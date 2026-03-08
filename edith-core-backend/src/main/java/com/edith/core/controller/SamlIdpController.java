package com.edith.core.controller;

import com.edith.core.model.UserInfo;
import com.edith.core.service.SamlIdpService;
import com.edith.core.service.SamlSpService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/saml")
public class SamlIdpController {

    private final SamlIdpService samlIdpService;
    private final SamlSpService samlSpService;

    public SamlIdpController(SamlIdpService samlIdpService, SamlSpService samlSpService) {
        this.samlIdpService = samlIdpService;
        this.samlSpService = samlSpService;
    }

    @PostMapping("/sso")
    public ResponseEntity<?> generateSsoResponse(@RequestBody Map<String, String> payload) {
        try {
            String spName = payload.get("spName");
            if (spName == null || spName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing spName (e.g. 'rdc' or 'ach')"));
            }

            UserInfo user = new UserInfo();
            user.setEmail(payload.get("email"));
            user.setDisplayName(payload.get("displayName"));

            SamlIdpService.SpConfig spConfig = samlIdpService.getSpConfig(spName);
            String samlResponse = samlIdpService.generateSamlResponse(user, spName);

            return ResponseEntity.ok(Map.of(
                    "samlResponse", samlResponse,
                    "acsUrl", spConfig.acsUrl()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to generate SAML response: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/metadata")
    public ResponseEntity<String> metadata() {
        String entityId = samlIdpService.getIdpEntityId();
        String metadata = """
                <?xml version="1.0" encoding="UTF-8"?>
                <EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata"
                                  entityID="%s">
                    <IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                        <SingleSignOnService
                            Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                            Location="http://localhost:3000/saml/sso"/>
                    </IDPSSODescriptor>
                </EntityDescriptor>
                """.formatted(entityId);
        return ResponseEntity.ok().header("Content-Type", "application/xml").body(metadata);
    }

    @GetMapping("/providers")
    public ResponseEntity<?> listProviders() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Registered SPs
        Map<String, Map<String, String>> sps = new LinkedHashMap<>();
        for (Map.Entry<String, SamlIdpService.SpConfig> entry : samlIdpService.getAllSpConfigs().entrySet()) {
            sps.put(entry.getKey(), Map.of(
                "entityId", entry.getValue().entityId(),
                "acsUrl", entry.getValue().acsUrl()
            ));
        }
        result.put("serviceProviders", sps);

        // Trusted IdPs
        result.put("trustedIdpIssuers", samlSpService.getTrustedIssuers());

        result.put("idpEntityId", samlIdpService.getIdpEntityId());
        result.put("spEntityId", samlSpService.getSpEntityId());

        return ResponseEntity.ok(result);
    }
}
