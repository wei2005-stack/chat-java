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

/**
 * Workspace endpoints used by the Vue sidebar: workspace CRUD, ordering,
 * default selection, workspace sessions and active-session restoration.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    public record WorkspaceResponse(String uuid, String name, String description, String color,
                                    String icon, boolean isDefault, int orderPosition,
                                    long sessionCount, String createdAt, String updatedAt) {
    }

    public record CreateWorkspaceBody(String name, String description, String color, String icon,
                                      Boolean isDefault) {
    }

    public record UpdateWorkspaceBody(String name, String description, String color, String icon) {
    }

    public record OrderBody(Integer orderPosition) {
    }

    public record CreateSessionBody(String topic, String model, String defaultSystemPrompt) {
    }

    public record MigrationResponse(boolean hasLegacySessions, int migratedSessions,
                                    WorkspaceResponse defaultWorkspace) {
    }

    public record WorkspaceSessionCreated(String uuid, String topic, String model,
                                          boolean artifactEnabled, String workspaceUuid,
                                          String createdAt) {
    }

    public record WorkspaceSessionItem(String uuid, String title, boolean isEdit, String model,
                                       String workspaceUuid, int maxLength, double temperature,
                                       int maxTokens, double topP, int n, boolean summarizeMode,
                                       boolean exploreMode, boolean artifactEnabled,
                                       String createdAt, String updatedAt) {
    }

    public record ActiveSessionItem(String workspaceUuid, String chatSessionUuid, String updatedAt) {
    }

    public record ActiveBody(String chatSessionUuid) {
    }

    private final WorkspaceRepository workspaces;
    private final SessionService sessionService;

    public WorkspaceController(WorkspaceRepository workspaces, SessionService sessionService) {
        this.workspaces = workspaces;
        this.sessionService = sessionService;
    }

    @GetMapping
    public List<WorkspaceResponse> list() {
        long userId = AuthContext.userId();
        return workspaces.listByUser(userId).stream().map(WorkspaceController::toResponse).toList();
    }

    @PostMapping
    public ResponseEntity<WorkspaceResponse> create(@RequestBody CreateWorkspaceBody body) {
        long userId = AuthContext.userId();
        if (body.name() == null || body.name().isBlank()) {
            throw ApiException.invalid("workspace name is required");
        }
        String color = body.color() == null || body.color().isBlank() ? "#6366f1" : body.color();
        String icon = body.icon() == null || body.icon().isBlank() ? "folder" : body.icon();
        WorkspaceRepository.Workspace created = workspaces.create(userId, body.name().trim(),
                body.description() == null ? "" : body.description(), color, icon, false, 0);
        if (Boolean.TRUE.equals(body.isDefault())) {
            workspaces.setDefault(created.uuid(), userId);
            created = workspaces.findByUuid(created.uuid());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping("/{uuid}")
    public WorkspaceResponse get(@PathVariable String uuid) {
        return toResponse(ownedWorkspace(uuid, AuthContext.userId()));
    }

    @PutMapping("/{uuid}")
    public WorkspaceResponse update(@PathVariable String uuid, @RequestBody UpdateWorkspaceBody body) {
        long userId = AuthContext.userId();
        ownedWorkspace(uuid, userId);
        if (body.name() == null || body.name().isBlank()) {
            throw ApiException.invalid("workspace name is required");
        }
        WorkspaceRepository.Workspace updated = workspaces.update(uuid, userId, body.name().trim(),
                body.description() == null ? "" : body.description(),
                body.color() == null || body.color().isBlank() ? "#6366f1" : body.color(),
                body.icon() == null || body.icon().isBlank() ? "folder" : body.icon());
        if (updated == null) {
            throw ApiException.notFound("Workspace not found with UUID: " + uuid);
        }
        return toResponse(updated);
    }

    @DeleteMapping("/{uuid}")
    public Map<String, String> delete(@PathVariable String uuid) {
        long userId = AuthContext.userId();
        if (workspaces.delete(uuid, userId) == 0) {
            throw ApiException.notFound("Workspace not found with UUID: " + uuid);
        }
        return Map.of("message", "Workspace deleted successfully");
    }

    @PutMapping("/{uuid}/reorder")
    public WorkspaceResponse reorder(@PathVariable String uuid, @RequestBody OrderBody body) {
        long userId = AuthContext.userId();
        ownedWorkspace(uuid, userId);
        int position = body.orderPosition() == null ? 0 : body.orderPosition();
        if (position < 0) {
            throw ApiException.invalid("orderPosition must not be negative");
        }
        workspaces.updateOrder(uuid, userId, position);
        return toResponse(workspaces.findByUuid(uuid));
    }

    @PutMapping("/{uuid}/set-default")
    public WorkspaceResponse setDefault(@PathVariable String uuid) {
        long userId = AuthContext.userId();
        ownedWorkspace(uuid, userId);
        workspaces.setDefault(uuid, userId);
        return toResponse(workspaces.findByUuid(uuid));
    }

    @PostMapping("/default")
    public WorkspaceResponse ensureDefault() {
        return toResponse(workspaces.ensureDefault(AuthContext.userId()));
    }

    @PostMapping("/auto-migrate")
    public MigrationResponse autoMigrate() {
        long userId = AuthContext.userId();
        long legacy = workspaces.countLegacySessions(userId);
        if (legacy == 0) {
            return new MigrationResponse(false, 0, null);
        }
        WorkspaceRepository.Workspace defaultWorkspace = workspaces.ensureDefault(userId);
        workspaces.migrateLegacySessions(userId, defaultWorkspace.id());
        workspaces.setActiveSession(userId, defaultWorkspace.id(), null);
        return new MigrationResponse(true, (int) legacy, toResponse(defaultWorkspace));
    }

    @PostMapping("/{uuid}/sessions")
    public ResponseEntity<WorkspaceSessionCreated> createSession(@PathVariable String uuid,
                                                                 @RequestBody CreateSessionBody body) {
        long userId = AuthContext.userId();
        WorkspaceRepository.Workspace workspace = ownedWorkspace(uuid, userId);
        SessionRepository.ChatSession session = sessionService.createInWorkspace(
                userId, workspace.id(), body.topic(), body.model(), body.defaultSystemPrompt());
        return ResponseEntity.status(HttpStatus.CREATED).body(new WorkspaceSessionCreated(
                session.uuid(), session.topic(), session.model(), session.artifactEnabled(),
                workspace.uuid(), Times.iso(session.createdAt())));
    }

    @GetMapping("/{uuid}/sessions")
    public List<WorkspaceSessionItem> listSessions(@PathVariable String uuid) {
        long userId = AuthContext.userId();
        WorkspaceRepository.Workspace workspace = ownedWorkspace(uuid, userId);
        return workspaces.sessionsByWorkspaceId(workspace.id()).stream()
                .map(s -> new WorkspaceSessionItem(s.uuid(), s.topic(), false, s.model(),
                        workspace.uuid(), s.maxLength(), s.temperature(), s.maxTokens(), s.topP(),
                        s.n(), s.summarizeMode(), s.exploreMode(), s.artifactEnabled(),
                        Times.iso(s.createdAt()), Times.iso(s.updatedAt())))
                .toList();
    }

    @GetMapping("/active-sessions")
    public List<ActiveSessionItem> activeSessions() {
        long userId = AuthContext.userId();
        return workspaces.activeSessions(userId).stream()
                .map(a -> new ActiveSessionItem(a.workspaceUuid(), a.sessionUuid(),
                        Times.iso(a.updatedAt())))
                .toList();
    }

    @PutMapping("/{uuid}/active-session")
    public ActiveSessionItem setActiveSession(@PathVariable String uuid,
                                              @RequestBody ActiveBody body) {
        long userId = AuthContext.userId();
        WorkspaceRepository.Workspace workspace = ownedWorkspace(uuid, userId);
        if (body.chatSessionUuid() == null || body.chatSessionUuid().isBlank()) {
            throw ApiException.invalid("chatSessionUuid is required");
        }
        workspaces.setActiveSession(userId, workspace.id(), body.chatSessionUuid());
        return new ActiveSessionItem(uuid, body.chatSessionUuid(), null);
    }

    /** Returns the workspace when the current user owns it, otherwise 403/404. */
    private WorkspaceRepository.Workspace ownedWorkspace(String uuid, long userId) {
        WorkspaceRepository.Workspace workspace = workspaces.findByUuid(uuid);
        if (workspace == null) {
            throw ApiException.notFound("Workspace not found with UUID: " + uuid);
        }
        if (workspace.userId() != userId) {
            throw ApiException.forbidden("Access denied to workspace");
        }
        return workspace;
    }

    static WorkspaceResponse toResponse(WorkspaceRepository.Workspace w) {
        return new WorkspaceResponse(w.uuid(), w.name(), w.description(), w.color(), w.icon(),
                w.isDefault(), w.orderPosition(), w.sessionCount(), Times.iso(w.createdAt()),
                Times.iso(w.updatedAt()));
    }
}
