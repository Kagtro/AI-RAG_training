package com.kagtro.rag.service;

import com.kagtro.rag.dao.DocumentDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentDao documentDao;
    private final IndexService indexService;
    private final String uploadDir;

    public DocumentService(DocumentDao documentDao, IndexService indexService,
                           @Value("${rag.upload-dir:uploads}") String uploadDir) {
        this.documentDao = documentDao;
        this.indexService = indexService;
        this.uploadDir = uploadDir;
    }

    public Map<String, Object> upload(long kbId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String originalName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) {
            ext = originalName.substring(dot + 1).toLowerCase();
        }
        if (!List.of("pdf", "docx", "md", "txt").contains(ext)) {
            throw new IllegalArgumentException("暂不支持的文件类型: " + ext);
        }

        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        file.transferTo(target.toFile());

        long id = documentDao.insert(kbId, originalName, target.toString(), ext);
        // 异步解析 + 切片 + Embedding 入库
        indexService.indexAsync(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("fileName", originalName);
        result.put("status", 0);
        result.put("statusText", "待解析");
        return result;
    }

    public List<Map<String, Object>> list(long kbId) {
        return documentDao.findByKb(kbId);
    }

    public Map<String, Object> status(long id) {
        return documentDao.findStatus(id);
    }

    /**
     * 删除文档：数据库记录 + 切片（外键级联） + uploads 原文件。
     *
     * 注意：解析中/待解析的文档不允许删，避免后台索引任务删完后又写 chunk。
     *
     * @return true=删除成功；false=文档不存在
     */
    public boolean delete(long id) throws IOException {
        Map<String, Object> doc = documentDao.findById(id);
        if (doc == null) {
            return false;
        }
        int status = ((Number) doc.get("status")).intValue();
        if (status == 0 || status == 1) {
            throw new IllegalStateException("文档正在解析中，请等解析完成后再删除");
        }

        String filePath = String.valueOf(doc.get("file_path"));
        int deleted = documentDao.deleteById(id);
        if (deleted == 0) {
            return false;
        }

        // 只删除 uploads 目录内的文件，防止误删其他路径
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path file = Paths.get(filePath).toAbsolutePath().normalize();
            if (file.startsWith(root)) {
                Files.deleteIfExists(file);
            } else {
                log.warn("文件不在 uploads 目录内，跳过删除: {}", filePath);
            }
        } catch (IOException e) {
            // 数据库已经删了，文件删除失败只记录日志，避免接口整体失败
            log.warn("删除原文件失败: {}, {}", filePath, e.getMessage());
        }
        return true;
    }
}
