package com.kagtro.rag.service;

import com.kagtro.rag.chunk.ChunkDraft;
import com.kagtro.rag.chunk.ChunkingService;
import com.kagtro.rag.dao.ChunkDao;
import com.kagtro.rag.dao.DocumentDao;
import com.kagtro.rag.embedding.EmbeddingClient;
import com.kagtro.rag.parser.PageText;
import com.kagtro.rag.parser.TextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final DocumentDao documentDao;
    private final ChunkDao chunkDao;
    private final TextExtractor textExtractor;
    private final ChunkingService chunkingService;
    private final EmbeddingClient embeddingClient;

    public IndexService(DocumentDao documentDao,
                        ChunkDao chunkDao,
                        TextExtractor textExtractor,
                        ChunkingService chunkingService,
                        EmbeddingClient embeddingClient) {
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.textExtractor = textExtractor;
        this.chunkingService = chunkingService;
        this.embeddingClient = embeddingClient;
    }

    @Async("indexExecutor")
    public void indexAsync(long docId) {
        index(docId);
    }

    /**
     * 同步索引，方便测试和手动重建。
     */
    public void index(long docId) {
        Map<String, Object> doc = documentDao.findById(docId);
        if (doc == null) {
            log.warn("文档不存在，跳过索引: {}", docId);
            return;
        }
        documentDao.updateStatus(docId, 1, null);
        try {
            long kbId = ((Number) doc.get("kb_id")).longValue();
            String filePath = String.valueOf(doc.get("file_path"));
            String fileType = String.valueOf(doc.get("file_type"));

            List<PageText> pages = textExtractor.extract(Path.of(filePath), fileType);
            List<ChunkDraft> chunks = chunkingService.chunk(pages);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("没有解析出文本，可能是扫描版 PDF（暂不支持 OCR）");
            }

            List<String> texts = chunks.stream().map(ChunkDraft::content).toList();
            List<float[]> embeddings = embeddingClient.embedAll(texts);

            chunkDao.deleteByDoc(docId);
            chunkDao.insertBatch(kbId, docId, chunks, embeddings);
            documentDao.updateChunkCount(docId, chunks.size());
            documentDao.updateStatus(docId, 2, null);
            log.info("文档索引完成: docId={}, chunks={}, dim={}", docId, chunks.size(), embeddingClient.dimension());
        } catch (Exception e) {
            log.error("文档索引失败: docId={}", docId, e);
            documentDao.updateStatus(docId, 3, truncate(e.getMessage()));
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 480 ? message : message.substring(0, 480);
    }
}
