package com.kagtro.rag.service;

import com.kagtro.rag.dao.ChunkDao;
import com.kagtro.rag.embedding.EmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final Bm25Retriever bm25Retriever;
    private final int defaultTopK;
    private final double defaultMinScore;
    private final boolean hybridEnabled;

    public RetrievalService(ChunkDao chunkDao,
                            EmbeddingClient embeddingClient,
                            QueryDecompositionService queryDecompositionService,
                            Bm25Retriever bm25Retriever,
                            @Value("${rag.top-k:5}") int defaultTopK,
                            @Value("${rag.score-threshold:0.1}") double defaultMinScore,
                            @Value("${rag.hybrid-enabled:true}") boolean hybridEnabled) {
        this.chunkDao = chunkDao;
        this.embeddingClient = embeddingClient;
        this.queryDecompositionService = queryDecompositionService;
        this.bm25Retriever = bm25Retriever;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
        this.hybridEnabled = hybridEnabled;
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
                                 double score,
                                 String source) {
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
                    ((Number) row.get("score")).doubleValue(),
                    "vector"
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
        List<String> effectiveQueries = subQueries.size() < 2 ? List.of(question) : subQueries;

        Map<Long, RetrievedChunk> bestByChunkId = new LinkedHashMap<>();
        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, Set<String>> channels = new HashMap<>();
        List<RetrievedChunk> mustInclude = new ArrayList<>();

        for (String query : effectiveQueries) {
            // 通道 1：向量召回
            List<RetrievedChunk> vectorList = retrieve(kbId, query, k, score);
            addRanked(vectorList, "vector", rrfScores, bestByChunkId, channels);
            if (!vectorList.isEmpty() && mustInclude.stream()
                    .noneMatch(c -> c.chunkId() == vectorList.get(0).chunkId())) {
                mustInclude.add(vectorList.get(0));
            }

            // 通道 2：BM25 词法召回（中文按字符二元组）
            if (hybridEnabled) {
                List<RetrievedChunk> bm25List = bm25Retriever.search(kbId, query, k);
                addRanked(bm25List, "bm25", rrfScores, bestByChunkId, channels);
                if (!bm25List.isEmpty() && mustInclude.stream()
                        .noneMatch(c -> c.chunkId() == bm25List.get(0).chunkId())) {
                    mustInclude.add(bm25List.get(0));
                }
            }
        }

        int finalLimit = Math.max(k, mustInclude.size());
        List<RetrievedChunk> merged = new ArrayList<>(rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(finalLimit)
                .map(entry -> withSource(bestByChunkId.get(entry.getKey()), channels.get(entry.getKey())))
                .toList());

        // 多意图覆盖：确保每个子查询的向量/BM25 第一名都进候选
        Set<Long> selectedIds = new LinkedHashSet<>();
        for (RetrievedChunk chunk : merged) {
            selectedIds.add(chunk.chunkId());
        }
        for (RetrievedChunk required : mustInclude) {
            if (required == null || selectedIds.contains(required.chunkId())) {
                continue;
            }
            if (merged.size() >= finalLimit && !merged.isEmpty()) {
                RetrievedChunk removed = merged.remove(merged.size() - 1);
                selectedIds.remove(removed.chunkId());
            }
            RetrievedChunk chunk = withSource(bestByChunkId.get(required.chunkId()),
                    channels.get(required.chunkId()));
            merged.add(chunk);
            selectedIds.add(chunk.chunkId());
        }

        return new RetrievalResult(merged, effectiveQueries);
    }

    private void addRanked(List<RetrievedChunk> list,
                           String channel,
                           Map<Long, Double> rrfScores,
                           Map<Long, RetrievedChunk> bestByChunkId,
                           Map<Long, Set<String>> channels) {
        for (int i = 0; i < list.size(); i++) {
            RetrievedChunk chunk = list.get(i);
            double rrf = 1.0 / (60 + i + 1);
            rrfScores.merge(chunk.chunkId(), rrf, Double::sum);
            bestByChunkId.merge(chunk.chunkId(), chunk,
                    (a, b) -> a.score() >= b.score() ? a : b);
            channels.computeIfAbsent(chunk.chunkId(), id -> new LinkedHashSet<>()).add(channel);
        }
    }

    private RetrievedChunk withSource(RetrievedChunk chunk, Set<String> channels) {
        if (chunk == null) {
            return null;
        }
        Set<String> set = channels == null ? Set.of() : channels;
        String source;
        if (set.contains("vector") && set.contains("bm25")) {
            source = "hybrid";
        } else if (set.contains("bm25")) {
            source = "bm25";
        } else {
            source = "vector";
        }
        return new RetrievedChunk(chunk.chunkId(), chunk.docId(), chunk.pageNo(),
                chunk.content(), chunk.fileName(), chunk.score(), source);
    }
}
