package com.chat.repo;

import com.chat.util.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class WorkspaceRepository {

    public record Workspace(long id, String uuid, long userId, String name, String description,
                            String color, String icon, boolean isDefault, int orderPosition,
                            Timestamp createdAt, Timestamp updatedAt, long sessionCount) {
    }

    public record SessionSummary(String uuid, String topic, String model, int maxLength,
                                 double temperature, double topP, int n, int maxTokens,
                                 boolean summarizeMode, boolean exploreMode, boolean artifactEnabled,
                                 Timestamp createdAt, Timestamp updatedAt) {
    }

    public record ActiveSession(String workspaceUuid, String sessionUuid, Timestamp updatedAt) {
    }

    private static final RowMapper<Workspace> MAPPER = (rs, i) -> new Workspace(
            rs.getLong("id"), rs.getString("uuid"), rs.getLong("user_id"), rs.getString("name"),
            rs.getString("description"), rs.getString("color"), rs.getString("icon"),
            rs.getBoolean("is_default"), rs.getInt("order_position"),
            rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"), 0L);

    private final JdbcTemplate jdbc;

    public WorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Workspace> listByUser(long userId) {
        return jdbc.query("""
                SELECT w.*, COUNT(cs.id) AS session_count
                FROM chat_workspace w
                LEFT JOIN chat_session cs ON w.id = cs.workspace_id AND cs.active = true
                WHERE w.user_id = ?
                GROUP BY w.id
                ORDER BY w.order_position ASC, w.created_at ASC
                """, (rs, i) -> new Workspace(
                rs.getLong("id"), rs.getString("uuid"), rs.getLong("user_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("color"), rs.getString("icon"),
                rs.getBoolean("is_default"), rs.getInt("order_position"),
                rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"),
                rs.getLong("session_count")), userId);
    }

    public Workspace findByUuid(String uuid) {
        List<Workspace> rows = jdbc.query("SELECT * FROM chat_workspace WHERE uuid = ?", MAPPER, uuid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Workspace create(long userId, String name, String description, String color, String icon,
                            boolean isDefault, int orderPosition) {
        return jdbc.queryForObject("""
                INSERT INTO chat_workspace (uuid, user_id, name, description, color, icon, is_default, order_position)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """, MAPPER, UUID.randomUUID().toString(), userId, name, description, color, icon,
                isDefault, orderPosition);
    }

    public Workspace update(String uuid, long userId, String name, String description, String color, String icon) {
        List<Workspace> rows = jdbc.query("""
                UPDATE chat_workspace SET name = ?, description = ?, color = ?, icon = ?, updated_at = now()
                WHERE uuid = ? AND user_id = ?
                RETURNING *
                """, MAPPER, name, description, color, icon, uuid, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int delete(String uuid, long userId) {
        return jdbc.update("DELETE FROM chat_workspace WHERE uuid = ? AND user_id = ?", uuid, userId);
    }

    public int updateOrder(String uuid, long userId, int orderPosition) {
        return jdbc.update("UPDATE chat_workspace SET order_position = ?, updated_at = now() WHERE uuid = ? AND user_id = ?",
                orderPosition, uuid, userId);
    }

    @Transactional
    public void setDefault(String uuid, long userId) {
        jdbc.update("UPDATE chat_workspace SET is_default = false, updated_at = now() WHERE user_id = ?", userId);
        jdbc.update("UPDATE chat_workspace SET is_default = true, updated_at = now() WHERE uuid = ? AND user_id = ?",
                uuid, userId);
    }

    /** Returns the user's default workspace, creating one when missing. */
    public Workspace ensureDefault(long userId) {
        List<Workspace> rows = jdbc.query(
                "SELECT * FROM chat_workspace WHERE user_id = ? AND is_default = true ORDER BY id LIMIT 1", MAPPER, userId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        List<Workspace> any = jdbc.query(
                "SELECT * FROM chat_workspace WHERE user_id = ? ORDER BY order_position, id LIMIT 1", MAPPER, userId);
        if (!any.isEmpty()) {
            setDefault(any.get(0).uuid(), userId);
            return findByUuid(any.get(0).uuid());
        }
        Workspace created = create(userId, "General", "Default workspace for all conversations",
                "#6366f1", "folder", true, 0);
        return created;
    }

    public List<SessionSummary> sessionsByWorkspaceId(long workspaceId) {
        return jdbc.query("""
                SELECT cs.*
                FROM chat_session cs
                LEFT JOIN (
                    SELECT chat_session_uuid, MAX(created_at) AS latest_message_time
                    FROM chat_message
                    GROUP BY chat_session_uuid
                ) cm ON cs.uuid = cm.chat_session_uuid
                WHERE cs.workspace_id = ? AND cs.active = true
                ORDER BY cm.latest_message_time DESC, cs.id DESC
                """, (rs, i) -> new SessionSummary(
                rs.getString("uuid"), rs.getString("topic"), rs.getString("model"),
                rs.getInt("max_length"), rs.getDouble("temperature"), rs.getDouble("top_p"),
                rs.getInt("n"), rs.getInt("max_tokens"), rs.getBoolean("summarize_mode"),
                rs.getBoolean("explore_mode"), rs.getBoolean("artifact_enabled"),
                rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")), workspaceId);
    }

    public List<ActiveSession> activeSessions(long userId) {
        return jdbc.query("""
                SELECT w.uuid AS workspace_uuid, a.chat_session_uuid, a.updated_at
                FROM user_active_chat_session a
                JOIN chat_workspace w ON w.id = a.workspace_id
                WHERE a.user_id = ?
                ORDER BY a.updated_at DESC
                """, (rs, i) -> new ActiveSession(
                rs.getString("workspace_uuid"), rs.getString("chat_session_uuid"),
                rs.getTimestamp("updated_at")), userId);
    }

    public void setActiveSession(long userId, Long workspaceId, String sessionUuid) {
        int key = workspaceId == null ? -1 : workspaceId.intValue();
        jdbc.update("""
                DELETE FROM user_active_chat_session
                WHERE user_id = ? AND COALESCE(workspace_id, -1) = ?
                """, userId, key);
        jdbc.update("""
                INSERT INTO user_active_chat_session (user_id, chat_session_uuid, workspace_id, updated_at)
                VALUES (?, ?, ?, now())
                """, userId, sessionUuid, workspaceId);
    }

    public record GlobalActiveSession(long id, long userId, String sessionUuid,
                                      String createdAt, String updatedAt) {
    }

    /** The global (workspace-less) active session used to restore the last conversation. */
    public GlobalActiveSession findGlobalActive(long userId) {
        List<GlobalActiveSession> rows = jdbc.query("""
                SELECT id, user_id, chat_session_uuid, created_at, updated_at
                FROM user_active_chat_session
                WHERE user_id = ? AND workspace_id IS NULL
                ORDER BY updated_at DESC LIMIT 1
                """, (rs, i) -> new GlobalActiveSession(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("chat_session_uuid"), Times.iso(rs.getTimestamp("created_at")),
                Times.iso(rs.getTimestamp("updated_at"))), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int migrateLegacySessions(long userId, long workspaceId) {
        return jdbc.update("""
                UPDATE chat_session SET workspace_id = ?, updated_at = now()
                WHERE user_id = ? AND workspace_id IS NULL
                """, workspaceId, userId);
    }

    public long countLegacySessions(long userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_session WHERE user_id = ? AND workspace_id IS NULL", Long.class, userId);
        return count == null ? 0 : count;
    }

    public static String formatTimestamp(Timestamp ts) {
        return Times.iso(ts);
    }
}
