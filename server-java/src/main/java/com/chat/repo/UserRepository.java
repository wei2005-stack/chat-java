package com.chat.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class UserRepository {

    public record AuthUser(long id, String username, String email, String password,
                           boolean superuser, boolean staff, boolean active, Timestamp dateJoined) {
        public String role() {
            return superuser ? "admin" : "user";
        }
    }

    private static final RowMapper<AuthUser> MAPPER = (rs, i) -> new AuthUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("password"),
            rs.getBoolean("is_superuser"),
            rs.getBoolean("is_staff"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("date_joined"));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AuthUser findByEmail(String email) {
        List<AuthUser> rows = jdbc.query(
                "SELECT * FROM auth_user WHERE lower(email) = lower(?)", MAPPER, email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AuthUser findById(long id) {
        List<AuthUser> rows = jdbc.query("SELECT * FROM auth_user WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AuthUser create(String email, String passwordHash, boolean superuser) {
        return jdbc.queryForObject("""
                INSERT INTO auth_user (password, username, email, is_superuser, is_staff, is_active)
                VALUES (?, ?, ?, ?, ?, true)
                RETURNING *
                """, MAPPER, passwordHash, email, email, superuser, superuser);
    }

    public void touchLastLogin(long userId) {
        jdbc.update("UPDATE auth_user SET last_login = now() WHERE id = ?", userId);
    }
}
