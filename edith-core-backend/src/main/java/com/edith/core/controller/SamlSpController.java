package com.edith.core.controller;

import com.edith.core.model.SamlUserInfo;
import com.edith.core.service.SamlSpService;
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
}
