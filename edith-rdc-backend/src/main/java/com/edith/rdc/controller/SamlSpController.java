package com.edith.rdc.controller;

import com.edith.rdc.model.SamlUserInfo;
import com.edith.rdc.service.SamlSpService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/saml")
public class SamlSpController {

    private final SamlSpService samlSpService;

    public SamlSpController(SamlSpService samlSpService) {
        this.samlSpService = samlSpService;
    }

    @PostMapping("/acs")
    public ResponseEntity<?> assertionConsumerService(@RequestBody Map<String, String> payload) {
        try {
            String samlResponse = payload.get("samlResponse");
            if (samlResponse == null || samlResponse.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing SAMLResponse"));
            }

            SamlUserInfo userInfo = samlSpService.validateAndParse(samlResponse);

            return ResponseEntity.ok(Map.of(
                    "nameID", userInfo.getNameID(),
                    "email", userInfo.getEmail(),
                    "displayName", userInfo.getDisplayName(),
                    "attributes", userInfo.getAttributes()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SAML validation failed: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/metadata")
    public ResponseEntity<String> metadata() {
        String entityId = samlSpService.getSpEntityId();
        String metadata = """
                <?xml version="1.0" encoding="UTF-8"?>
                <EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata"
                                  entityID="%s">
                    <SPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol"
                                     AuthnRequestsSigned="false"
                                     WantAssertionsSigned="true">
                        <AssertionConsumerService
                            Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                            Location="http://localhost:4000/saml/acs"
                            index="0"/>
                    </SPSSODescriptor>
                </EntityDescriptor>
                """.formatted(entityId);
        return ResponseEntity.ok().header("Content-Type", "application/xml").body(metadata);
    }
}
