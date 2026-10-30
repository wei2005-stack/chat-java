package com.chat.web;

import com.chat.repo.SessionRepository;
import com.chat.repo.WorkspaceRepository;
import com.chat.service.SessionService;
import org.springframework.web.bind.annotation.*;

/**
 * Global (workspace-less) active chat session endpoints. The Vue client calls
 * these on startup to restore the last conversation it was showing.
 */
@RestController
@RequestMapping("/api/uuid/user_active_chat_session")
public class ActiveSessionController {

    public record ActiveSessionResponse(long id, long userId, String chatSessionUuid,
                                        Long workspaceId, String createdAt, String updatedAt) {
    }

    public record ActiveBody(String chatSessionUuid) {
    }

    private final WorkspaceRepository workspaces;
    private final SessionService sessionService;

    public ActiveSessionController(WorkspaceRepository workspaces, SessionService sessionService) {
        this.workspaces = workspaces;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ActiveSessionResponse get() {
        long userId = AuthContext.userId();
        WorkspaceRepository.GlobalActiveSession active = workspaces.findGlobalActive(userId);
        if (active == null) {
            throw ApiException.notFound("no active session for user " + userId);
        }
        return new ActiveSessionResponse(active.id(), active.userId(), active.sessionUuid(), null,
                active.createdAt(), active.updatedAt());
    }

    @PutMapping
    public ActiveSessionResponse set(@RequestBody ActiveBody body) {
        long userId = AuthContext.userId();
        if (body.chatSessionUuid() == null || body.chatSessionUuid().isBlank()) {
            throw ApiException.invalid("chatSessionUuid is required");
        }
        SessionRepository.ChatSession session = sessionService.getOwned(body.chatSessionUuid(), userId);
        workspaces.setActiveSession(userId, null, session.uuid());
        WorkspaceRepository.GlobalActiveSession active = workspaces.findGlobalActive(userId);
        return new ActiveSessionResponse(active.id(), active.userId(), active.sessionUuid(), null,
                active.createdAt(), active.updatedAt());
    }
}
