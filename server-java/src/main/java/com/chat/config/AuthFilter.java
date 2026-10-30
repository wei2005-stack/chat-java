package com.chat.config;

import com.chat.web.SceneExceptionWriter;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Minimal bearer-token gate. Spring Security is intentionally not used: the
 * surface is small and a servlet filter keeps the authentication flow explicit.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTR = "chat.userId";
    public static final String USER_ROLE_ATTR = "chat.userRole";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/login",
            "/api/signup",
            "/api/auth/refresh",
            "/api/logout"
    );

    private final JwtService jwtService;

    public AuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || PUBLIC_PATHS.contains(path) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "Missing bearer token");
            return;
        }
        try {
            Claims claims = jwtService.parse(header.substring("Bearer ".length()), JwtService.TYPE_ACCESS);
            request.setAttribute(USER_ID_ATTR, Long.parseLong(claims.getSubject()));
            request.setAttribute(USER_ROLE_ATTR, claims.get("role", String.class));
            chain.doFilter(request, response);
        } catch (Exception ex) {
            writeUnauthorized(response, "Invalid or expired token");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SceneExceptionWriter.toJson("AUTH_INVALID_CREDENTIALS", message));
    }
}
