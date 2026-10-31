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

-- ============================================================
-- 表和字段注释（IDEA / DBeaver 连接后能直接看到，方便理解）
-- ============================================================

COMMENT ON TABLE kb IS '知识库：一个知识库对应一批文档';
COMMENT ON COLUMN kb.id IS '主键';
COMMENT ON COLUMN kb.name IS '知识库名称';
COMMENT ON COLUMN kb.owner_id IS '创建人用户 id';
COMMENT ON COLUMN kb.created_at IS '创建时间';

COMMENT ON TABLE document IS '文档：用户上传的原始文件及其解析状态';
COMMENT ON COLUMN document.id IS '主键';
COMMENT ON COLUMN document.kb_id IS '所属知识库 id';
COMMENT ON COLUMN document.file_name IS '原始文件名';
COMMENT ON COLUMN document.file_path IS '服务器上的存储路径';
COMMENT ON COLUMN document.file_type IS '文件类型：pdf/docx/doc/md/txt';
COMMENT ON COLUMN document.status IS '解析状态：0待解析 1解析中 2已就绪 3失败';
COMMENT ON COLUMN document.error_msg IS '失败原因（status=3 时有值）';
COMMENT ON COLUMN document.chunk_count IS '切片数量';
COMMENT ON COLUMN document.version IS '版本号（用于重建索引/软切换）';
COMMENT ON COLUMN document.created_at IS '上传时间';

COMMENT ON TABLE chunk IS '切片：文档切分后的小块 + 向量，是检索的最小单位';
COMMENT ON COLUMN chunk.id IS '主键';
COMMENT ON COLUMN chunk.kb_id IS '所属知识库 id（检索时用于权限/范围过滤）';
COMMENT ON COLUMN chunk.doc_id IS '所属文档 id（删除文档时级联删除）';
COMMENT ON COLUMN chunk.page_no IS '原文页码（用于引用溯源）';
COMMENT ON COLUMN chunk.content IS '切片原文';
COMMENT ON COLUMN chunk.token_count IS '估算 token 数（控制上下文长度）';
COMMENT ON COLUMN chunk.embedding IS '文本向量，1024 维，pgvector 类型';
COMMENT ON COLUMN chunk.created_at IS '创建时间';

COMMENT ON TABLE qa_log IS '问答日志：记录每次提问、召回、回答、耗时与成本';
COMMENT ON COLUMN qa_log.id IS '主键';
COMMENT ON COLUMN qa_log.user_id IS '提问用户 id';
COMMENT ON COLUMN qa_log.kb_id IS '知识库 id';
COMMENT ON COLUMN qa_log.question IS '用户问题';
COMMENT ON COLUMN qa_log.retrieved_ids IS '召回片段的 id 列表（逗号分隔）';
COMMENT ON COLUMN qa_log.answer IS '模型回答原文';
COMMENT ON COLUMN qa_log.prompt_tokens IS '提示词 token 数';
COMMENT ON COLUMN qa_log.completion_tokens IS '回答 token 数';
COMMENT ON COLUMN qa_log.cost IS '本次调用成本（元）';
COMMENT ON COLUMN qa_log.first_token_ms IS '首字延迟（毫秒）';
COMMENT ON COLUMN qa_log.total_ms IS '总耗时（毫秒）';
COMMENT ON COLUMN qa_log.cache_hit IS '是否命中缓存';
COMMENT ON COLUMN qa_log.feedback IS '用户反馈：1 有用 / -1 没用';
COMMENT ON COLUMN qa_log.created_at IS '提问时间';

COMMENT ON TABLE eval_case IS '评测集：问题 + 标准答案 + 期望命中的文档，用于量化效果';
COMMENT ON COLUMN eval_case.id IS '主键';
COMMENT ON COLUMN eval_case.kb_id IS '知识库 id';
COMMENT ON COLUMN eval_case.question IS '评测问题';
COMMENT ON COLUMN eval_case.ground_truth IS '标准答案';
COMMENT ON COLUMN eval_case.doc_ids IS '期望命中的文档 id 列表';
COMMENT ON COLUMN eval_case.category IS '题目类型：可答 / 跨文档 / 不可答';
