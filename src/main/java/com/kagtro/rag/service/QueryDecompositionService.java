package com.kagtro.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kagtro.rag.ai.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询拆解：
 * 把"多意图"问题拆成多个独立、适合检索的子问题。
 *
 * 只在看起来像多意图时才调用 LLM，避免简单问题也增加一次模型调用。
 * 如果 LLM 输出不合法，退回简单的规则拆分；再不行就用原问题。
 */
@Service
public class QueryDecompositionService {

    private static final Logger log = LoggerFactory.getLogger(QueryDecompositionService.class);

    private static final String SYSTEM_PROMPT = """
            你是问题拆解器。判断用户问题是否包含多个需要分别回答的子问题。
            - 如果有，拆成 2~3 个独立、完整、适合检索的中文子问题；
            - 如果没有，输出空数组；
            - 只输出 JSON 字符串数组，不要解释、不要 Markdown 代码块。
            示例：
            输入：单位录用职工后，签订书面劳动合同和办理公积金缴存登记的时限分别是多久？
            输出：["签订书面劳动合同的法定时限是多久？","办理住房公积金缴存登记的法定时限是多久？"]
            """;

    private static final int MAX_SUB_QUERIES = 3;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    public QueryDecompositionService(ChatClient chatClient,
                                     ObjectMapper objectMapper,
                                     @Value("${rag.query-decomposition-enabled:true}") boolean enabled) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * @return 拆解后的子问题；如果不需要拆解，返回空列表
     */
    public List<String> decompose(String question) {
        if (!enabled || question == null || question.isBlank()) {
            return List.of();
        }
        if (!looksMultiIntent(question)) {
            return List.of();
        }
        List<String> cached = cache.get(question);
        if (cached != null) {
            return cached;
        }

        List<String> result = List.of();
        try {
            String raw = chatClient.chat(SYSTEM_PROMPT, question);
            List<String> parsed = parseJsonArray(raw);
            if (parsed.size() >= 2) {
                result = parsed.stream()
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(MAX_SUB_QUERIES)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("查询拆解失败，使用规则兜底: {}", e.getMessage());
        }

        if (result.size() < 2) {
            result = ruleSplit(question);
        }
        cache.put(question, result);
        return result;
    }

    /** 先做低成本判断：没有"多意图信号"就不调模型 */
    private boolean looksMultiIntent(String question) {
        if (question.contains("分别") || question.contains("各自") || question.contains("以及")) {
            return true;
        }
        // "和/与" 作为连接词时，且问题不算短，才认为可能多意图
        return question.length() >= 12 && (question.contains("和") || question.contains("与"));
    }

    private List<String> parseJsonArray(String raw) {
        if (raw == null) {
            return List.of();
        }
        String text = raw.trim();
        // 去掉模型可能输出的 Markdown 代码块
        if (text.startsWith("```")) {
            int first = text.indexOf('[');
            int last = text.lastIndexOf(']');
            if (first >= 0 && last > first) {
                text = text.substring(first, last + 1);
            }
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(text.substring(start, end + 1),
                    new TypeReference<List<String>>() {
                    });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 规则兜底：按"和/与/以及"拆，只适用于问题里有两个并列对象的情况。
     * 拆出来的子问题可能不完整，但通常保留足够的关键词用于检索。
     */
    private List<String> ruleSplit(String question) {
        String normalized = question.replace("以及", "和");
        String[] parts = normalized.split("[和与]", 2);
        if (parts.length < 2) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        String first = parts[0].trim();
        String second = parts[1].trim();
        if (!first.isBlank() && !second.isBlank()) {
            result.add(first);
            result.add(second);
        }
        return result;
    }
}
