package com.kagtro.rag.dao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class DocumentDao {

    private final JdbcClient jdbcClient;

    public DocumentDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long insert(long kbId, String fileName, String filePath, String fileType) {
        return jdbcClient.sql("""
                        insert into document(kb_id, file_name, file_path, file_type, status)
                        values (:kbId, :fileName, :filePath, :fileType, 0)
                        returning id
                        """)
                .param("kbId", kbId)
                .param("fileName", fileName)
                .param("filePath", filePath)
                .param("fileType", fileType)
                .query(Long.class)
                .single();
    }

    public List<Map<String, Object>> findByKb(long kbId) {
        return jdbcClient.sql("""
                        select id, kb_id, file_name, file_type, status, chunk_count, created_at
                        from document
                        where kb_id = :kbId
                        order by id desc
                        """)
                .param("kbId", kbId)
                .query()
                .listOfRows();
    }

    public Map<String, Object> findStatus(long id) {
        return jdbcClient.sql("select id, file_name, status, error_msg, chunk_count from document where id = :id")
                .param("id", id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);
    }
}
