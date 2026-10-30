package com.kagtro.rag.dao;

import com.kagtro.rag.chunk.ChunkDraft;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ChunkDao {

    private final JdbcClient jdbcClient;

    public ChunkDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void deleteByDoc(long docId) {
        jdbcClient.sql("delete from chunk where doc_id = :docId")
                .param("docId", docId)
                .update();
    }

    public void insertBatch(long kbId, long docId, List<ChunkDraft> chunks, List<float[]> embeddings) {
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft chunk = chunks.get(i);
            jdbcClient.sql("""
                            insert into chunk(kb_id, doc_id, page_no, content, token_count, embedding)
                            values (:kbId, :docId, :pageNo, :content, :tokenCount, CAST(:embedding AS vector))
                            """)
                    .param("kbId", kbId)
                    .param("docId", docId)
                    .param("pageNo", chunk.pageNo())
                    .param("content", chunk.content())
                    .param("tokenCount", chunk.tokenCount())
                    .param("embedding", toVectorLiteral(embeddings.get(i)))
                    .update();
        }
    }

    public int countByDoc(long docId) {
        return jdbcClient.sql("select count(*) from chunk where doc_id = :docId")
                .param("docId", docId)
                .query(Integer.class)
                .single();
    }

    public List<java.util.Map<String, Object>> listByDoc(long docId) {
        return jdbcClient.sql("""
                        select id, page_no, left(content, 120) as preview, token_count
                        from chunk
                        where doc_id = :docId
                        order by id
                        """)
                .param("docId", docId)
                .query()
                .listOfRows();
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
