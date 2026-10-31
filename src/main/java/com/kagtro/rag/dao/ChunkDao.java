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

    /**
     * 向量检索：在指定知识库内，找出与查询向量最相似的 topK 个切片。
     *
     * SQL 关键点（面试常问）：
     * 1. <=> 是 pgvector 的余弦距离运算符，值越小越相似；
     *    相似度 = 1 - 距离，范围大致 0~1。
     * 2. ORDER BY 距离 ASC 就是"从最相似到最不相似"。
     * 3. WHERE 里带 kb_id：只搜当前知识库，避免越权/串库。
     * 4. JOIN document 是为了拿文件名；同时只保留解析成功（status=2）的文档。
     * 5. 阈值过滤放在 SQL 里，减少不必要的数据传输；也可以查出来再过滤。
     */
    public List<java.util.Map<String, Object>> searchSimilar(long kbId, float[] queryVector,
                                                             int topK, double minScore) {
        String vectorLiteral = toVectorLiteral(queryVector);
        return jdbcClient.sql("""
                        select c.id,
                               c.doc_id,
                               c.page_no,
                               c.content,
                               d.file_name,
                               1 - (c.embedding <=> CAST(:vector AS vector)) as score
                        from chunk c
                        join document d on d.id = c.doc_id
                        where c.kb_id = :kbId
                          and d.status = 2
                          and 1 - (c.embedding <=> CAST(:vector AS vector)) >= :minScore
                        order by c.embedding <=> CAST(:vector AS vector) asc
                        limit :topK
                        """)
                .param("vector", vectorLiteral)
                .param("kbId", kbId)
                .param("minScore", minScore)
                .param("topK", topK)
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
