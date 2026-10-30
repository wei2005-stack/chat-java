package com.chat.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Compatible with the Go implementation's password format:
 * {@code pbkdf2_sha256$<iterations>$<base64 salt>$<base64 hash>}.
 */
public final class PasswordHasher {

    private static final int KEY_BITS = 256;

    private PasswordHasher() {
    }

    public static String hash(String password) {
        int iterations = 260000;
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] key = derive(password, salt, iterations);
        return "pbkdf2_sha256$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    public static boolean matches(String password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot derive password hash", ex);
        }
    }

    /** Rough token estimate used across the app, matching the Go ratio of 4 chars per token. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chars = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, chars / 4);
    }
}
