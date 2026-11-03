package com.kagtro.rag.controller;

import com.kagtro.rag.service.EvalService;
import com.kagtro.rag.service.EvalService.EvalReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 评测接口：
 * - POST /api/eval/run     触发一次评测（会调 30 次大模型，耗时约 1 分钟）
 * - GET  /api/eval/latest  读取最近一次评测结果 JSON（不调模型）
 * - GET  /api/eval/report  下载最近一次 CSV（不调模型）
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private static final Path LATEST_JSON = Paths.get("eval", "report-latest.json");
    private static final Path LATEST_CSV = Paths.get("eval", "report-latest.csv");

    private final EvalService evalService;
    private final long defaultKbId;
    private final int defaultTopK;
    private final double defaultMinScore;

    public EvalController(EvalService evalService,
                          @Value("${rag.eval-kb-id:1}") long defaultKbId,
                          @Value("${rag.top-k:5}") int defaultTopK,
                          @Value("${rag.score-threshold:0.1}") double defaultMinScore) {
        this.evalService = evalService;
        this.defaultKbId = defaultKbId;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
    }

    /** 触发评测：会真实调用模型，注意成本 */
    @PostMapping("/run")
    public EvalReport run(@RequestParam(required = false) Long kbId,
                          @RequestParam(required = false) Integer topK,
                          @RequestParam(required = false) Double minScore) {
        long id = kbId == null ? defaultKbId : kbId;
        int k = topK == null ? defaultTopK : topK;
        double score = minScore == null ? defaultMinScore : minScore;
        return evalService.run(id, k, score);
    }

    /** 查看最近一次结果：只读文件，不调模型、不花钱 */
    @GetMapping("/latest")
    public ResponseEntity<byte[]> latest() throws IOException {
        if (!Files.exists(LATEST_JSON)) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = Files.readAllBytes(LATEST_JSON);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** 下载最近一次 CSV */
    @GetMapping("/report")
    public ResponseEntity<byte[]> report() throws IOException {
        if (!Files.exists(LATEST_CSV)) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = Files.readAllBytes(LATEST_CSV);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"eval-report.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
