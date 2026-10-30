package com.chat.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class KnowledgeRepository {

    public record Document(long id, String uuid, String title, int chunkCount, Timestamp createdAt) {
    }

    public record Chunk(String documentUuid, String documentTitle, int chunkIndex, String content) {
    }

    private static final RowMapper<Document> DOCUMENT_MAPPER = (rs, i) -> new Document(
            rs.getLong("id"), rs.getString("uuid"), rs.getString("title"),
            rs.getInt("chunk_count"), rs.getTimestamp("created_at"));

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Document createDocument(String uuid, long userId, String title, String content, List<String> chunks) {
        Document document = jdbc.queryForObject("""
                INSERT INTO knowledge_document (uuid, user_id, title, content, chunk_count)
                VALUES (?, ?, ?, ?, ?)
                RETURNING *
                """, DOCUMENT_MAPPER, uuid, userId, title, content, chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            jdbc.update("""
                    INSERT INTO knowledge_chunk (document_uuid, user_id, chunk_index, content)
                    VALUES (?, ?, ?, ?)
                    """, uuid, userId, index, chunks.get(index));
        }
        return document;
    }

    public List<Document> listDocuments(long userId) {
        return jdbc.query("""
                SELECT id, uuid, title, chunk_count, created_at
                FROM knowledge_document
                WHERE user_id = ?
                ORDER BY created_at DESC
                """, DOCUMENT_MAPPER, userId);
    }

    public int deleteDocument(String uuid, long userId) {
        return jdbc.update("DELETE FROM knowledge_document WHERE uuid = ? AND user_id = ?", uuid, userId);
    }

    public List<Chunk> listChunks(long userId) {
        return jdbc.query("""
                SELECT c.document_uuid, d.title, c.chunk_index, c.content
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.uuid = c.document_uuid
                WHERE c.user_id = ?
                ORDER BY c.document_uuid, c.chunk_index
                """, (rs, i) -> new Chunk(
                rs.getString("document_uuid"), rs.getString("title"),
                rs.getInt("chunk_index"), rs.getString("content")), userId);
    }
}
