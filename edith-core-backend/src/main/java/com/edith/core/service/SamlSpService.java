package com.edith.core.service;

import com.edith.core.model.SamlUserInfo;
import net.shibboleth.utilities.java.support.xml.BasicParserPool;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.io.UnmarshallerFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.saml2.core.*;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

@Service
public class SamlSpService {

    @Value("${saml.bank-idp.cert-path}")
    private Resource bankIdpCertResource;

    @Value("${saml.bank-idp.entity-id}")
    private String bankIdpEntityId;

    @Value("${saml.jarvis-idp.cert-path}")
    private Resource jarvisIdpCertResource;

    @Value("${saml.jarvis-idp.entity-id}")
    private String jarvisIdpEntityId;

    @Value("${saml.core-sp.entity-id}")
    private String spEntityId;

    private Map<String, Credential> trustedIdpCredentials;
    private BasicParserPool parserPool;

    @PostConstruct
    public void init() throws Exception {
        trustedIdpCredentials = new HashMap<>();
        trustedIdpCredentials.put(bankIdpEntityId, loadCertCredential(bankIdpCertResource));
        trustedIdpCredentials.put(jarvisIdpEntityId, loadCertCredential(jarvisIdpCertResource));

        parserPool = new BasicParserPool();
        parserPool.setNamespaceAware(true);
        parserPool.initialize();
    }

    private Credential loadCertCredential(Resource certResource) throws Exception {
        X509Certificate cert;
        try (InputStream is = certResource.getInputStream()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate) cf.generateCertificate(is);
        }
        return new BasicX509Credential(cert);
    }

    public SamlUserInfo validateAndParse(String samlResponseBase64) throws Exception {
        byte[] decodedBytes = Base64.getDecoder().decode(samlResponseBase64);
        String xml = new String(decodedBytes, "UTF-8");

        Document document = parserPool.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        Element element = document.getDocumentElement();

        UnmarshallerFactory unmarshallerFactory = XMLObjectProviderRegistrySupport.getUnmarshallerFactory();
        Response response = (Response) unmarshallerFactory.getUnmarshaller(element).unmarshall(element);

        // Identify the issuer and find the matching trusted IdP credential
        String issuerValue = response.getIssuer() != null ? response.getIssuer().getValue() : null;
        if (issuerValue == null) {
            throw new RuntimeException("SAML Response has no Issuer");
        }

        Credential idpCredential = trustedIdpCredentials.get(issuerValue);
        if (idpCredential == null) {
            throw new RuntimeException("Untrusted IdP issuer: " + issuerValue);
        }

        // Validate signature against the matched IdP's certificate
        Signature signature = response.getSignature();
        if (signature != null) {
            SignatureValidator.validate(signature, idpCredential);
        }

        // Validate status
        if (response.getStatus() == null ||
            response.getStatus().getStatusCode() == null ||
            !StatusCode.SUCCESS.equals(response.getStatus().getStatusCode().getValue())) {
            throw new RuntimeException("SAML Response status is not Success");
        }

        // Extract assertion
        if (response.getAssertions().isEmpty()) {
            throw new RuntimeException("No assertions found in SAML Response");
        }
        Assertion assertion = response.getAssertions().get(0);

        String nameID = assertion.getSubject().getNameID().getValue();

        Map<String, String> attributes = new HashMap<>();
        for (AttributeStatement attrStmt : assertion.getAttributeStatements()) {
            for (Attribute attr : attrStmt.getAttributes()) {
                String attrName = attr.getName();
                if (!attr.getAttributeValues().isEmpty()) {
                    XMLObject valueObj = attr.getAttributeValues().get(0);
                    if (valueObj instanceof XSString) {
                        attributes.put(attrName, ((XSString) valueObj).getValue());
                    }
                }
            }
        }

        SamlUserInfo userInfo = new SamlUserInfo();
        userInfo.setNameID(nameID);
        userInfo.setEmail(nameID);
        userInfo.setDisplayName(attributes.getOrDefault("displayName", nameID));
        userInfo.setAttributes(attributes);

        return userInfo;
    }

    public String getSpEntityId() {
        return spEntityId;
    }
}
