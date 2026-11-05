package com.kagtro.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kagtro.rag.dao.EvalDao;
import com.kagtro.rag.service.ChatService.ChatAnswer;
import com.kagtro.rag.service.RetrievalService.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测执行器：
 * 1. 从 eval_case 读 30 道题；
 * 2. 每题跑一遍检索 + 问答；
 * 3. 算 Recall@5、答案关键词命中、拒答、延迟；
 * 4. 输出 eval/report-时间.csv，并返回汇总。
 *
 * 注意：它不改变 RAG 逻辑，只是一个"自动跑题 + 打分"的工具。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final EvalDao evalDao;
    private final RetrievalService retrievalService;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public EvalService(EvalDao evalDao, RetrievalService retrievalService,
                       ChatService chatService, ObjectMapper objectMapper) {
        this.evalDao = evalDao;
        this.retrievalService = retrievalService;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    /** 单题结果 */
    public record CaseResult(int id,
                             String category,
                             String difficulty,
                             String question,
                             String expectedFiles,
                             String optionalFiles,
                             String retrievedFiles,
                             String citedFiles,
                             boolean recallHit,
                             double recallCoverage,
                             boolean grounded,
                             String keywordHits,
                             boolean correct,
                             Boolean refused,
                             long retrievalMs,
                             long answerMs,
                             long totalMs,
                             String answer,
                             String error) {
    }

    /** 汇总结果 */
    public record EvalReport(int total,
                             int answerable,
                             int crossDoc,
                             int unanswerable,
                             int topK,
                             double minScore,
                             double recallAtK,
                             double crossRecallAtK,
                             double groundedness,
                             double answerAccuracy,
                             double refusalAccuracy,
                             long p95LatencyMs,
                             long avgLatencyMs,
                             String reportPath,
                             String generatedAt,
                             List<CaseResult> cases) {
    }

    public EvalReport run(long kbId, int topK, double minScore) {
        List<Map<String, Object>> rows = evalDao.findAll();
        List<CaseResult> results = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            int id = ((Number) row.get("id")).intValue();
            String category = String.valueOf(row.get("category"));
            String difficulty = row.get("difficulty") == null ? "easy" : String.valueOf(row.get("difficulty"));
            String question = String.valueOf(row.get("question"));
            String expected = row.get("doc_ids") == null ? "" : String.valueOf(row.get("doc_ids"));
            String optional = row.get("optional_files") == null ? "" : String.valueOf(row.get("optional_files"));
            String groundTruth = row.get("ground_truth") == null ? "" : String.valueOf(row.get("ground_truth"));
            String keywords = row.get("keywords") == null ? "" : String.valueOf(row.get("keywords"));

            long t0 = System.currentTimeMillis();
            try {
                // 1. 检索（与线上问答一致：按需查询拆解 + 融合召回）
                long r0 = System.currentTimeMillis();
                var retrieval = retrievalService.retrieveWithDecomposition(kbId, question, topK, minScore);
                List<RetrievedChunk> chunks = retrieval.chunks();
                long retrievalMs = System.currentTimeMillis() - r0;

                // 召回/引用覆盖只强制必选文档；optional_files 引用到不扣分、没引用也不扣分
                Set<String> expectedFiles = splitToSet(expected);
                Set<String> retrievedFiles = new LinkedHashSet<>();
                for (RetrievedChunk chunk : chunks) {
                    retrievedFiles.add(chunk.fileName());
                }
                int matched = 0;
                for (String file : expectedFiles) {
                    if (retrievedFiles.contains(file)) {
                        matched++;
                    }
                }
                boolean recallHit = !expectedFiles.isEmpty() && matched == expectedFiles.size();
                double recallCoverage = expectedFiles.isEmpty() ? 0 : (double) matched / expectedFiles.size();

                // 2. 问答（内部会再检索一次，这里主要拿答案和引用）
                long a0 = System.currentTimeMillis();
                ChatAnswer answer = chatService.ask(kbId, question, topK, minScore);
                long answerMs = System.currentTimeMillis() - a0;

                // 3. 判分
                // 优先用 eval_case.keywords（CSV 里人工维护的关键词）；没有就退回标准答案
                String answerText = answer.answer() == null ? "" : answer.answer();
                String keywordSource = keywords.isBlank() ? groundTruth : keywords;
                String hitKeywords = matchKeywords(answerText, keywordSource);
                boolean correct = !hitKeywords.isEmpty();

                // 引用是否覆盖了期望文档（判断答案有没有超出检索到的资料）
                Set<String> citedFiles = new LinkedHashSet<>();
                for (var citation : answer.citations()) {
                    citedFiles.add(citation.fileName());
                }
                boolean grounded = !expectedFiles.isEmpty() && citedFiles.containsAll(expectedFiles);

                Boolean refused = null;
                if ("unanswerable".equals(category)) {
                    refused = answerText.contains("没有") || answerText.contains("未找到")
                            || answerText.contains("无法") || answer.citations().isEmpty();
                }

                long totalMs = System.currentTimeMillis() - t0;
                results.add(new CaseResult(id, category, difficulty, question, expected, optional,
                        String.join(";", retrievedFiles), String.join(";", citedFiles),
                        recallHit, recallCoverage, grounded,
                        hitKeywords, correct, refused, retrievalMs, answerMs, totalMs, answerText, ""));
            } catch (Exception e) {
                long totalMs = System.currentTimeMillis() - t0;
                log.error("评测题目失败: id={}, question={}", id, question, e);
                results.add(new CaseResult(id, category, difficulty, question, expected, optional, "", "", false, 0,
                        false, "", false, null, 0, 0, totalMs, "", e.getMessage()));
            }
        }

        // ===== 汇总指标 =====
        int answerable = 0, crossDoc = 0, unanswerable = 0;
        int recallHitCount = 0, crossHitCount = 0;
        int groundedCount = 0;
        int answerCorrect = 0, answerTotal = 0;
        int refusedCount = 0;

        for (CaseResult r : results) {
            if ("answerable".equals(r.category())) {
                answerable++;
                if (r.recallHit()) recallHitCount++;
                answerTotal++;
                if (r.grounded()) groundedCount++;
                if (r.correct()) answerCorrect++;
            } else if ("cross_doc".equals(r.category())) {
                crossDoc++;
                if (r.recallHit()) crossHitCount++;
                answerTotal++;
                if (r.grounded()) groundedCount++;
                if (r.correct()) answerCorrect++;
            } else {
                unanswerable++;
                if (Boolean.TRUE.equals(r.refused())) refusedCount++;
            }
        }

        double recallAtK = answerable == 0 ? 0 : (double) recallHitCount / answerable;
        double crossRecallAtK = crossDoc == 0 ? 0 : (double) crossHitCount / crossDoc;
        double groundedness = answerTotal == 0 ? 0 : (double) groundedCount / answerTotal;
        double answerAccuracy = answerTotal == 0 ? 0 : (double) answerCorrect / answerTotal;
        double refusalAccuracy = unanswerable == 0 ? 0 : (double) refusedCount / unanswerable;

        List<Long> latencies = results.stream().map(CaseResult::totalMs).sorted().toList();
        long p95 = percentile(latencies, 0.95);
        long avg = latencies.isEmpty() ? 0 : (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        String reportPath = writeCsv(results);
        EvalReport report = new EvalReport(results.size(), answerable, crossDoc, unanswerable,
                topK, minScore, recallAtK, crossRecallAtK, groundedness,
                answerAccuracy, refusalAccuracy, p95, avg, reportPath,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                results);
        writeJson(report);
        return report;
    }

    private Set<String> splitToSet(String value) {
        Set<String> set = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return set;
        for (String part : value.split(";")) {
            if (!part.isBlank()) set.add(part.trim());
        }
        return set;
    }

    /**
     * 从 answer 中匹配关键词（keywords 多个用分号分隔）。
     * 比较前去掉空白，避免“30 日”和“30日”这种差异。
     */
    private String matchKeywords(String answer, String keywordSource) {
        if (answer == null || keywordSource == null || keywordSource.isBlank()) return "";
        String normalizedAnswer = normalize(answer);
        List<String> hits = new ArrayList<>();
        for (String keyword : keywordSource.split(";")) {
            String k = normalize(keyword);
            if (!k.isBlank() && normalizedAnswer.contains(k)) {
                hits.add(keyword.trim());
            }
        }
        return String.join(";", hits);
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "")
                .replace('％', '%')
                .replace('（', '(')
                .replace('）', ')');
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private String writeCsv(List<CaseResult> results) {
        try {
            Path dir = Paths.get("eval");
            Files.createDirectories(dir);
            String fileName = "report-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
            Path path = dir.resolve(fileName);
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
                writer.write("\uFEFF"); // BOM，方便 Excel 打开中文
                writer.write("id,category,difficulty,question,expected_files,optional_files,retrieved_files,cited_files,recall_hit,recall_coverage,grounded,keyword_hits,correct,refused,retrieval_ms,answer_ms,total_ms,error,answer\n");
                for (CaseResult r : results) {
                    writer.write(String.join(",",
                            String.valueOf(r.id()),
                            r.category(),
                            r.difficulty(),
                            csv(r.question()),
                            csv(r.expectedFiles()),
                            csv(r.optionalFiles()),
                            csv(r.retrievedFiles()),
                            csv(r.citedFiles()),
                            String.valueOf(r.recallHit()),
                            String.format("%.2f", r.recallCoverage()),
                            String.valueOf(r.grounded()),
                            csv(r.keywordHits()),
                            String.valueOf(r.correct()),
                            r.refused() == null ? "" : String.valueOf(r.refused()),
                            String.valueOf(r.retrievalMs()),
                            String.valueOf(r.answerMs()),
                            String.valueOf(r.totalMs()),
                            csv(r.error()),
                            csv(r.answer())
                    ) + "\n");
                }
            }
            // 复制一份 latest，供查看页/下载使用
            Path latest = dir.resolve("report-latest.csv");
            Files.copy(path, latest, StandardCopyOption.REPLACE_EXISTING);
            return latest.toString();
        } catch (IOException e) {
            log.error("写评测报告失败", e);
            return "";
        }
    }

    private void writeJson(EvalReport report) {
        try {
            Path dir = Paths.get("eval");
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("report-latest.json").toFile(), report);
        } catch (IOException e) {
            log.warn("写评测 JSON 失败: {}", e.getMessage());
        }
    }

    private String csv(String value) {
        if (value == null) return "";
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\n") || v.contains("\"")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
