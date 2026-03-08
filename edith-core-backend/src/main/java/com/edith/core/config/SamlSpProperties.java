package com.edith.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dynamic SP registry — add new SPs purely through application.properties:
 *
 *   saml.sp.providers.wire.entity-id=https://wire.edith.com/saml/metadata
 *   saml.sp.providers.wire.acs-url=https://wire.edith.com/saml/acs
 */
@Configuration
@ConfigurationProperties(prefix = "saml.sp")
public class SamlSpProperties {

    private Map<String, SpEntry> providers = new LinkedHashMap<>();

    public Map<String, SpEntry> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, SpEntry> providers) {
        this.providers = providers;
    }

    public static class SpEntry {
        private String entityId;
        private String acsUrl;

        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }
        public String getAcsUrl() { return acsUrl; }
        public void setAcsUrl(String acsUrl) { this.acsUrl = acsUrl; }
    }
}
