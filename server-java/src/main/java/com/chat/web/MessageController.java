package com.chat.web;

import com.chat.repo.MessageRepository;
import com.chat.service.SessionService;
import com.chat.util.Times;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chat history, message edit/delete and session system prompts. History is a
 * merged list: the session prompts come first, then messages in insertion order
 * (the same contract the Go backend exposes to the Vue client).
 */
@RestController
@RequestMapping("/api")
public class MessageController {

    public record HistoryItem(String uuid, String dateTime, String text, String model,
                              boolean inversion, boolean error, boolean loading, boolean isPin,
                              boolean isPrompt, Object artifacts, Object suggestedQuestions) {
    }

    public record SimpleChatMessage(String uuid, String dateTime, String text, String model,
                                    Boolean inversion, Boolean error, Boolean loading, Boolean isPin,
                                    Boolean isPrompt, Object artifacts, Object suggestedQuestions) {
    }

    public record MessageResponse(long id, String uuid, String chatSessionUuid, String role,
                                  String content, String reasoningContent, String model,
                                  boolean isPin, int tokenCount, String createdAt, String updatedAt) {
    }

    public record CreatePromptBody(String uuid, String chatSessionUuid, String role, String content,
                                   Integer tokenCount, Double score) {
    }

    public record PromptResponse(String uuid, String chatSessionUuid, String role, String content,
                                 int tokenCount, String updatedAt) {
    }

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final MessageRepository messages;
    private final SessionService sessionService;
    private final ObjectMapper json;

    public MessageController(MessageRepository messages, SessionService sessionService,
                             ObjectMapper json) {
        this.messages = messages;
        this.sessionService = sessionService;
        this.json = json;
    }

    @GetMapping("/uuid/chat_messages/chat_sessions/{uuid}")
    public List<HistoryItem> history(@PathVariable String uuid,
                                     @RequestParam(value = "limit", required = false) Integer limit,
                                     @RequestParam(value = "offset", required = false) Integer offset) {
        sessionService.getOwned(uuid, AuthContext.userId());
        int size = clamp(limit == null ? DEFAULT_LIMIT : limit, 1, MAX_LIMIT);
        int start = Math.max(offset == null ? 0 : offset, 0);
        return messages.history(uuid, size, start).stream().map(this::toHistoryItem).toList();
    }

    @GetMapping("/uuid/chat_messages/{uuid}")
    public MessageResponse get(@PathVariable String uuid) {
        MessageRepository.ChatMessage message = messages.findByUuid(uuid, AuthContext.userId());
        if (message == null) {
            throw ApiException.notFound("Chat message not found");
        }
        return toMessageResponse(message);
    }

    @PutMapping("/uuid/chat_messages/{uuid}")
    public MessageResponse update(@PathVariable String uuid,
                                  @RequestBody SimpleChatMessage body) {
        long userId = AuthContext.userId();
        if (body.uuid() != null && !body.uuid().equals(uuid)) {
            throw ApiException.invalid("request uuid must match path uuid");
        }
        String content = body.text() == null ? "" : body.text();
        messages.updateMessage(uuid, userId, content, Boolean.TRUE.equals(body.isPin()),
                writeJson(body.artifacts()), writeJson(body.suggestedQuestions()));
        MessageRepository.ChatMessage updated = messages.findByUuid(uuid, userId);
        if (updated == null) {
            throw ApiException.notFound("Chat message not found");
        }
        return toMessageResponse(updated);
    }

    @DeleteMapping("/uuid/chat_messages/{uuid}")
    public ResponseEntity<Void> delete(@PathVariable String uuid) {
        if (messages.deleteMessage(uuid, AuthContext.userId()) == 0) {
            throw ApiException.notFound("Chat message not found");
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/uuid/chat_messages/chat_sessions/{uuid}")
    public ResponseEntity<Void> deleteHistory(@PathVariable String uuid) {
        long userId = AuthContext.userId();
        sessionService.getOwned(uuid, userId);
        messages.deleteSessionMessages(uuid, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/chat_prompts")
    public ResponseEntity<PromptResponse> createPrompt(@RequestBody CreatePromptBody body) {
        long userId = AuthContext.userId();
        if (body.chatSessionUuid() == null || body.chatSessionUuid().isBlank()) {
            throw ApiException.invalid("chatSessionUuid is required");
        }
        sessionService.getOwned(body.chatSessionUuid(), userId);
        String promptUuid = body.uuid() == null || body.uuid().isBlank()
                ? UUID.randomUUID().toString() : body.uuid();
        String role = body.role() == null || body.role().isBlank() ? "system" : body.role();
        if ("system".equals(role)) {
            // A session may only hold one active system prompt; return it instead of
            // hitting the unique index (same idempotent behaviour as the Go handler).
            MessageRepository.Prompt existing = messages.prompts(body.chatSessionUuid()).stream()
                    .filter(p -> "system".equals(p.role()))
                    .findFirst().orElse(null);
            if (existing != null) {
                return ResponseEntity.ok(toPromptResponse(existing));
            }
        }
        MessageRepository.Prompt prompt = messages.createPrompt(body.chatSessionUuid(), promptUuid,
                userId, role, body.content() == null ? "" : body.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(toPromptResponse(prompt));
    }

    @PutMapping("/uuid/chat_prompts/{uuid}")
    public PromptResponse updatePrompt(@PathVariable String uuid,
                                       @RequestBody SimpleChatMessage body) {
        long userId = AuthContext.userId();
        if (messages.updatePrompt(uuid, userId, body.text() == null ? "" : body.text()) == 0) {
            throw ApiException.notFound("Chat prompt not found");
        }
        return new PromptResponse(uuid, null, null, body.text(), 0, null);
    }

    @DeleteMapping("/uuid/chat_prompts/{uuid}")
    public ResponseEntity<Void> deletePrompt(@PathVariable String uuid) {
        if (messages.deletePrompt(uuid, AuthContext.userId()) == 0) {
            throw ApiException.notFound("Chat prompt not found");
        }
        return ResponseEntity.ok().build();
    }

    private HistoryItem toHistoryItem(MessageRepository.HistoryMessage hit) {
        return new HistoryItem(hit.uuid(), hit.dateTime(), hit.text(), hit.model(),
                hit.inversion(), false, false, hit.isPin(), hit.isPrompt(),
                readJson(hit.artifactsJson()), readJson(hit.suggestedQuestionsJson()));
    }

    private MessageResponse toMessageResponse(MessageRepository.ChatMessage m) {
        return new MessageResponse(m.id(), m.uuid(), m.sessionUuid(), m.role(), m.content(),
                m.reasoningContent(), m.model(), m.isPin(), m.tokenCount(),
                Times.iso(m.createdAt()), Times.iso(m.updatedAt()));
    }

    private PromptResponse toPromptResponse(MessageRepository.Prompt p) {
        return new PromptResponse(p.uuid(), p.sessionUuid(), p.role(), p.content(), p.tokenCount(),
                Times.iso(p.updatedAt()));
    }

    /** Converts an arbitrary JSON value from the client into jsonb text. */
    private String writeJson(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw ApiException.invalid("invalid artifacts or suggested questions");
        }
    }

    private Object readJson(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = json.readTree(value);
            return node;
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
