package com.chat.repo;

import com.chat.service.PasswordHasher;
import com.chat.util.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class MessageRepository {

    public record ChatMessage(long id, String uuid, String sessionUuid, String role, String content,
                              String reasoningContent, String model, boolean isPin,
                              int tokenCount, String artifactsJson, String suggestedQuestionsJson,
                              Timestamp createdAt, Timestamp updatedAt) {
    }

    public record Prompt(long id, String uuid, String sessionUuid, String role, String content,
                         int tokenCount, Timestamp updatedAt) {
    }

    /** One row of the merged conversation history returned to the UI. */
    public record HistoryMessage(String uuid, String dateTime, String text, String model,
                                 boolean inversion, boolean isPin, boolean isPrompt,
                                 String artifactsJson, String suggestedQuestionsJson) {
    }

    private static final RowMapper<ChatMessage> MESSAGE_MAPPER = (rs, i) -> new ChatMessage(
            rs.getLong("id"), rs.getString("uuid"), rs.getString("chat_session_uuid"),
            rs.getString("role"), rs.getString("content"), rs.getString("reasoning_content"),
            rs.getString("model"), rs.getBoolean("is_pin"), rs.getInt("token_count"),
            rs.getString("artifacts"), rs.getString("suggested_questions"),
            rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));

    private static final RowMapper<Prompt> PROMPT_MAPPER = (rs, i) -> new Prompt(
            rs.getLong("id"), rs.getString("uuid"), rs.getString("chat_session_uuid"),
            rs.getString("role"), rs.getString("content"), rs.getInt("token_count"),
            rs.getTimestamp("updated_at"));

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Prompt> prompts(String sessionUuid) {
        return jdbc.query("""
                SELECT * FROM chat_prompt
                WHERE chat_session_uuid = ? AND is_deleted = false
                ORDER BY id
                """, PROMPT_MAPPER, sessionUuid);
    }

    public boolean hasPrompt(String sessionUuid) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_prompt WHERE chat_session_uuid = ? AND is_deleted = false
                """, Long.class, sessionUuid);
        return count != null && count > 0;
    }

    public Prompt createPrompt(String sessionUuid, String uuid, long userId, String role,
                               String content) {
        return jdbc.queryForObject("""
                INSERT INTO chat_prompt (uuid, chat_session_uuid, role, content, score, user_id,
                                         created_by, updated_by, token_count)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)
                RETURNING *
                """, PROMPT_MAPPER,
                uuid, sessionUuid, role, content, userId, userId, userId,
                PasswordHasher.estimateTokens(content));
    }

    public int updatePrompt(String uuid, long userId, String content) {
        return jdbc.update("""
                UPDATE chat_prompt SET content = ?, token_count = ?, updated_at = now()
                WHERE uuid = ? AND user_id = ?
                """, content, PasswordHasher.estimateTokens(content), uuid, userId);
    }

    public int deletePrompt(String uuid, long userId) {
        return jdbc.update("UPDATE chat_prompt SET is_deleted = true, updated_at = now() WHERE uuid = ? AND user_id = ?",
                uuid, userId);
    }

    /** Merged history: system prompts first, then messages ordered by id. */
    public List<HistoryMessage> history(String sessionUuid, int limit, int offset) {
        List<HistoryMessage> result = new ArrayList<>();
        for (Prompt p : prompts(sessionUuid)) {
            result.add(new HistoryMessage(p.uuid(), Times.iso(p.updatedAt()), p.content(), "",
                    true, false, true, "[]", null));
        }
        List<ChatMessage> messages = jdbc.query("""
                SELECT cm.* FROM chat_message cm
                JOIN chat_session cs ON cm.chat_session_uuid = cs.uuid
                WHERE cm.is_deleted = false AND cs.active = true AND cs.uuid = ?
                ORDER BY cm.id
                OFFSET ? LIMIT ?
                """, MESSAGE_MAPPER, sessionUuid, offset, limit);
        for (ChatMessage m : messages) {
            String text = (m.reasoningContent() == null ? "" : m.reasoningContent()) + m.content();
            result.add(new HistoryMessage(m.uuid(), Times.iso(m.updatedAt()), text, m.model(),
                    "user".equals(m.role()), m.isPin(), false,
                    m.artifactsJson(), m.suggestedQuestionsJson()));
        }
        return result;
    }

    /** Latest N messages in chronological order, used as the LLM context window. */
    public List<ChatMessage> latestMessages(String sessionUuid, int limit) {
        List<ChatMessage> rows = jdbc.query("""
                SELECT * FROM chat_message
                WHERE chat_session_uuid = ? AND is_deleted = false
                ORDER BY id DESC
                LIMIT ?
                """, MESSAGE_MAPPER, sessionUuid, limit);
        List<ChatMessage> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    /**
     * Pinned messages plus the latest N messages older than {@code targetUuid},
     * mirrored after Go's GetLastNChatMessages query for regenerate requests.
     */
    public List<ChatMessage> messagesBefore(String sessionUuid, String targetUuid, int limit) {
        return jdbc.query("""
                SELECT * FROM chat_message
                WHERE id IN (
                    SELECT id FROM chat_message
                    WHERE chat_session_uuid = ? AND is_deleted = false AND is_pin = true
                    UNION
                    (
                        SELECT id FROM chat_message
                        WHERE chat_session_uuid = ?
                          AND id < (SELECT id FROM chat_message WHERE uuid = ?)
                          AND is_deleted = false
                        ORDER BY id DESC
                        LIMIT ?
                    )
                )
                ORDER BY id
                """, MESSAGE_MAPPER, sessionUuid, sessionUuid, targetUuid, limit);
    }

    public ChatMessage createMessage(String sessionUuid, String uuid, long userId, String role,
                                     String content, String reasoningContent, String model) {
        return jdbc.queryForObject("""
                INSERT INTO chat_message (uuid, chat_session_uuid, role, content, reasoning_content,
                                          model, llm_summary, score, user_id, created_by, updated_by,
                                          token_count, raw, artifacts, suggested_questions)
                VALUES (?, ?, ?, ?, ?, ?, '', 0, ?, ?, ?, ?, '{}', '[]', '[]')
                RETURNING *
                """, MESSAGE_MAPPER,
                uuid, sessionUuid, role, content, reasoningContent == null ? "" : reasoningContent,
                model, userId, userId, userId, PasswordHasher.estimateTokens(content));
    }

    public int updateMessage(String uuid, long userId, String content, boolean isPin,
                             String artifactsJson, String suggestedQuestionsJson) {
        return jdbc.update("""
                UPDATE chat_message
                SET content = ?, token_count = ?, is_pin = ?, artifacts = ?::jsonb,
                    suggested_questions = ?::jsonb, updated_at = now()
                WHERE uuid = ? AND user_id = ? AND is_deleted = false
                """, content, PasswordHasher.estimateTokens(content), isPin,
                artifactsJson, suggestedQuestionsJson, uuid, userId);
    }

    /** Regenerate replaces the original assistant text without touching pin/artifacts. */
    public int updateContent(String uuid, long userId, String content) {
        return jdbc.update("""
                UPDATE chat_message SET content = ?, token_count = ?, updated_at = now()
                WHERE uuid = ? AND user_id = ? AND is_deleted = false
                """, content, PasswordHasher.estimateTokens(content), uuid, userId);
    }

    public int deleteMessage(String uuid, long userId) {
        return jdbc.update(
                "UPDATE chat_message SET is_deleted = true, updated_at = now() WHERE uuid = ? AND user_id = ?",
                uuid, userId);
    }

    public int deleteSessionMessages(String sessionUuid, long userId) {
        return jdbc.update("""
                UPDATE chat_message SET is_deleted = true, updated_at = now()
                WHERE chat_session_uuid = ? AND user_id = ? AND is_deleted = false
                """, sessionUuid, userId);
    }

    public ChatMessage findByUuid(String uuid, long userId) {
        List<ChatMessage> rows = jdbc.query(
                "SELECT * FROM chat_message WHERE uuid = ? AND user_id = ? AND is_deleted = false LIMIT 1",
                MESSAGE_MAPPER, uuid, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
