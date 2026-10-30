package com.chat.service;

import com.chat.repo.ChatModelRepository;
import com.chat.repo.MessageRepository;
import com.chat.repo.SessionRepository;
import com.chat.repo.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Session use cases. A session create/update also guarantees the system prompt
 * exists and remembers the selected session per workspace, mirroring the Go
 * transactional {@code SaveSession} / {@code CreateWorkspaceSession} flows.
 */
@Service
public class SessionService {

    public static final int DEFAULT_MAX_LENGTH = 10;
    public static final int DEFAULT_MAX_TOKENS = 4096;
    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final double DEFAULT_TOP_P = 1.0;
    public static final int DEFAULT_N = 1;

    /** Request body mapped to application inputs (no transport coupling). */
    public record SaveSessionCommand(long userId, String sessionUuid, String workspaceUuid,
                                     String topic, String model, int maxLength, double temperature,
                                     double topP, int maxTokens, int n, boolean summarizeMode,
                                     boolean exploreMode, boolean artifactEnabled,
                                     boolean ensurePrompt, String defaultSystemPrompt,
                                     boolean activateGlobally) {
    }

    private final SessionRepository sessions;
    private final WorkspaceRepository workspaces;
    private final MessageRepository messages;
    private final ChatModelRepository models;

    @Value("${app.chat.default-system-prompt}")
    private String defaultSystemPrompt;

    public SessionService(SessionRepository sessions, WorkspaceRepository workspaces,
                          MessageRepository messages, ChatModelRepository models) {
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.messages = messages;
        this.models = models;
    }

    public String defaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public SessionRepository.ChatSession getOwned(String uuid, long userId) {
        SessionRepository.ChatSession session = sessions.findByUuidAndUser(uuid, userId);
        if (session == null || !session.active()) {
            throw com.chat.web.ApiException.notFound("Chat session not found");
        }
        return session;
    }

    @Transactional
    public SessionRepository.ChatSession createInWorkspace(long userId, long workspaceId,
                                                           String topic, String model,
                                                           String promptText) {
        String uuid = UUID.randomUUID().toString();
        String resolvedModel = resolveModel(model);
        String resolvedTopic = topic == null ? "" : topic.trim();
        SessionRepository.ChatSession session = sessions.createInWorkspace(userId, uuid,
                resolvedTopic, resolvedModel, workspaceId, DEFAULT_MAX_LENGTH);
        ensurePrompt(session.uuid(), userId, normalizePrompt(promptText));
        workspaces.setActiveSession(userId, workspaceId, session.uuid());
        return session;
    }

    @Transactional
    public SessionRepository.ChatSession save(SaveSessionCommand cmd) {
        if (cmd.sessionUuid() == null || cmd.sessionUuid().isBlank()) {
            throw com.chat.web.ApiException.invalid("session uuid is required");
        }
        WorkspaceRepository.Workspace workspace = resolveWorkspace(cmd.userId(), cmd.workspaceUuid());
        SessionRepository.ChatSession existing = sessions.findByUuidAndUser(cmd.sessionUuid(), cmd.userId());
        if (existing != null && existing.userId() != cmd.userId()) {
            throw com.chat.web.ApiException.forbidden("chat session does not belong to user");
        }
        int maxLength = cmd.maxLength() <= 0 ? DEFAULT_MAX_LENGTH : cmd.maxLength();
        String model = resolveModel(cmd.model());
        SessionRepository.ChatSession saved;
        try {
            saved = sessions.createOrUpdate(cmd.sessionUuid(), cmd.userId(),
                    cmd.topic() == null ? "" : cmd.topic().trim(), maxLength,
                    cmd.temperature() <= 0 ? DEFAULT_TEMPERATURE : cmd.temperature(), model,
                    cmd.maxTokens(), cmd.topP(), cmd.n(), cmd.summarizeMode(), workspace.id(),
                    cmd.exploreMode(), cmd.artifactEnabled());
        } catch (EmptyResultDataAccessException ex) {
            throw com.chat.web.ApiException.forbidden("chat session does not belong to user");
        }
        if (cmd.ensurePrompt()) {
            ensurePrompt(saved.uuid(), cmd.userId(), normalizePrompt(cmd.defaultSystemPrompt()));
        }
        if (cmd.activateGlobally()) {
            workspaces.setActiveSession(cmd.userId(), null, saved.uuid());
        }
        return saved;
    }

    public void ensurePrompt(String sessionUuid, long userId, String promptText) {
        if (messages.hasPrompt(sessionUuid)) {
            return;
        }
        messages.createPrompt(sessionUuid, UUID.randomUUID().toString(), userId, "system",
                promptText);
    }

    public SessionRepository.ChatSession updateTopic(String uuid, long userId, String topic) {
        if (topic == null || topic.isBlank()) {
            throw com.chat.web.ApiException.invalid("topic is required");
        }
        getOwned(uuid, userId);
        sessions.updateTopic(uuid, userId, topic.trim());
        return sessions.findByUuidAndUser(uuid, userId);
    }

    public SessionRepository.ChatSession updateMaxLength(String uuid, long userId, int maxLength) {
        if (maxLength < 1) {
            throw com.chat.web.ApiException.invalid("maxLength must be positive");
        }
        getOwned(uuid, userId);
        sessions.updateMaxLength(uuid, userId, maxLength);
        return sessions.findByUuidAndUser(uuid, userId);
    }

    public void delete(String uuid, long userId) {
        if (sessions.deactivate(uuid, userId) == 0) {
            throw com.chat.web.ApiException.notFound("Chat session not found");
        }
    }

    private WorkspaceRepository.Workspace resolveWorkspace(long userId, String workspaceUuid) {
        if (workspaceUuid != null && !workspaceUuid.isBlank()) {
            WorkspaceRepository.Workspace workspace = workspaces.findByUuid(workspaceUuid);
            if (workspace == null) {
                throw com.chat.web.ApiException.notFound("Workspace not found");
            }
            if (workspace.userId() != userId) {
                throw com.chat.web.ApiException.forbidden("workspace does not belong to user");
            }
            return workspace;
        }
        return workspaces.ensureDefault(userId);
    }

    private String resolveModel(String model) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        ChatModelRepository.ChatModel fallback = models.findDefault();
        return fallback == null ? "" : fallback.name();
    }

    private String normalizePrompt(String text) {
        if (text != null && !text.isBlank()) {
            return text.trim();
        }
        return defaultSystemPrompt;
    }
}
