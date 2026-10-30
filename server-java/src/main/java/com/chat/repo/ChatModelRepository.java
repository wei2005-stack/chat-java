package com.chat.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ChatModelRepository {

    public record ChatModel(long id, String name, String label, boolean isDefault, String url,
                            String apiAuthHeader, String apiAuthKey, long userId,
                            boolean enablePerModeRateLimit, Integer maxToken, Integer defaultToken,
                            int orderNumber, int httpTimeOut, boolean isEnable, String apiType,
                            boolean isTitleModel) {
    }

    private static final RowMapper<ChatModel> MAPPER = (rs, i) -> new ChatModel(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("label"),
            rs.getBoolean("is_default"),
            rs.getString("url"),
            rs.getString("api_auth_header"),
            rs.getString("api_auth_key"),
            rs.getLong("user_id"),
            rs.getBoolean("enable_per_mode_ratelimit"),
            (Integer) rs.getObject("max_token"),
            (Integer) rs.getObject("default_token"),
            rs.getInt("order_number"),
            rs.getInt("http_time_out"),
            rs.getBoolean("is_enable"),
            rs.getString("api_type"),
            rs.getBoolean("is_title_model"));

    private final JdbcTemplate jdbc;

    public ChatModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChatModel> listEnabled() {
        return jdbc.query("SELECT * FROM chat_model WHERE is_enable = true ORDER BY order_number, id", MAPPER);
    }

    public ChatModel findDefault() {
        List<ChatModel> rows = jdbc.query(
                "SELECT * FROM chat_model WHERE is_enable = true AND is_default = true ORDER BY id LIMIT 1", MAPPER);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        List<ChatModel> enabled = listEnabled();
        return enabled.isEmpty() ? null : enabled.get(0);
    }

    public ChatModel findByName(String name) {
        List<ChatModel> rows = jdbc.query("SELECT * FROM chat_model WHERE name = ?", MAPPER, name);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
