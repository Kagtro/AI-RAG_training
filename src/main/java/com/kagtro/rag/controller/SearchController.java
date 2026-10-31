package com.kagtro.rag.controller;

import com.kagtro.rag.service.RetrievalService;
import com.kagtro.rag.service.RetrievalService.RetrievedChunk;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索接口（只做"召回"，还不调用大模型）。
 * 这一步的验收标准：问一个问题，能返回相关片段和相似度分数。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final RetrievalService retrievalService;

    public SearchController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * POST /api/search
     * 请求体：{"kbId":1,"question":"报销上限是多少","topK":5,"minScore":0.1}
     * topK / minScore 可以不传，使用配置默认值。
     */
    @PostMapping
    public Map<String, Object> search(@RequestBody SearchRequest request) {
        long kbId = request.kbId() == null ? 1L : request.kbId();
        List<RetrievedChunk> chunks = retrievalService.retrieve(
                kbId, request.question(), request.topK(), request.minScore());
        return toResponse(request.question(), chunks);
    }

    /**
     * GET /api/search?question=...&kbId=1&topK=5&minScore=0.1
     * 方便用浏览器/curl 直接试。
     */
    @GetMapping
    public Map<String, Object> searchGet(@RequestParam String question,
                                         @RequestParam(defaultValue = "1") long kbId,
                                         @RequestParam(required = false) Integer topK,
                                         @RequestParam(required = false) Double minScore) {
        List<RetrievedChunk> chunks = retrievalService.retrieve(kbId, question, topK, minScore);
        return toResponse(question, chunks);
    }

    private Map<String, Object> toResponse(String question, List<RetrievedChunk> chunks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("count", chunks.size());
        result.put("chunks", chunks);
        return result;
    }

    /** 请求体：kbId 可选，question 必填，topK/minScore 可选 */
    public record SearchRequest(Long kbId, String question, Integer topK, Double minScore) {
    }
}
