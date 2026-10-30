package com.kagtro.rag.controller;

import com.kagtro.rag.dao.ChunkDao;
import com.kagtro.rag.service.DocumentService;
import com.kagtro.rag.service.IndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final IndexService indexService;
    private final ChunkDao chunkDao;

    public DocumentController(DocumentService documentService, IndexService indexService, ChunkDao chunkDao) {
        this.documentService = documentService;
        this.indexService = indexService;
        this.chunkDao = chunkDao;
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam(defaultValue = "1") long kbId,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        return documentService.upload(kbId, file);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "1") long kbId) {
        return documentService.list(kbId);
    }

    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable long id) {
        Map<String, Object> data = documentService.status(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", data == null ? 404 : 0);
        result.put("data", data);
        return result;
    }

    @PostMapping("/{id}/reindex")
    public Map<String, Object> reindex(@PathVariable long id) {
        indexService.indexAsync(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("msg", "已提交重建索引");
        return result;
    }

    @GetMapping("/{id}/chunks")
    public List<Map<String, Object>> chunks(@PathVariable long id) {
        return chunkDao.listByDoc(id);
    }
}
