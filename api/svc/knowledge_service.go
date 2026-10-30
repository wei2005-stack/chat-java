package svc

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/swuecho/chat_backend/pkg/util"
	"github.com/swuecho/chat_backend/sqlc_queries"
)

const (
	knowledgeChunkSize    = 500
	knowledgeChunkOverlap = 100
	knowledgeTopK         = 5
	knowledgeMinScore     = 2
)

// KnowledgeDocument is the application view of an uploaded knowledge base text.
type KnowledgeDocument struct {
	ID         int32
	UUID       string
	Title      string
	ChunkCount int32
	CreatedAt  time.Time
}

// CreateKnowledgeDocumentCommand is the input for indexing one text document.
type CreateKnowledgeDocumentCommand struct {
	UserID  int32
	Title   string
	Content string
}

// KnowledgeSearchHit is one retrieved chunk with its relevance score.
type KnowledgeSearchHit struct {
	DocumentUUID  string
	DocumentTitle string
	ChunkIndex    int32
	Content       string
	Score         int
}

// KnowledgeService indexes user documents and retrieves chunks for prompting.
// Retrieval is keyword based (CJK bigrams plus ASCII words); it is intentionally
// simple enough to stay dependency free while still being replaceable later.
type KnowledgeService struct {
	q     *sqlc_queries.Queries
	newID func() string
}

func NewKnowledgeService(q *sqlc_queries.Queries) *KnowledgeService {
	return &KnowledgeService{q: q, newID: util.NewUUID}
}

// CreateDocument splits the text into overlapping chunks and stores them.
func (s *KnowledgeService) CreateDocument(ctx context.Context, command CreateKnowledgeDocumentCommand) (KnowledgeDocument, error) {
	title := strings.TrimSpace(command.Title)
	content := strings.TrimSpace(command.Content)
	if title == "" {
		title = "未命名文档"
	}
	if len([]rune(content)) < 20 {
		return KnowledgeDocument{}, fmt.Errorf("document content is too short to index")
	}

	chunks := splitKnowledgeText(content)
	if len(chunks) == 0 {
		return KnowledgeDocument{}, fmt.Errorf("document content produced no chunks")
	}

	documentUUID := s.newID()
	row, err := s.q.CreateKnowledgeDocument(ctx, sqlc_queries.CreateKnowledgeDocumentParams{
		Uuid:       documentUUID,
		UserID:     command.UserID,
		Title:      title,
		Content:    content,
		ChunkCount: int32(len(chunks)),
	})
	if err != nil {
		return KnowledgeDocument{}, err
	}

	for index, chunk := range chunks {
		if err := s.q.InsertKnowledgeChunk(ctx, sqlc_queries.InsertKnowledgeChunkParams{
			DocumentUuid: documentUUID,
			UserID:       command.UserID,
			ChunkIndex:   int32(index),
			Content:      chunk,
		}); err != nil {
			// Best effort cleanup so a half indexed document is not left behind.
			_, _ = s.q.DeleteKnowledgeDocument(ctx, sqlc_queries.DeleteKnowledgeDocumentParams{
				Uuid: documentUUID, UserID: command.UserID,
			})
			return KnowledgeDocument{}, err
		}
	}

	return KnowledgeDocument{
		ID: row.ID, UUID: row.Uuid, Title: row.Title,
		ChunkCount: row.ChunkCount, CreatedAt: row.CreatedAt,
	}, nil
}

// ListDocuments returns the user's documents in reverse chronological order.
func (s *KnowledgeService) ListDocuments(ctx context.Context, userID int32) ([]KnowledgeDocument, error) {
	rows, err := s.q.ListKnowledgeDocuments(ctx, userID)
	if err != nil {
		return nil, err
	}
	documents := make([]KnowledgeDocument, 0, len(rows))
	for _, row := range rows {
		documents = append(documents, KnowledgeDocument{
			ID: row.ID, UUID: row.Uuid, Title: row.Title,
			ChunkCount: row.ChunkCount, CreatedAt: row.CreatedAt,
		})
	}
	return documents, nil
}

// DeleteDocument removes one document and its chunks. It reports whether a row
// belonging to the user was actually deleted.
func (s *KnowledgeService) DeleteDocument(ctx context.Context, userID int32, documentUUID string) (bool, error) {
	deleted, err := s.q.DeleteKnowledgeDocument(ctx, sqlc_queries.DeleteKnowledgeDocumentParams{
		Uuid: documentUUID, UserID: userID,
	})
	if err != nil {
		return false, err
	}
	return deleted > 0, nil
}

