package com.chat.web;

import com.chat.service.ChatService;
import com.chat.service.ProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE endpoint used by the Vue chat view. Events follow the same protocol as
 * the Go backend: typed {@code event:} lines with a JSON body, terminated by a
 * persisted {@code completed} event or a {@code failed} event.
 */
@RestController
@RequestMapping("/api")
public class ChatStreamController {

    public record ChatRequest(String prompt, String sessionUuid, String chatUuid,
                              boolean regenerate, Boolean stream) {
    }

    private final ChatService chatService;
    private final ObjectMapper json;

    public ChatStreamController(ChatService chatService, ObjectMapper json) {
        this.chatService = chatService;
        this.json = json;
    }

    @PostMapping("/chat_stream")
    public void stream(@RequestBody ChatRequest request, HttpServletResponse response) throws IOException {
        if (request.sessionUuid() == null || request.sessionUuid().isBlank()
                || request.chatUuid() == null || request.chatUuid().isBlank()) {
            throw ApiException.invalid("sessionUuid and chatUuid are required");
        }
        if (!request.regenerate() && (request.prompt() == null || request.prompt().isBlank())) {
            throw ApiException.invalid("prompt is required");
        }
        long userId = AuthContext.userId();

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        response.flushBuffer();

        String answerId = request.regenerate() ? request.chatUuid() : UUID.randomUUID().toString();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        ProviderClient.DeltaSink sink = text -> {
            try {
                emit(response, "delta", Map.of(
                        "type", "delta",
                        "answerId", answerId,
                        "delta", text,
                        "persisted", false));
            } catch (IOException ex) {
                clientGone.set(true);
                throw ex;
            }
        };

        try {
            if (request.regenerate()) {
                chatService.regenerate(userId, request.sessionUuid(), request.chatUuid(), sink);
            } else {
                chatService.complete(userId, request.sessionUuid(), request.chatUuid(), answerId,
                        request.prompt(), sink);
            }
            if (!clientGone.get()) {
                emit(response, "completed", Map.of(
                        "type", "completed",
                        "answerId", answerId,
                        "persisted", true));
            }
        } catch (ApiException ex) {
            emitFailure(response, clientGone, request.chatUuid(), ex.getCode(), ex.getMessage());
        } catch (RuntimeException ex) {
            emitFailure(response, clientGone, answerId, "generation_failed",
                    ex.getMessage() == null ? "Failed to generate answer" : ex.getMessage());
        }
    }

    private void emitFailure(HttpServletResponse response, AtomicBoolean clientGone, String answerId,
                             String code, String message) throws IOException {
        if (clientGone.get()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "failed");
        data.put("answerId", answerId);
        data.put("persisted", false);
        data.put("code", code);
        data.put("message", message);
        emit(response, "failed", data);
    }

    private void emit(HttpServletResponse response, String type, Map<String, Object> data)
            throws IOException {
        String payload = json.writeValueAsString(data);
        response.getWriter().write("event: " + type + "\ndata: " + payload + "\n\n");
        response.getWriter().flush();
    }
}
