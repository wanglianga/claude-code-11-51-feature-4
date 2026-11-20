package com.semantic.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 认证拦截器：除 /api/auth/** 与静态资源外，要求有效令牌。 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/") || !path.startsWith("/api/")) {
            return true;
        }
        String token = request.getHeader("X-Token");
        AuthService.Session session = authService.resolve(token);
        if (session == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录或令牌无效\"}");
            return false;
        }
        request.setAttribute("user", session.username());
        request.setAttribute("role", session.role());
        request.setAttribute("displayName", session.displayName());
        return true;
    }
}
