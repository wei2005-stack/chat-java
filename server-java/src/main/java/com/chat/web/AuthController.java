package com.chat.web;

import com.chat.config.JwtService;
import com.chat.repo.UserRepository;
import com.chat.service.PasswordHasher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Login / signup / refresh / logout. Access tokens are returned in the body,
 * the refresh token lives in an HttpOnly cookie so the Vue client can silently
 * refresh on startup, exactly like the Go backend.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    public record TokenResult(String accessToken, long expiresIn) {
    }

    public record LoginParams(String email, String password) {
    }

    private final UserRepository users;
    private final JwtService jwt;

    public AuthController(UserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResult> login(@RequestBody LoginParams params) {
        if (params.email() == null || params.password() == null || params.email().isBlank()) {
            throw ApiException.invalid("email and password are required");
        }
        UserRepository.AuthUser user = users.findByEmail(params.email().trim());
        if (user == null || !user.active() || !PasswordHasher.matches(params.password(), user.password())) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        users.touchLastLogin(user.id());
        return issueTokens(user, HttpStatus.OK);
    }

    @PostMapping("/signup")
    public ResponseEntity<TokenResult> signup(@RequestBody LoginParams params) {
        if (params.email() == null || params.password() == null || params.email().isBlank()) {
            throw ApiException.invalid("email and password are required");
        }
        if (users.findByEmail(params.email().trim()) != null) {
            throw ApiException.invalid("email already registered");
        }
        UserRepository.AuthUser user = users.create(params.email().trim(),
                PasswordHasher.hash(params.password()), false);
        return issueTokens(user, HttpStatus.CREATED);
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<TokenResult> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("Missing refresh token");
        }
        var claims = jwt.parse(refreshToken, JwtService.TYPE_REFRESH);
        long userId = Long.parseLong(claims.getSubject());
        String role = claims.get("role", String.class);
        return ResponseEntity.ok(new TokenResult(jwt.generateAccessToken(userId, role),
                jwt.accessTokenExpiresInEpochSeconds()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true).path("/").maxAge(0).sameSite("Lax").build();
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    private ResponseEntity<TokenResult> issueTokens(UserRepository.AuthUser user, HttpStatus status) {
        String access = jwt.generateAccessToken(user.id(), user.role());
        ResponseCookie cookie = ResponseCookie.from("refresh_token", jwt.generateRefreshToken(user.id(), user.role()))
                .httpOnly(true).path("/").maxAge(jwt.refreshLifetimeSeconds()).sameSite("Lax").build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new TokenResult(access, jwt.accessTokenExpiresInEpochSeconds()));
    }

    @GetMapping("/users/me")
    public Map<String, Object> me() {
        UserRepository.AuthUser user = users.findById(AuthContext.userId());
        if (user == null) {
            throw ApiException.unauthorized("user not found");
        }
        return Map.of("id", user.id(), "email", user.email(), "username", user.username(),
                "isSuperuser", user.superuser());
    }
}
