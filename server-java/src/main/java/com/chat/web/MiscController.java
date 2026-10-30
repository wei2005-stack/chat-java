package com.chat.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Small static endpoints the Vue client calls on chat startup. */
@RestController
@RequestMapping("/api")
public class MiscController {

    private static final String ARTIFACT_INSTRUCTION = "当用户要求生成代码、HTML、SVG、图表或可预览的文件时，"
            + "请使用 ```html 或 ```svg 代码块输出完整内容，便于前端渲染为可预览的 artifact。";

    public record Instructions(String artifactInstruction) {
    }

    @GetMapping("/chat_instructions")
    public Instructions instructions() {
        return new Instructions(ARTIFACT_INSTRUCTION);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "backend", "java");
    }
}
