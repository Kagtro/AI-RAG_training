# AI-RAG_training

企业知识库智能问答（RAG）系统 —— Java 后端 + 原生前端实现。

> 完整架构说明（含图、数据流、技术选型、已知限制、面试讲解要点）：[docs/architecture.md](docs/architecture.md)

## 技术栈

- Java 17 / Spring Boot 3.3
- PostgreSQL 16 + pgvector（Docker）
- Redis 7（Docker，已部署、暂未使用）
- 阿里云百炼：qwen-plus（对话）/ text-embedding-v3（向量，1024 维）
- 检索：向量召回 + BM25 词法召回（RRF 融合）
- 前端：原生 HTML + CSS + JavaScript（EventSource 接收 SSE）

## 快速开始

```powershell
# 1. 启动中间件（首次会自动拉镜像）
docker compose up -d

# 2. 启动应用
mvn spring-boot:run
```

浏览器打开 `http://localhost:8080`，上传文档后即可提问。

应用默认端口 `8080`，数据库映射到本机 `5432`。

> 密钥放在项目根目录 `.env`（已被 `.gitignore` 忽略），格式：
> `DASHSCOPE_API_KEY=sk-...`。没有 Key 时会自动使用 Mock 实现，链路仍可跑通。

## 当前接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 前端页面 |
| GET | `/api/health` | 健康检查（含数据库连通性） |
| POST | `/api/documents` | 上传文档（pdf/docx/md/txt），自动异步解析、清洗、切片、Embedding 入库 |
| GET | `/api/documents?kbId=1` | 文档列表（含 status、chunk_count） |
| GET | `/api/documents/{id}/status` | 单个文档状态 |
| POST | `/api/documents/{id}/reindex` | 重新解析/重建索引 |
| DELETE | `/api/documents/{id}` | 删除文档（记录 + 切片 + uploads 原文件） |
| GET | `/api/documents/{id}/chunks` | 查看切片（预览） |
| GET/POST | `/api/search` | 向量检索（返回片段 + 相似度） |
| GET/POST | `/api/chat` | 非流式问答（答案 + 引用） |
| GET | `/api/chat/stream` | 流式问答（SSE：meta/delta/done/error） |

## 核心链路

### 索引（写）

```
上传 → document(status=0)
     → @Async indexAsync
     → PDFBox/POI 解析（PDF 保留页码）
     → TextCleaner 清洗（去页眉页脚/页码/断行/重复行）
     → 按段落切片（700 字，overlap 120）
     → Embedding（批量，1024 维）
     → 写 chunk 表（vector(1024) + HNSW 索引）
     → document(status=2, chunk_count=N)
失败 → status=3 + error_msg，可重建索引
```

### 检索（读）

```
问题 → 多意图？查询拆解 → 子问题各自 向量召回 + BM25 词法召回 → RRF 融合去重 → 阈值/topK → 片段
```

### 问答（生成）

```
片段编号拼 Prompt → qwen-plus → 解析 [n] 引用 → 返回答案 + 引用
```

流式接口事件：`meta`（检索完成）、`delta`（回答片段）、`done`（引用）、`error`。

## 目录结构

```
src/main/java/com/kagtro/rag
├── ai/           ChatClient（百炼/Mock）、AiProperties
├── chunk/        切片
├── config/       线程池（indexExecutor / chatExecutor）
├── controller/   health / documents / search / chat
├── dao/          DocumentDao、ChunkDao（含向量检索 SQL）
├── embedding/    EmbeddingClient（百炼/Mock）
├── parser/       PDFBox + POI 解析
├── service/      Document / Index / Retrieval / Chat
└── RagApplication.java

src/main/resources
├── application.yml
├── schema.sql      kb / document / chunk / qa_log / eval_case（含注释）
└── static/         前端页面
```

## 数据模型

- `kb` 知识库
- `document` 文档（status: 0待解析 / 1解析中 / 2已就绪 / 3失败）
- `chunk` 切片（embedding vector(1024)，HNSW 余弦索引）
- `qa_log` 问答日志（已建表，待接入）
- `eval_case` 评测集（已建表，待接入）

## 阶段路线

- [x] 阶段一：环境、工程骨架、数据库建表、上传接口、健康检查
- [x] 阶段二：PDF/DOCX 解析 + 切片 + Embedding 入库（异步索引、可重建）
- [x] 阶段三：向量召回 + 阈值 + 检索接口
- [x] 阶段四：Prompt + 引用 + 非流式回答
- [x] 阶段五：SSE 流式 + 聊天页
- [ ] 阶段六：评测集 + 指标（Recall@K / 正确率 / 拒答率 / P95 / 成本）
- [ ] 阶段七：删除文档、qa_log、缓存限流、鉴权
- [ ] 后续：Rerank、混合检索、OCR、部署与监控
