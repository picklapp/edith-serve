package com.edith.rdc.service;

import com.edith.rdc.model.SamlUserInfo;
import net.shibboleth.utilities.java.support.xml.BasicParserPool;
import org.opensaml.core.config.InitializationService;
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

    @Value("${saml.idp.cert-path}")
    private Resource idpCertResource;

    @Value("${saml.sp.entity-id}")
    private String spEntityId;

    @Value("${saml.idp.entity-id}")
    private String idpEntityId;

    private Credential idpCredential;
    private BasicParserPool parserPool;

    @PostConstruct
    public void init() throws Exception {
        InitializationService.initialize();
        idpCredential = loadIdpCredential();

        parserPool = new BasicParserPool();
        parserPool.setNamespaceAware(true);
        parserPool.initialize();
    }

    private Credential loadIdpCredential() throws Exception {
        X509Certificate cert;
        try (InputStream is = idpCertResource.getInputStream()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate) cf.generateCertificate(is);
        }
        return new BasicX509Credential(cert);
    }

    public SamlUserInfo validateAndParse(String samlResponseBase64) throws Exception {
        // Decode base64
        byte[] decodedBytes = Base64.getDecoder().decode(samlResponseBase64);
        String xml = new String(decodedBytes, "UTF-8");

        // Parse XML
        Document document = parserPool.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        Element element = document.getDocumentElement();

        // Unmarshal to OpenSAML Response object
        UnmarshallerFactory unmarshallerFactory = XMLObjectProviderRegistrySupport.getUnmarshallerFactory();
        Response response = (Response) unmarshallerFactory.getUnmarshaller(element).unmarshall(element);

        // Validate signature
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

        // Validate issuer
        if (response.getIssuer() != null &&
            !idpEntityId.equals(response.getIssuer().getValue())) {
            throw new RuntimeException("Invalid issuer: " + response.getIssuer().getValue());
        }

        // Extract assertion
        if (response.getAssertions().isEmpty()) {
            throw new RuntimeException("No assertions found in SAML Response");
        }
        Assertion assertion = response.getAssertions().get(0);

        // Extract NameID
        String nameID = assertion.getSubject().getNameID().getValue();

        // Extract attributes
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

        // Build user info
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
