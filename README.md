# AI-RAG_training

企业知识库智能问答（RAG）项目 —— Java 后端实现。

## 技术栈（规划）

- Java 17 / Spring Boot 3
- PostgreSQL 16 + pgvector
- Redis 7
- 阿里云百炼（qwen-plus + text-embedding-v3）

## 两周目标

- 文档上传 → 异步解析切片 → 批量 Embedding → 向量入库
- 向量召回 + 阈值 + 重排 + 带引用 Prompt + SSE 流式回答
- 30 条评测集：Recall@5 / 答案正确率 / 拒答率 / P95 / 成本

> 详细方案见本地《AI项目两周方案》。
