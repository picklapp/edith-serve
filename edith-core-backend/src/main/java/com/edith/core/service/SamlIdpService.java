package com.edith.core.service;

import com.edith.core.config.SamlSpProperties;
import com.edith.core.model.UserInfo;
import net.shibboleth.utilities.java.support.xml.SerializeSupport;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.Marshaller;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.schema.impl.XSStringBuilder;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.*;
import org.opensaml.saml.saml2.core.impl.*;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.impl.SignatureBuilder;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

@Service
public class SamlIdpService {

    private static final Logger log = Logger.getLogger(SamlIdpService.class.getName());

    @Value("${saml.idp.entity-id}")
    private String idpEntityId;

    @Value("${saml.idp.cert-path}")
    private Resource certResource;

    @Value("${saml.idp.key-path}")
    private Resource keyResource;

    private final SamlSpProperties spProperties;

    private Credential signingCredential;
    private Map<String, SpConfig> spConfigs;

    public SamlIdpService(SamlSpProperties spProperties) {
        this.spProperties = spProperties;
    }

    @PostConstruct
    public void init() throws Exception {
        InitializationService.initialize();
        signingCredential = loadCredential();

        // Dynamically load all SPs from configuration
        spConfigs = new HashMap<>();
        for (Map.Entry<String, SamlSpProperties.SpEntry> entry : spProperties.getProviders().entrySet()) {
            String spName = entry.getKey();
            SamlSpProperties.SpEntry sp = entry.getValue();
            spConfigs.put(spName, new SpConfig(sp.getEntityId(), sp.getAcsUrl()));
            log.info("Registered SP: " + spName + " -> " + sp.getEntityId());
        }
        log.info("Total SPs registered: " + spConfigs.size());
    }

    private Credential loadCredential() throws Exception {
        X509Certificate cert;
        try (InputStream is = certResource.getInputStream()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate) cf.generateCertificate(is);
        }

        String keyPem;
        try (InputStream is = keyResource.getInputStream()) {
            keyPem = new String(is.readAllBytes());
        }
        String keyBase64 = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);

        BasicX509Credential credential = new BasicX509Credential(cert, privateKey);
        return credential;
    }

    public SpConfig getSpConfig(String spName) {
        SpConfig config = spConfigs.get(spName);
        if (config == null) {
            throw new IllegalArgumentException("Unknown SP: " + spName
                    + ". Registered SPs: " + spConfigs.keySet());
        }
        return config;
    }

    public Map<String, SpConfig> getAllSpConfigs() {
        return Collections.unmodifiableMap(spConfigs);
    }

    public String generateSamlResponse(UserInfo user, String spName) throws Exception {
        SpConfig sp = getSpConfig(spName);
        Response response = buildResponse(user, sp.entityId(), sp.acsUrl());

        Signature signature = new SignatureBuilder().buildObject();
        signature.setSigningCredential(signingCredential);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        response.setSignature(signature);

        Marshaller marshaller = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                .getMarshaller(response);
        Element element = marshaller.marshall(response);
        Signer.signObject(signature);

        String xml = SerializeSupport.nodeToString(element);
        return Base64.getEncoder().encodeToString(xml.getBytes("UTF-8"));
    }

    private Response buildResponse(UserInfo user, String spEntityId, String acsUrl) {
        Instant now = Instant.now();
        Instant notOnOrAfter = now.plusSeconds(300);

        Issuer responseIssuer = new IssuerBuilder().buildObject();
        responseIssuer.setValue(idpEntityId);

        StatusCode statusCode = new StatusCodeBuilder().buildObject();
        statusCode.setValue(StatusCode.SUCCESS);
        Status status = new StatusBuilder().buildObject();
        status.setStatusCode(statusCode);

        NameID nameID = new NameIDBuilder().buildObject();
        nameID.setValue(user.getEmail());
        nameID.setFormat(NameIDType.EMAIL);

        SubjectConfirmationData confirmationData = new SubjectConfirmationDataBuilder().buildObject();
        confirmationData.setRecipient(acsUrl);
        confirmationData.setNotOnOrAfter(notOnOrAfter);

        SubjectConfirmation subjectConfirmation = new SubjectConfirmationBuilder().buildObject();
        subjectConfirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        subjectConfirmation.setSubjectConfirmationData(confirmationData);

        Subject subject = new SubjectBuilder().buildObject();
        subject.setNameID(nameID);
        subject.getSubjectConfirmations().add(subjectConfirmation);

        Audience audience = new AudienceBuilder().buildObject();
        audience.setURI(spEntityId);

        AudienceRestriction audienceRestriction = new AudienceRestrictionBuilder().buildObject();
        audienceRestriction.getAudiences().add(audience);

        Conditions conditions = new ConditionsBuilder().buildObject();
        conditions.setNotBefore(now);
        conditions.setNotOnOrAfter(notOnOrAfter);
        conditions.getAudienceRestrictions().add(audienceRestriction);

        AuthnContextClassRef authnContextClassRef = new AuthnContextClassRefBuilder().buildObject();
        authnContextClassRef.setURI(AuthnContext.PASSWORD_AUTHN_CTX);

        AuthnContext authnContext = new AuthnContextBuilder().buildObject();
        authnContext.setAuthnContextClassRef(authnContextClassRef);

        AuthnStatement authnStatement = new AuthnStatementBuilder().buildObject();
        authnStatement.setAuthnInstant(now);
        authnStatement.setAuthnContext(authnContext);

        AttributeStatement attrStatement = new AttributeStatementBuilder().buildObject();
        attrStatement.getAttributes().add(buildAttribute("email", user.getEmail()));
        attrStatement.getAttributes().add(buildAttribute("displayName", user.getDisplayName()));

        Issuer assertionIssuer = new IssuerBuilder().buildObject();
        assertionIssuer.setValue(idpEntityId);

        Assertion assertion = new AssertionBuilder().buildObject();
        assertion.setID("_" + UUID.randomUUID().toString());
        assertion.setIssueInstant(now);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssuer(assertionIssuer);
        assertion.setSubject(subject);
        assertion.setConditions(conditions);
        assertion.getAuthnStatements().add(authnStatement);
        assertion.getAttributeStatements().add(attrStatement);

        Response response = new ResponseBuilder().buildObject();
        response.setID("_" + UUID.randomUUID().toString());
        response.setIssueInstant(now);
        response.setVersion(SAMLVersion.VERSION_20);
        response.setDestination(acsUrl);
        response.setIssuer(responseIssuer);
        response.setStatus(status);
        response.getAssertions().add(assertion);

        return response;
    }

    private Attribute buildAttribute(String name, String value) {
        XSStringBuilder stringBuilder = (XSStringBuilder) XMLObjectProviderRegistrySupport
                .getBuilderFactory().getBuilder(XSString.TYPE_NAME);
        XSString attrValue = stringBuilder.buildObject(
                AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME);
        attrValue.setValue(value);

        Attribute attribute = new AttributeBuilder().buildObject();
        attribute.setName(name);
        attribute.getAttributeValues().add(attrValue);
        return attribute;
    }

    public String getIdpEntityId() {
        return idpEntityId;
    }

    public record SpConfig(String entityId, String acsUrl) {}
}
