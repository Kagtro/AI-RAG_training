package com.kagtro.rag.service;

import com.kagtro.rag.dao.ChunkDao;
import com.kagtro.rag.service.RetrievalService.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量 BM25 词法召回。
 *
 * 中文没有空格，这里用"字符二元组"作为 term（例如"住房公积金" → 住房/房公/公积/积金），
 * 不依赖分词器，适合个人项目和小语料。
 *
 * 注意：这是应用层 BM25，每次查询会读取当前知识库的切片；
 * 生产规模应换成 Elasticsearch/OpenSearch 的倒排索引，或 PG 的中文全文检索。
 */
@Service
public class Bm25Retriever {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final ChunkDao chunkDao;

    public Bm25Retriever(ChunkDao chunkDao) {
        this.chunkDao = chunkDao;
    }

    public List<RetrievedChunk> search(long kbId, String query, int topK) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> rows = chunkDao.findByKbForBm25(kbId);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Doc> docs = new ArrayList<>(rows.size());
        Map<String, Integer> df = new HashMap<>();
        long totalLength = 0;

        for (Map<String, Object> row : rows) {
            String content = String.valueOf(row.get("content"));
            List<String> tokens = tokenList(content);
            if (tokens.isEmpty()) {
                continue;
            }
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
            totalLength += tokens.size();
            docs.add(new Doc(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("doc_id")).longValue(),
                    row.get("page_no") == null ? null : ((Number) row.get("page_no")).intValue(),
                    content,
                    String.valueOf(row.get("file_name")),
                    tokens.size(),
                    tf
            ));
        }
        if (docs.isEmpty()) {
            return List.of();
        }

        int n = docs.size();
        double avgdl = (double) totalLength / n;
        Map<Long, Double> scores = new HashMap<>();
        for (Doc doc : docs) {
            double score = 0;
            for (String term : queryTerms) {
                Integer freq = doc.tf().get(term);
                if (freq == null) {
                    continue;
                }
                int docFreq = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - docFreq + 0.5) / (docFreq + 0.5));
                double tf = freq;
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * doc.length() / avgdl));
                score += idf * norm;
            }
            if (score > 0) {
                scores.put(doc.id(), score);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, topK))
                .map(entry -> {
                    Doc doc = docs.stream()
                            .filter(d -> d.id() == entry.getKey())
                            .findFirst()
                            .orElseThrow();
                    // 把 BM25 分数压到 0~1，仅用于展示；融合时 RRF 不看原始分
                    double normalized = 1.0 - 1.0 / (1.0 + entry.getValue());
                    return new RetrievedChunk(doc.id(), doc.docId(), doc.pageNo(),
                            doc.content(), doc.fileName(), normalized, "bm25");
                })
                .toList();
    }

    /** 文档 token 列表（中文二元组，保留重复用于 tf） */
    private List<String> tokenList(String text) {
        String normalized = normalize(text);
        List<String> tokens = new ArrayList<>();
        if (normalized.length() < 2) {
            if (!normalized.isBlank()) {
                tokens.add(normalized);
            }
            return tokens;
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            tokens.add(normalized.substring(i, i + 2));
        }
        return tokens;
    }

    /** 查询 token 集合（去重） */
    private Set<String> terms(String text) {
        return new HashSet<>(tokenList(text));
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[，。；：！？、（）【】《》\"'“”‘’*.,;:!?()\\[\\]{}<>/\\\\|-]", "");
    }

    private record Doc(long id, long docId, Integer pageNo, String content,
                       String fileName, int length, Map<String, Integer> tf) {
    }
}
