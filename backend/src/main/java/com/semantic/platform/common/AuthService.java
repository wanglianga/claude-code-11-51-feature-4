package com.semantic.platform.common;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简版认证：内置三个测试账号，登录发令牌（内存保存，演示用）。
 * admin=指标管理员, analyst=分析员, manager=经营负责人
 */
@Service
public class AuthService {

    public record Session(String username, String role, String displayName) {}

    private static final Map<String, String[]> USERS = Map.of(
            "admin", new String[]{"admin123", "ADMIN", "指标管理员"},
            "analyst", new String[]{"analyst123", "ANALYST", "分析员"},
            "manager", new String[]{"manager123", "MANAGER", "经营负责人"}
    );

    private final Map<String, Session> tokens = new ConcurrentHashMap<>();

    public Session login(String username, String password) {
        String[] u = USERS.get(username);
        if (u == null || !u[0].equals(password)) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return new Session(username, u[1], u[2]);
    }

    public String issueToken(Session session) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, session);
        return token;
    }

    public Session resolve(String token) {
        return token == null ? null : tokens.get(token);
    }
}