// Search ranks the user's chunks against the query and returns the best matches.
func (s *KnowledgeService) Search(ctx context.Context, userID int32, query string) ([]KnowledgeSearchHit, error) {
	keywords := extractKeywords(query)
	if len(keywords) == 0 {
		return nil, nil
	}
	rows, err := s.q.ListKnowledgeChunksByUser(ctx, userID)
	if err != nil {
		return nil, err
	}

	hits := make([]KnowledgeSearchHit, 0, len(rows))
	for _, row := range rows {
		score := scoreKnowledgeChunk(row.Content, keywords)
		if score < knowledgeMinScore {
			continue
		}
		hits = append(hits, KnowledgeSearchHit{
			DocumentUUID: row.DocumentUuid, DocumentTitle: row.Title,
			ChunkIndex: row.ChunkIndex, Content: row.Content, Score: score,
		})
	}

	sort.SliceStable(hits, func(i, j int) bool { return hits[i].Score > hits[j].Score })
	if len(hits) > knowledgeTopK {
		hits = hits[:knowledgeTopK]
	}
	return hits, nil
}

// BuildContext returns the system message block injected before a chat request.
// The second return value is false when the user has no documents at all, in
// which case normal chatting should continue untouched.
func (s *KnowledgeService) BuildContext(ctx context.Context, userID int32, query string) (string, bool, error) {
	documents, err := s.q.ListKnowledgeDocuments(ctx, userID)
	if err != nil {
		return "", false, err
	}
	if len(documents) == 0 {
		return "", false, nil
	}

	hits, err := s.Search(ctx, userID, query)
	if err != nil {
		return "", false, err
	}
	if len(hits) == 0 {
		return "【知识库检索结果】本次检索未在用户的知识库中找到与该问题直接相关的内容。" +
			"如果问题涉及用户上传的资料，请先明确说明“知识库中未找到相关内容”，再谨慎给出一般性回答，禁止编造资料中不存在的细节。", true, nil
	}

	var builder strings.Builder
	builder.WriteString("【知识库检索结果】以下片段来自用户上传的知识库，与当前问题相关。\n")
	builder.WriteString("回答要求：优先依据下列片段作答；引用片段时在句末标注 [1]、[2] 这样的序号；")
	builder.WriteString("并在回答最后单独列出“参考资料：”，格式为“1. 文档名（第N段）”。")
	builder.WriteString("若片段不足以回答问题，请明确说明资料中没有相关记录，不要编造。\n\n")
	for index, hit := range hits {
		fmt.Fprintf(&builder, "[%d] 文档《%s》第%d段：\n%s\n\n", index+1, hit.DocumentTitle, hit.ChunkIndex+1, hit.Content)
	}
	return builder.String(), true, nil
}

// splitKnowledgeText cuts text into rune windows with overlap so no sentence is
// lost at a chunk boundary. Rune based slicing keeps CJK text intact.
func splitKnowledgeText(text string) []string {
	runes := []rune(strings.TrimSpace(text))
	if len(runes) == 0 {
		return nil
	}

	chunks := make([]string, 0, len(runes)/knowledgeChunkSize+1)
	start := 0
	for start < len(runes) {
		end := start + knowledgeChunkSize
		if end > len(runes) {
			end = len(runes)
		}
		chunk := strings.TrimSpace(string(runes[start:end]))
		if len([]rune(chunk)) >= 20 {
			chunks = append(chunks, chunk)
		}
		if end == len(runes) {
			break
		}
		start = end - knowledgeChunkOverlap
	}
	return chunks
}

var asciiKeywordPattern = regexp.MustCompile(`[a-z0-9]{2,}`)

// extractKeywords turns a query into CJK bigrams plus ASCII words. Bigrams are
// used because Chinese has no spaces, so character pairs are a cheap proxy for
// terms without pulling in a tokenizer or an embedding service.
func extractKeywords(query string) []string {
	lowered := strings.ToLower(query)
	seen := make(map[string]struct{})
	keywords := make([]string, 0, 64)

	for _, word := range asciiKeywordPattern.FindAllString(lowered, -1) {
		if _, ok := seen[word]; ok {
			continue
		}
		seen[word] = struct{}{}
		keywords = append(keywords, word)
	}

	runes := []rune(lowered)
	for i := 0; i+1 < len(runes); i++ {
		if !unicode.Is(unicode.Han, runes[i]) || !unicode.Is(unicode.Han, runes[i+1]) {
			continue
		}
		bigram := string(runes[i : i+2])
		if _, ok := seen[bigram]; ok {
			continue
		}
		seen[bigram] = struct{}{}
		keywords = append(keywords, bigram)
	}
	return keywords
}

func scoreKnowledgeChunk(content string, keywords []string) int {
	lowered := strings.ToLower(content)
	score := 0
	for _, keyword := range keywords {
		count := strings.Count(lowered, keyword)
		if count > 3 {
			count = 3
		}
		score += count
	}
	return score
}
