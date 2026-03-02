package com.jarvis.bank.controller;

import com.jarvis.bank.model.UserInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final List<UserInfo> USERS = List.of(
            new UserInfo("jarvis1", "password123", "jarvis1@jarvisbank.com", "Jarvis User One"),
            new UserInfo("jarvis2", "password123", "jarvis2@jarvisbank.com", "Jarvis User Two")
    );

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        return USERS.stream()
                .filter(u -> u.getUsername().equals(username) && u.getPassword().equals(password))
                .findFirst()
                .map(user -> ResponseEntity.ok(Map.of(
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "displayName", user.getDisplayName()
                )))
                .orElse(ResponseEntity.status(401).body(Map.of(
                        "error", "Invalid username or password"
                )));
    }
}
