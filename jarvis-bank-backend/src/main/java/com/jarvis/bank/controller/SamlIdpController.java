package com.jarvis.bank.controller;

import com.jarvis.bank.model.UserInfo;
import com.jarvis.bank.service.SamlIdpService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/saml")
public class SamlIdpController {

    private final SamlIdpService samlIdpService;

    public SamlIdpController(SamlIdpService samlIdpService) {
        this.samlIdpService = samlIdpService;
    }

    @PostMapping("/sso")
    public ResponseEntity<?> generateSsoResponse(@RequestBody Map<String, String> payload) {
        try {
            String spName = payload.get("spName");
            if (spName == null || spName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing spName"));
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
                            Location="http://localhost:5001/saml/sso"/>
                    </IDPSSODescriptor>
                </EntityDescriptor>
                """.formatted(entityId);
        return ResponseEntity.ok().header("Content-Type", "application/xml").body(metadata);
    }
}
