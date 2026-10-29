-- 企业知识库 RAG 问答：初始化表结构（幂等，可重复执行）

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    owner_id   BIGINT       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS document (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT       NOT NULL REFERENCES kb(id),
    file_name   VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    file_type   VARCHAR(20),
    status      SMALLINT     NOT NULL DEFAULT 0,   -- 0待解析 1解析中 2已就绪 3失败
    error_msg   VARCHAR(500),
    chunk_count INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_document_kb ON document(kb_id);

CREATE TABLE IF NOT EXISTS chunk (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT       NOT NULL,
    doc_id      BIGINT       NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    page_no     INT,
    content     TEXT         NOT NULL,
    token_count INT,
    embedding   vector(1024) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunk_kb  ON chunk(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON chunk(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON chunk
    USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS qa_log (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT,
    kb_id             BIGINT,
    question          TEXT,
    retrieved_ids     TEXT,
    answer            TEXT,
    prompt_tokens     INT,
    completion_tokens INT,
    cost              NUMERIC(10, 6),
    first_token_ms    INT,
    total_ms          INT,
    cache_hit         BOOLEAN NOT NULL DEFAULT false,
    feedback          SMALLINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS eval_case (
    id           BIGSERIAL PRIMARY KEY,
    kb_id        BIGINT,
    question     TEXT NOT NULL,
    ground_truth TEXT,
    doc_ids      TEXT,
    category     VARCHAR(30)   -- 可答 / 跨文档 / 不可答
);

-- 默认知识库，本地联调用
INSERT INTO kb(name, owner_id)
SELECT '默认知识库', 1
WHERE NOT EXISTS (SELECT 1 FROM kb);
