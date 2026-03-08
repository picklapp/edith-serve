package com.edith.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dynamic trusted IdP registry — add new IdPs purely through application.properties:
 *
 *   saml.trusted-idps.providers.meridian-bank.entity-id=https://sso.meridianbank.com/saml/metadata
 *   saml.trusted-idps.providers.meridian-bank.cert-path=classpath:saml/meridian-idp-cert.pem
 */
@Configuration
@ConfigurationProperties(prefix = "saml.trusted-idps")
public class SamlIdpProperties {

    private Map<String, IdpEntry> providers = new LinkedHashMap<>();

    public Map<String, IdpEntry> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, IdpEntry> providers) {
        this.providers = providers;
    }

    public static class IdpEntry {
        private String entityId;
        private String certPath;

        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }
        public String getCertPath() { return certPath; }
        public void setCertPath(String certPath) { this.certPath = certPath; }
    }
}
