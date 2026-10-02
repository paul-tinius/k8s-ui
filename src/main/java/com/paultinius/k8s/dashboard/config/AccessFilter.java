package com.paultinius.k8s.dashboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public AccessFilter(DashboardProperties properties) {
        String token = properties.getAuth().getToken();
        this.expected = token == null || token.isBlank() ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (expected.length == 0 || isPublic(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String presented = bearer(request.getHeader("Authorization"));
        if (presented == null && queryTokenAllowed(request.getRequestURI())) {
            presented = request.getParameter("access_token");
        }
        if (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean queryTokenAllowed(String path) {
        return "/api/live".equals(path) || "/api/logs/stream".equals(path);
    }

    private static boolean isPublic(String path) {
        if (path == null || "/".equals(path) || "/index.html".equals(path)) {
            return true;
        }
        return "/app.js".equals(path)
                || "/app.css".equals(path)
                || "/favicon.svg".equals(path)
                || "/actuator/health".equals(path)
                || path.startsWith("/actuator/health/");
    }

    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String token = header.substring(prefix.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
