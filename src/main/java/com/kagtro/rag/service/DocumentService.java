package com.kagtro.rag.service;

import com.kagtro.rag.dao.DocumentDao;
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
        if (!List.of("pdf", "docx", "doc", "md", "txt").contains(ext)) {
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
}
