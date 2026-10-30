package com.chat.web;

import com.chat.service.KnowledgeService;
import com.chat.util.Times;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Knowledge base upload, listing, deletion and keyword retrieval. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    public record CreateDocumentBody(String title, String content) {
    }

    public record DocumentItem(long id, String uuid, String title, int chunkCount, String createdAt) {
    }

    public record DocumentList(List<DocumentItem> documents) {
    }

    public record HitItem(String documentUuid, String documentTitle, int chunkIndex,
                          String content, int score) {
    }

    public record SearchResult(List<HitItem> hits) {
    }

    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/documents")
    public DocumentList list() {
        List<DocumentItem> items = knowledge.list(AuthContext.userId()).stream()
                .map(d -> new DocumentItem(d.id(), d.uuid(), d.title(), d.chunkCount(),
                        Times.iso(d.createdAt())))
                .toList();
        return new DocumentList(items);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentItem create(@RequestBody CreateDocumentBody body) {
        if (body.content() == null || body.content().isBlank()) {
            throw ApiException.invalid("Document content must not be empty");
        }
        try {
            KnowledgeService.DocumentView document = knowledge.create(
                    new KnowledgeService.CreateCommand(AuthContext.userId(), body.title(), body.content()));
            return new DocumentItem(document.id(), document.uuid(), document.title(),
                    document.chunkCount(), Times.iso(document.createdAt()));
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalid(ex.getMessage());
        }
    }

    @DeleteMapping("/documents/{uuid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String uuid) {
        if (!knowledge.delete(AuthContext.userId(), uuid)) {
            throw ApiException.notFound("Knowledge document not found");
        }
    }

    @GetMapping("/search")
    public SearchResult search(@RequestParam(name = "q", required = false) String query) {
        if (query == null || query.isBlank()) {
            throw ApiException.invalid("Query parameter q is required");
        }
        List<HitItem> hits = knowledge.search(AuthContext.userId(), query.trim()).stream()
                .map(h -> new HitItem(h.documentUuid(), h.documentTitle(), h.chunkIndex(),
                        h.content(), h.score()))
                .toList();
        return new SearchResult(hits);
    }
}
