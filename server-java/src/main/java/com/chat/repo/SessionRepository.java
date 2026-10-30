package com.chat.repo;

import com.chat.util.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class SessionRepository {

    public record ChatSession(long id, long userId, String uuid, String topic, boolean active,
                              String model, int maxLength, double temperature, double topP,
                              int maxTokens, int n, boolean summarizeMode, Long workspaceId,
                              boolean artifactEnabled, boolean exploreMode,
                              Timestamp createdAt, Timestamp updatedAt) {
    }

    private static final RowMapper<ChatSession> MAPPER = (rs, i) -> new ChatSession(
            rs.getLong("id"), rs.getLong("user_id"), rs.getString("uuid"), rs.getString("topic"),
            rs.getBoolean("active"), rs.getString("model"), rs.getInt("max_length"),
            rs.getDouble("temperature"), rs.getDouble("top_p"), rs.getInt("max_tokens"),
            rs.getInt("n"), rs.getBoolean("summarize_mode"), workspaceId(rs),
            rs.getBoolean("artifact_enabled"), rs.getBoolean("explore_mode"),
            rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));

    /** workspace_id may be an INTEGER column, so avoid casting the raw JDBC object. */
    private static Long workspaceId(java.sql.ResultSet rs) throws java.sql.SQLException {
        long value = rs.getLong("workspace_id");
        return rs.wasNull() ? null : value;
    }

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ChatSession findByUuidAndUser(String uuid, long userId) {
        List<ChatSession> rows = jdbc.query(
                "SELECT * FROM chat_session WHERE uuid = ? AND user_id = ? ORDER BY updated_at DESC LIMIT 1",
                MAPPER, uuid, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ChatSession> listByUser(long userId) {
        return jdbc.query("""
                SELECT cs.*
                FROM chat_session cs
                LEFT JOIN (
                    SELECT chat_session_uuid, MAX(created_at) AS latest_message_time
                    FROM chat_message GROUP BY chat_session_uuid
                ) cm ON cs.uuid = cm.chat_session_uuid
                WHERE cs.user_id = ? AND cs.active = true
                ORDER BY cm.latest_message_time DESC, cs.id DESC
                """, MAPPER, userId);
    }

    public ChatSession createInWorkspace(long userId, String uuid, String topic, String model,
                                         Long workspaceId, int maxLength) {
        return jdbc.queryForObject("""
                INSERT INTO chat_session (user_id, uuid, topic, created_at, active, max_length, model, workspace_id)
                VALUES (?, ?, ?, now(), true, ?, ?, ?)
                RETURNING *
                """, MAPPER, userId, uuid, topic, maxLength, model, workspaceId);
    }

    public ChatSession createOrUpdate(String uuid, long userId, String topic, int maxLength,
                                      double temperature, String model, int maxTokens, double topP,
                                      int n, boolean summarizeMode, Long workspaceId,
                                      boolean exploreMode, boolean artifactEnabled) {
        return jdbc.queryForObject("""
                INSERT INTO chat_session(uuid, user_id, topic, max_length, temperature, model, max_tokens,
                                         top_p, n, summarize_mode, workspace_id, explore_mode, artifact_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid) DO UPDATE SET
                    max_length = EXCLUDED.max_length,
                    max_tokens = EXCLUDED.max_tokens,
                    temperature = EXCLUDED.temperature,
                    top_p = EXCLUDED.top_p,
                    n = EXCLUDED.n,
                    model = EXCLUDED.model,
                    summarize_mode = EXCLUDED.summarize_mode,
                    artifact_enabled = EXCLUDED.artifact_enabled,
                    workspace_id = COALESCE(EXCLUDED.workspace_id, chat_session.workspace_id),
                    topic = CASE WHEN chat_session.topic IS NULL THEN EXCLUDED.topic ELSE chat_session.topic END,
                    explore_mode = EXCLUDED.explore_mode,
                    updated_at = now()
                WHERE chat_session.user_id = EXCLUDED.user_id
                RETURNING *
                """, MAPPER, uuid, userId, topic, maxLength, temperature, model, maxTokens,
                topP, n, summarizeMode, workspaceId, exploreMode, artifactEnabled);
    }

    public void updateTopic(String uuid, long userId, String topic) {
        jdbc.update("UPDATE chat_session SET topic = ?, updated_at = now() WHERE uuid = ? AND user_id = ?",
                topic, uuid, userId);
    }

    public void updateMaxLength(String uuid, long userId, int maxLength) {
        jdbc.update("UPDATE chat_session SET max_length = ?, updated_at = now() WHERE uuid = ? AND user_id = ?",
                maxLength, uuid, userId);
    }

    public int deactivate(String uuid, long userId) {
        return jdbc.update("UPDATE chat_session SET active = false, updated_at = now() WHERE uuid = ? AND user_id = ?",
                uuid, userId);
    }

    public static String formatTimestamp(Timestamp ts) {
        return Times.iso(ts);
    }
}
