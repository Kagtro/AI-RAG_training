package com.kagtro.rag.service;

import com.kagtro.rag.dao.ChunkDao;
import com.kagtro.rag.embedding.EmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务（RAG 的"召回"阶段）。
 *
 * 职责很单一：
 * 1. 把用户问题变成向量（必须和入库时用同一个 Embedding 模型）；
 * 2. 去 chunk 表里找最相似的 topK 个片段；
 * 3. 过滤掉相似度低于阈值的片段；
 * 4. 把结果转成结构化对象，交给下一步（拼 Prompt）。
 *
 * 面试追问点：
 * - 为什么问题也要 Embedding？因为要在同一个向量空间里比较。
 * - 为什么用余弦距离？文本向量更关注方向，不关注长度。
 * - 阈值干什么？决定"能不能答"，低相似度直接拒答，防幻觉。
 */
@Service
public class RetrievalService {

    private final ChunkDao chunkDao;
    private final EmbeddingClient embeddingClient;
    private final QueryDecompositionService queryDecompositionService;
    private final int defaultTopK;
    private final double defaultMinScore;

    public RetrievalService(ChunkDao chunkDao,
                            EmbeddingClient embeddingClient,
                            QueryDecompositionService queryDecompositionService,
                            @Value("${rag.top-k:5}") int defaultTopK,
                            @Value("${rag.score-threshold:0.1}") double defaultMinScore) {
        this.chunkDao = chunkDao;
        this.embeddingClient = embeddingClient;
        this.queryDecompositionService = queryDecompositionService;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
    }

    /**
     * 一条被检索出来的切片。
     * record 是 Java 16+ 的语法，适合这种"只读数据载体"。
     */
    public record RetrievedChunk(long chunkId,
                                 long docId,
                                 Integer pageNo,
                                 String content,
                                 String fileName,
                                 double score) {
    }

    /** 检索结果：候选片段 + 实际使用的查询（拆解后可能是多个子问题） */
    public record RetrievalResult(List<RetrievedChunk> chunks, List<String> queries) {
    }

    public List<RetrievedChunk> retrieve(long kbId, String question) {
        return retrieve(kbId, question, defaultTopK, defaultMinScore);
    }

    /**
     * 允许调用方覆盖 topK / 阈值（没传就用配置默认值）。
     * 联调 Mock 向量时，可以传 minScore=-1 先看链路是否通。
     */
    public List<RetrievedChunk> retrieve(long kbId, String question, Integer topK, Double minScore) {
        int k = (topK == null || topK <= 0) ? defaultTopK : topK;
        double score = (minScore == null) ? defaultMinScore : minScore;
        return retrieve(kbId, question, k, score);
    }

    public List<RetrievedChunk> retrieve(long kbId, String question, int topK, double minScore) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        // 1. 问题向量化：和入库用同一个模型，维度必须一致
        float[] questionVector = embeddingClient.embedAll(List.of(question)).get(0);

        // 2. 向量检索（SQL 里已经带了 kb_id 过滤、status 过滤、阈值过滤）
        List<Map<String, Object>> rows = chunkDao.searchSimilar(kbId, questionVector, topK, minScore);

        // 3. 转成 record，方便后面拼 Prompt / 序列化
        List<RetrievedChunk> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new RetrievedChunk(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("doc_id")).longValue(),
                    row.get("page_no") == null ? null : ((Number) row.get("page_no")).intValue(),
                    String.valueOf(row.get("content")),
                    String.valueOf(row.get("file_name")),
                    ((Number) row.get("score")).doubleValue()
            ));
        }
        return result;
    }

    /**
     * 带查询拆解的检索：
     * 1. 判断是否多意图，必要时拆成多个子问题；
     * 2. 每个子问题独立召回 topK；
     * 3. 用 RRF（按排名融合）去重排序；
     * 4. 取最终 topK 作为候选集。
     *
     * 不需要拆解时，等价于原来的单查询向量检索。
     */
    public RetrievalResult retrieveWithDecomposition(long kbId, String question,
                                                     Integer topK, Double minScore) {
        int k = (topK == null || topK <= 0) ? defaultTopK : topK;
        double score = (minScore == null) ? defaultMinScore : minScore;

        List<String> subQueries = queryDecompositionService.decompose(question);
        if (subQueries.size() < 2) {
            // 不需要拆解：保持原来的单查询行为
            return new RetrievalResult(retrieve(kbId, question, k, score), List.of(question));
        }

        Map<Long, RetrievedChunk> bestByChunkId = new LinkedHashMap<>();
        Map<Long, Double> rrfScores = new HashMap<>();

        for (String subQuery : subQueries) {
            List<RetrievedChunk> list = retrieve(kbId, subQuery, k, score);
            for (int i = 0; i < list.size(); i++) {
                RetrievedChunk chunk = list.get(i);
                // 同一个片段保留相似度更高的一条
                bestByChunkId.merge(chunk.chunkId(), chunk,
                        (a, b) -> a.score() >= b.score() ? a : b);
                // RRF：只按排名融合，避免不同子查询的分数不可比
                double rrf = 1.0 / (60 + i + 1);
                rrfScores.merge(chunk.chunkId(), rrf, Double::sum);
            }
        }

        List<RetrievedChunk> merged = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(k)
                .map(entry -> bestByChunkId.get(entry.getKey()))
                .toList();

        return new RetrievalResult(merged, subQueries);
    }
}
