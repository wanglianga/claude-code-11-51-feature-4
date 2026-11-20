package com.semantic.platform.api;

import com.semantic.platform.common.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        AuthService.Session session = authService.login(body.get("username"), body.get("password"));
        String token = authService.issueToken(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("username", session.username());
        out.put("role", session.role());
        out.put("displayName", session.displayName());
        return out;
    }
}
