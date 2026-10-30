package handler

import (
	"net/http"
	"strings"
	"time"

	"github.com/gorilla/mux"
	"github.com/swuecho/chat_backend/apicontract"
	"github.com/swuecho/chat_backend/dto"
	"github.com/swuecho/chat_backend/svc"
)

// KnowledgeHandler exposes the user's knowledge base documents and retrieval
// used by the chat prompt. Chunks are created automatically on upload.
type KnowledgeHandler struct {
	service *svc.KnowledgeService
}

func NewKnowledgeHandler(service *svc.KnowledgeService) *KnowledgeHandler {
	return &KnowledgeHandler{service: service}
}

type createKnowledgeDocumentRequest struct {
	Title   string `json:"title"`
	Content string `json:"content"`
}

type knowledgeDocumentHTTPResponse struct {
	ID         int32     `json:"id"`
	UUID       string    `json:"uuid"`
	Title      string    `json:"title"`
	ChunkCount int32     `json:"chunkCount"`
	CreatedAt  time.Time `json:"createdAt"`
}

type knowledgeDocumentListHTTPResponse struct {
	Documents []knowledgeDocumentHTTPResponse `json:"documents"`
}

type knowledgeSearchHitHTTPResponse struct {
	DocumentUUID  string `json:"documentUuid"`
	DocumentTitle string `json:"documentTitle"`
	ChunkIndex    int32  `json:"chunkIndex"`
	Content       string `json:"content"`
	Score         int    `json:"score"`
}

type knowledgeSearchHTTPResponse struct {
	Hits []knowledgeSearchHitHTTPResponse `json:"hits"`
}

func (h *KnowledgeHandler) Register(router *mux.Router, registry *apicontract.Registry) {
	apicontract.RegisterJSON[apicontract.NoBody, knowledgeDocumentListHTTPResponse](router, registry, apicontract.Operation{
		Method: http.MethodGet, Path: "/knowledge/documents", OperationID: "listKnowledgeDocuments",
		Summary: "List the current user's knowledge base documents", Tags: []string{"knowledge"},
		SuccessStatus: http.StatusOK, Security: apicontract.BearerAuth(),
	}, h.listDocuments)

	apicontract.RegisterJSON[createKnowledgeDocumentRequest, knowledgeDocumentHTTPResponse](router, registry, apicontract.Operation{
		Method: http.MethodPost, Path: "/knowledge/documents", OperationID: "createKnowledgeDocument",
		Summary: "Upload a plain text document and index it into chunks", Tags: []string{"knowledge"},
		SuccessStatus: http.StatusCreated, Security: apicontract.BearerAuth(),
	}, h.createDocument)

	apicontract.RegisterJSON[apicontract.NoBody, apicontract.NoBody](router, registry, apicontract.Operation{
		Method: http.MethodDelete, Path: "/knowledge/documents/{uuid}", OperationID: "deleteKnowledgeDocument",
		Summary: "Delete a knowledge base document and its chunks", Tags: []string{"knowledge"},
		SuccessStatus: http.StatusNoContent, Security: apicontract.BearerAuth(),
		Parameters: []apicontract.Parameter{apicontract.UUIDPathParameter("uuid")},
	}, h.deleteDocument)

	apicontract.RegisterJSON[apicontract.NoBody, knowledgeSearchHTTPResponse](router, registry, apicontract.Operation{
		Method: http.MethodGet, Path: "/knowledge/search", OperationID: "searchKnowledge",
		Summary: "Search the knowledge base for chunks relevant to a query", Tags: []string{"knowledge"},
		SuccessStatus: http.StatusOK, Security: apicontract.BearerAuth(),
		Parameters: []apicontract.Parameter{{Name: "q", In: "query", Required: true,
			Schema: map[string]any{"type": "string"}}},
	}, h.search)
}

func (h *KnowledgeHandler) listDocuments(r *http.Request, _ apicontract.NoBody) (knowledgeDocumentListHTTPResponse, error) {
	userID, err := authenticatedUserID(r)
	if err != nil {
		return knowledgeDocumentListHTTPResponse{}, err
	}
	documents, err := h.service.ListDocuments(r.Context(), userID)
	if err != nil {
		return knowledgeDocumentListHTTPResponse{}, dto.WrapError(dto.MapDatabaseError(err), "Failed to list knowledge documents")
	}
	items := make([]knowledgeDocumentHTTPResponse, 0, len(documents))
	for _, document := range documents {
		items = append(items, knowledgeDocumentResponse(document))
	}
	return knowledgeDocumentListHTTPResponse{Documents: items}, nil
}

func (h *KnowledgeHandler) createDocument(r *http.Request, request createKnowledgeDocumentRequest) (knowledgeDocumentHTTPResponse, error) {
	userID, err := authenticatedUserID(r)
	if err != nil {
		return knowledgeDocumentHTTPResponse{}, err
	}
	if strings.TrimSpace(request.Content) == "" {
		return knowledgeDocumentHTTPResponse{}, dto.ErrValidationInvalidInput("Document content must not be empty")
	}
	document, err := h.service.CreateDocument(r.Context(), svc.CreateKnowledgeDocumentCommand{
		UserID: userID, Title: request.Title, Content: request.Content,
	})
	if err != nil {
		return knowledgeDocumentHTTPResponse{}, dto.WrapError(err, "Failed to index knowledge document")
	}
	return knowledgeDocumentResponse(document), nil
}

func (h *KnowledgeHandler) deleteDocument(r *http.Request, _ apicontract.NoBody) (apicontract.NoBody, error) {
	userID, err := authenticatedUserID(r)
	if err != nil {
		return apicontract.NoBody{}, err
	}
	deleted, err := h.service.DeleteDocument(r.Context(), userID, mux.Vars(r)["uuid"])
	if err != nil {
		return apicontract.NoBody{}, dto.WrapError(dto.MapDatabaseError(err), "Failed to delete knowledge document")
	}
	if !deleted {
		return apicontract.NoBody{}, dto.ErrResourceNotFound("Knowledge document")
	}
	return apicontract.NoBody{}, nil
}

func (h *KnowledgeHandler) search(r *http.Request, _ apicontract.NoBody) (knowledgeSearchHTTPResponse, error) {
	userID, err := authenticatedUserID(r)
	if err != nil {
		return knowledgeSearchHTTPResponse{}, err
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if query == "" {
		return knowledgeSearchHTTPResponse{}, dto.ErrValidationInvalidInput("Query parameter q is required")
	}
	hits, err := h.service.Search(r.Context(), userID, query)
	if err != nil {
		return knowledgeSearchHTTPResponse{}, dto.WrapError(dto.MapDatabaseError(err), "Failed to search knowledge base")
	}
	items := make([]knowledgeSearchHitHTTPResponse, 0, len(hits))
	for _, hit := range hits {
		items = append(items, knowledgeSearchHitHTTPResponse{
			DocumentUUID: hit.DocumentUUID, DocumentTitle: hit.DocumentTitle,
			ChunkIndex: hit.ChunkIndex, Content: hit.Content, Score: hit.Score,
		})
	}
	return knowledgeSearchHTTPResponse{Hits: items}, nil
}

func knowledgeDocumentResponse(document svc.KnowledgeDocument) knowledgeDocumentHTTPResponse {
	return knowledgeDocumentHTTPResponse{
		ID: document.ID, UUID: document.UUID, Title: document.Title,
		ChunkCount: document.ChunkCount, CreatedAt: document.CreatedAt,
	}
}
