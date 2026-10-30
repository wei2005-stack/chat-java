package com.chat.web;

import com.chat.repo.ChatModelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat_model")
public class ChatModelController {

    private final ChatModelRepository models;

    public ChatModelController(ChatModelRepository models) {
        this.models = models;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return models.listEnabled().stream().map(ChatModelController::toJson).toList();
    }

    @GetMapping("/default")
    public Map<String, Object> defaultModel() {
        ChatModelRepository.ChatModel model = models.findDefault();
        if (model == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no chat model configured");
        }
        return toJson(model);
    }

    static Map<String, Object> toJson(ChatModelRepository.ChatModel m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("name", m.name());
        body.put("label", m.label());
        body.put("isDefault", m.isDefault());
        body.put("url", m.url());
        body.put("apiAuthHeader", m.apiAuthHeader());
        body.put("apiAuthKey", m.apiAuthKey());
        body.put("userId", m.userId());
        body.put("enablePerModeRatelimit", m.enablePerModeRateLimit());
        body.put("maxToken", m.maxToken());
        body.put("defaultToken", m.defaultToken());
        body.put("orderNumber", m.orderNumber());
        body.put("httpTimeOut", m.httpTimeOut());
        body.put("isEnable", m.isEnable());
        body.put("apiType", m.apiType());
        body.put("isTitleModel", m.isTitleModel());
        return body;
    }
}
