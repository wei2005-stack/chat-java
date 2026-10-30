package com.chat.service;

import com.chat.repo.ChatModelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Minimal OpenAI-compatible streaming client. The Go backend relies on
 * go-openai; here a JDK HttpClient is enough because every supported provider
 * (DeepSeek, OpenAI, Ollama) speaks the same chat/completions SSE protocol.
 */
@Component
public class ProviderClient {

    /** Looks like an environment variable name such as DEEPSEEK_API_KEY. */
    private static final java.util.regex.Pattern ENV_NAME =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{2,}");

    public record Message(String role, String content) {
    }

    /** Final answer parts as received from the provider. */
    public record Answer(String content, String reasoning) {
    }

    public interface DeltaSink {
        void onDelta(String text) throws IOException;
    }

    public static class ProviderException extends RuntimeException {
        public ProviderException(String message) {
            super(message);
        }
    }

    private final ObjectMapper mapper;
    private final HttpClient http;

    public ProviderClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public Answer stream(ChatModelRepository.ChatModel model, List<Message> messages,
                         double temperature, double topP, int n, DeltaSink sink) {
        String endpoint = chatCompletionsUrl(model.url());
        String token = resolveToken(model.apiAuthKey());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model.name());
        payload.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content() == null ? "" : m.content()))
                .toList());
        payload.put("temperature", temperature);
        payload.put("top_p", topP <= 0 ? 0.01 : Math.max(0.01, topP - 0.01));
        payload.put("n", Math.max(1, n));
        payload.put("stream", true);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
        } catch (IOException ex) {
            throw new ProviderException("failed to build provider request: " + ex.getMessage());
        }

        HttpResponse<Stream<String>> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ProviderException("provider connection failed: " + ex.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body().limit(5).reduce("", (a, b) -> a + b);
            throw new ProviderException("provider returned HTTP " + response.statusCode() + ": " + body);
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        boolean hasReason = false;
        boolean thinkOpened = false;
        boolean thinkClosed = false;

        try (Stream<String> lines = response.body()) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode root = mapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");
                String chunk = delta.path("content").asText("");
                String reason = delta.path("reasoning_content").asText("");

                if (reason.length() > 0) {
                    hasReason = true;
                    reasoning.append(reason);
                }
                if (chunk.length() > 0) {
                    content.append(chunk);
                }
                if (chunk.isEmpty() && reason.isEmpty()) {
                    continue;
                }

                String display;
                if (reason.length() > 0) {
                    display = thinkOpened ? reason : "<think>" + reason;
                    thinkOpened = true;
                } else if (hasReason && !thinkClosed) {
                    display = "</think>" + chunk;
                    thinkClosed = true;
                } else {
                    display = chunk;
                }
                if (!display.isEmpty()) {
                    sink.onDelta(display);
                }
            }
        } catch (IOException ex) {
            throw new ProviderException("failed to read provider stream: " + ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex instanceof ProviderException) {
                throw ex;
            }
            throw new ProviderException("failed to parse provider stream: " + ex.getMessage());
        }

        if (content.length() == 0 && reasoning.length() == 0) {
            throw new ProviderException("stream closed without content; verify the model URL resolves to a "
                    + "valid OpenAI-compatible endpoint: " + endpoint);
        }
        return new Answer(content.toString(), reasoning.toString());
    }

    /** Mirrors Go's GetModelBaseURL: strip /chat/completions and /completions suffixes. */
    static String chatCompletionsUrl(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new ProviderException("chat model URL is empty");
        }
        URI uri = URI.create(configuredUrl.trim());
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.isEmpty() || "/".equals(path)) {
            return uri.getScheme() + "://" + uri.getAuthority() + "/chat/completions";
        }
        String basePath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String suffix : List.of("/chat/completions", "/completions")) {
            if (basePath.endsWith(suffix)) {
                basePath = basePath.substring(0, basePath.length() - suffix.length());
                break;
            }
        }
        if (basePath.isEmpty()) {
            basePath = "/chat";
        }
        return uri.getScheme() + "://" + uri.getAuthority() + basePath + "/chat/completions";
    }

    /**
     * chat_model.api_auth_key stores the name of the environment variable in the
     * Go project. Keep that convention, but fall back to the literal value so a
     * locally pasted key still works.
     */
    static String resolveToken(String apiAuthKey) {
        if (apiAuthKey == null || apiAuthKey.isBlank()) {
            return "";
        }
        String key = apiAuthKey.trim();
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        // chat_model.api_auth_key normally stores an environment variable name. When
        // that variable is missing, sending the name itself upstream only yields a
        // confusing 401, so fail fast with an actionable message.
        if (ENV_NAME.matcher(key).matches()) {
            throw new ProviderException("未配置环境变量 " + key
                    + "，请设置该变量后重启后端（当前 api_auth_key 会被当作密钥发送）");
        }
        return key;
    }
}
