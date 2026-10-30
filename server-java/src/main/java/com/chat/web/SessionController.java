package com.chat.web;

import com.chat.repo.SessionRepository;
import com.chat.repo.WorkspaceRepository;
import com.chat.service.SessionService;
import com.chat.util.Times;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chat session endpoints. The Vue store reads sessions through the workspace
 * endpoints and uses these routes for create/update/rename/delete.
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    public record SimpleChatSession(String uuid, boolean isEdit, String title, int maxLength,
                                    double temperature, double topP, int n, int maxTokens,
                                    String model, boolean summarizeMode, boolean artifactEnabled,
                                    String workspaceUuid) {
    }

    public record ChatSessionResponse(long id, long userId, String uuid, String topic, boolean active,
                                      String model, int maxLength, double temperature, double topP,
                                      int maxTokens, int n, boolean summarizeMode, Long workspaceId,
                                      boolean artifactEnabled, boolean exploreMode,
                                      String createdAt, String updatedAt) {
    }

    public record GetChatSessionResponse(String uuid, String topic, int maxLength,
                                         String createdAt, String updatedAt, boolean artifactEnabled) {
    }

    public record CreateChatSessionBody(String uuid, String topic, String model,
                                        String defaultSystemPrompt) {
    }

    public record UpdateChatSessionBody(String uuid, String topic, Integer maxLength,
                                        Double temperature, String model, Double topP, Integer n,
                                        Integer maxTokens, Boolean summarizeMode,
                                        Boolean artifactEnabled, Boolean exploreMode,
                                        String workspaceUuid) {
    }

    public record TopicBody(String topic) {
    }

    public record MaxLengthBody(Integer maxLength) {
    }

    private final SessionRepository sessions;
    private final WorkspaceRepository workspaces;
    private final SessionService service;

    public SessionController(SessionRepository sessions, WorkspaceRepository workspaces,
                             SessionService service) {
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.service = service;
    }

    @GetMapping("/chat_sessions/user")
    public List<SimpleChatSession> listByUser() {
        long userId = AuthContext.userId();
        Map<Long, String> workspaceUuids = workspaces.listByUser(userId).stream()
                .collect(Collectors.toMap(WorkspaceRepository.Workspace::id,
                        WorkspaceRepository.Workspace::uuid));
        return sessions.listByUser(userId).stream()
                .map(s -> new SimpleChatSession(s.uuid(), false, s.topic(), s.maxLength(),
                        s.temperature(), s.topP(), s.n(), s.maxTokens(), s.model(),
                        s.summarizeMode(), s.artifactEnabled(),
                        s.workspaceId() == null ? null : workspaceUuids.get(s.workspaceId())))
                .toList();
    }

    @GetMapping("/uuid/chat_sessions/{uuid}")
    public GetChatSessionResponse get(@PathVariable String uuid) {
        SessionRepository.ChatSession session = service.getOwned(uuid, AuthContext.userId());
        return new GetChatSessionResponse(session.uuid(), session.topic(), session.maxLength(),
                Times.iso(session.createdAt()), Times.iso(session.updatedAt()),
                session.artifactEnabled());
    }

    @PostMapping("/uuid/chat_sessions")
    public ResponseEntity<ChatSessionResponse> create(@RequestBody CreateChatSessionBody body) {
        long userId = AuthContext.userId();
        if (body.uuid() == null || body.uuid().isBlank()) {
            throw ApiException.invalid("uuid is required");
        }
        SessionRepository.ChatSession session = service.save(new SessionService.SaveSessionCommand(
                userId, body.uuid(), null, body.topic(), body.model(),
                SessionService.DEFAULT_MAX_LENGTH, SessionService.DEFAULT_TEMPERATURE,
                SessionService.DEFAULT_TOP_P, SessionService.DEFAULT_MAX_TOKENS,
                SessionService.DEFAULT_N, false, false, false,
                true, body.defaultSystemPrompt(), true));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
    }

    @PutMapping("/uuid/chat_sessions/{uuid}")
    public ChatSessionResponse createOrUpdate(@PathVariable String uuid,
                                              @RequestBody UpdateChatSessionBody body) {
        long userId = AuthContext.userId();
        if (body.uuid() != null && !body.uuid().equals(uuid)) {
            throw ApiException.invalid("request uuid must match path uuid");
        }
        SessionRepository.ChatSession session = service.save(new SessionService.SaveSessionCommand(
                userId, uuid, body.workspaceUuid(), body.topic(), body.model(),
                body.maxLength() == null ? 0 : body.maxLength(),
                body.temperature() == null ? 0 : body.temperature(),
                body.topP() == null ? SessionService.DEFAULT_TOP_P : body.topP(),
                body.maxTokens() == null ? SessionService.DEFAULT_MAX_TOKENS : body.maxTokens(),
                body.n() == null ? SessionService.DEFAULT_N : body.n(),
                Boolean.TRUE.equals(body.summarizeMode()), Boolean.TRUE.equals(body.exploreMode()),
                Boolean.TRUE.equals(body.artifactEnabled()), false, null, false));
        return toResponse(session);
    }

    @DeleteMapping("/uuid/chat_sessions/{uuid}")
    public ResponseEntity<Void> delete(@PathVariable String uuid) {
        service.delete(uuid, AuthContext.userId());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/uuid/chat_sessions/topic/{uuid}")
    public ChatSessionResponse updateTopic(@PathVariable String uuid, @RequestBody TopicBody body) {
        return toResponse(service.updateTopic(uuid, AuthContext.userId(), body.topic()));
    }

    @PutMapping("/uuid/chat_sessions/max_length/{uuid}")
    public ChatSessionResponse updateMaxLength(@PathVariable String uuid,
                                               @RequestBody MaxLengthBody body) {
        int maxLength = body.maxLength() == null ? SessionService.DEFAULT_MAX_LENGTH : body.maxLength();
        return toResponse(service.updateMaxLength(uuid, AuthContext.userId(), maxLength));
    }

    static ChatSessionResponse toResponse(SessionRepository.ChatSession s) {
        return new ChatSessionResponse(s.id(), s.userId(), s.uuid(), s.topic(), s.active(),
                s.model(), s.maxLength(), s.temperature(), s.topP(), s.maxTokens(), s.n(),
                s.summarizeMode(), s.workspaceId(), s.artifactEnabled(), s.exploreMode(),
                Times.iso(s.createdAt()), Times.iso(s.updatedAt()));
    }
}
