package com.edith.core.controller;

import com.edith.core.model.UserInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final List<UserInfo> USERS = List.of(
            new UserInfo("john", "password123", "john@example.com", "John Doe"),
            new UserInfo("jane", "password123", "jane@example.com", "Jane Smith")
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
