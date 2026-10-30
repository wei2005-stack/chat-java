package com.chat.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * Signs and verifies the access/refresh JWTs. The token shape mirrors the Go
 * implementation: subject is the user id, plus role and token type claims.
 */
@Component
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final String audience;
    private final Duration accessLifetime;
    private final Duration refreshLifetime;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.audience}") String audience,
            @Value("${app.jwt.access-token-minutes}") long accessMinutes,
            @Value("${app.jwt.refresh-token-days}") long refreshDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.audience = audience;
        this.accessLifetime = Duration.ofMinutes(accessMinutes);
        this.refreshLifetime = Duration.ofDays(refreshDays);
    }

    public String generateAccessToken(long userId, String role) {
        return generate(userId, role, TYPE_ACCESS, accessLifetime);
    }

    public String generateRefreshToken(long userId, String role) {
        return generate(userId, role, TYPE_REFRESH, refreshLifetime);
    }

    public long accessTokenExpiresInEpochSeconds() {
        return System.currentTimeMillis() / 1000 + accessLifetime.getSeconds();
    }

    public long refreshLifetimeSeconds() {
        return refreshLifetime.getSeconds();
    }

    private String generate(long userId, String role, String type, Duration lifetime) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(Long.toString(userId))
                .setAudience(audience)
                .claim("role", role)
                .claim("type", type)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + lifetime.toMillis()))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Returns the claims when the token is valid and of the expected type. */
    public Claims parse(String token, String expectedType) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .requireAudience(audience)
                .build()
                .parseClaimsJws(token)
                .getBody();
        if (!expectedType.equals(claims.get("type", String.class))) {
            throw new IllegalArgumentException("unexpected token type");
        }
        return claims;
    }
}
