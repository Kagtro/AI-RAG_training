package com.kagtro.rag.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 问答日志：记录每次提问、召回片段、回答和耗时。
 * 写日志失败不能影响主流程，所以这里自己吞掉异常。
 */
@Repository
public class QaLogDao {

    private static final Logger log = LoggerFactory.getLogger(QaLogDao.class);

    private final JdbcClient jdbcClient;

    public QaLogDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void log(long kbId, String question, List<Long> retrievedIds, String answer, long totalMs) {
        try {
            String ids = retrievedIds == null ? ""
                    : retrievedIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcClient.sql("""
                            insert into qa_log(kb_id, question, retrieved_ids, answer, total_ms)
                            values (:kbId, :question, :retrievedIds, :answer, :totalMs)
                            """)
                    .param("kbId", kbId)
                    .param("question", question)
                    .param("retrievedIds", ids)
                    .param("answer", answer)
                    .param("totalMs", (int) Math.min(Integer.MAX_VALUE, totalMs))
                    .update();
        } catch (Exception e) {
            log.warn("写 qa_log 失败: {}", e.getMessage());
        }
    }
}
