package com.chat.service;

import com.chat.repo.KnowledgeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the Go knowledge service: text is indexed as overlapping rune windows
 * and retrieval scores CJK bigrams plus ASCII words. The scoring is deliberately
 * dependency free so the project does not need embeddings for the demo.
 */
@Service
public class KnowledgeService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 100;
    private static final int TOP_K = 5;
    private static final int MIN_SCORE = 2;
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]{2,}");

    public record CreateCommand(long userId, String title, String content) {
    }

    public record DocumentView(long id, String uuid, String title, int chunkCount,
                               java.sql.Timestamp createdAt) {
    }

    public record SearchHit(String documentUuid, String documentTitle, int chunkIndex,
                            String content, int score) {
    }

    public record KnowledgeContext(String text, boolean available) {
    }

    private final KnowledgeRepository repository;

    public KnowledgeService(KnowledgeRepository repository) {
        this.repository = repository;
    }

    public DocumentView create(CreateCommand command) {
        String title = command.title() == null ? "" : command.title().trim();
        String content = command.content() == null ? "" : command.content().trim();
        if (title.isEmpty()) {
            title = "未命名文档";
        }
        if (countRunes(content) < 20) {
            throw new IllegalArgumentException("document content is too short to index");
        }
        List<String> chunks = splitText(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("document content produced no chunks");
        }
        KnowledgeRepository.Document document = repository.createDocument(
                java.util.UUID.randomUUID().toString(), command.userId(), title, content, chunks);
        return new DocumentView(document.id(), document.uuid(), document.title(),
                document.chunkCount(), document.createdAt());
    }

    public List<DocumentView> list(long userId) {
        return repository.listDocuments(userId).stream()
                .map(d -> new DocumentView(d.id(), d.uuid(), d.title(), d.chunkCount(), d.createdAt()))
                .toList();
    }

    public boolean delete(long userId, String documentUuid) {
        return repository.deleteDocument(documentUuid, userId) > 0;
    }

    public List<SearchHit> search(long userId, String query) {
        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (KnowledgeRepository.Chunk chunk : repository.listChunks(userId)) {
            int score = score(chunk.content(), keywords);
            if (score < MIN_SCORE) {
                continue;
            }
            hits.add(new SearchHit(chunk.documentUuid(), chunk.documentTitle(),
                    chunk.chunkIndex(), chunk.content(), score));
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return hits.size() > TOP_K ? hits.subList(0, TOP_K) : hits;
    }

    /** System prompt block injected before the conversation when documents exist. */
    public KnowledgeContext buildContext(long userId, String query) {
        if (repository.listDocuments(userId).isEmpty()) {
            return new KnowledgeContext("", false);
        }
        List<SearchHit> hits = search(userId, query);
        if (hits.isEmpty()) {
            return new KnowledgeContext(
                    "【知识库检索结果】本次检索未在用户的知识库中找到与该问题直接相关的内容。"
                            + "如果问题涉及用户上传的资料，请先明确说明“知识库中未找到相关内容”，再谨慎给出一般性回答，禁止编造资料中不存在的细节。",
                    true);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【知识库检索结果】以下片段来自用户上传的知识库，与当前问题相关。\n");
        builder.append("回答要求：优先依据下列片段作答；引用片段时在句末标注 [1]、[2] 这样的序号；");
        builder.append("并在回答最后单独列出“参考资料：”，格式为“1. 文档名（第N段）”。");
        builder.append("若片段不足以回答问题，请明确说明资料中没有相关记录，不要编造。\n\n");
        for (int index = 0; index < hits.size(); index++) {
            SearchHit hit = hits.get(index);
            builder.append('[').append(index + 1).append("] 文档《").append(hit.documentTitle())
                    .append("》第").append(hit.chunkIndex() + 1).append("段：\n")
                    .append(hit.content()).append("\n\n");
        }
        return new KnowledgeContext(builder.toString(), true);
    }

    private static List<String> splitText(String text) {
        // Work on code points so CJK characters are never split in half.
        int[] runes = text.trim().codePoints().toArray();
        if (runes.length == 0) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < runes.length) {
            int end = Math.min(start + CHUNK_SIZE, runes.length);
            String chunk = new String(runes, start, end - start).trim();
            if (!chunk.isEmpty() && countRunes(chunk) >= 20) {
                chunks.add(chunk);
            }
            if (end == runes.length) {
                break;
            }
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }

    private static int countRunes(String text) {
        return text.codePointCount(0, text.length());
    }

    static List<String> extractKeywords(String query) {
        String lowered = query.toLowerCase();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = ASCII_WORD.matcher(lowered);
        while (matcher.find()) {
            seen.add(matcher.group());
        }
        int[] runes = lowered.codePoints().toArray();
        for (int i = 0; i + 1 < runes.length; i++) {
            if (!isHan(runes[i]) || !isHan(runes[i + 1])) {
                continue;
            }
            seen.add(new String(runes, i, 2));
        }
        return new ArrayList<>(seen);
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static int score(String content, List<String> keywords) {
        String lowered = content.toLowerCase();
        int score = 0;
        for (String keyword : keywords) {
            int count = countOccurrences(lowered, keyword);
            score += Math.min(count, 3);
        }
        return score;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
