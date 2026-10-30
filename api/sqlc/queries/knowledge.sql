-- name: CreateKnowledgeDocument :one
INSERT INTO knowledge_document (uuid, user_id, title, content, chunk_count)
VALUES ($1, $2, $3, $4, $5)
RETURNING *;

-- name: ListKnowledgeDocuments :many
SELECT id, uuid, title, chunk_count, created_at
FROM knowledge_document
WHERE user_id = $1
ORDER BY created_at DESC;

-- name: DeleteKnowledgeDocument :execrows
DELETE FROM knowledge_document
WHERE uuid = $1 AND user_id = $2;

-- name: InsertKnowledgeChunk :exec
INSERT INTO knowledge_chunk (document_uuid, user_id, chunk_index, content)
VALUES ($1, $2, $3, $4);

-- name: ListKnowledgeChunksByUser :many
SELECT c.id, c.document_uuid, c.chunk_index, c.content, d.title
FROM knowledge_chunk c
JOIN knowledge_document d ON d.uuid = c.document_uuid
WHERE c.user_id = $1
ORDER BY c.document_uuid, c.chunk_index;
