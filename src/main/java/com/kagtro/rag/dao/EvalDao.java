package com.kagtro.rag.dao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 评测题数据访问：只读取 eval_case 表。
 */
@Repository
public class EvalDao {

    private final JdbcClient jdbcClient;

    public EvalDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Map<String, Object>> findAll() {
        return jdbcClient.sql("""
                        select id, kb_id, question, ground_truth, doc_ids, keywords, category
                        from eval_case
                        order by id
                        """)
                .query()
                .listOfRows();
    }
}
