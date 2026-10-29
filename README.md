# AI-RAG_training

企业知识库智能问答（RAG）系统 —— Java 后端实现。

## 技术栈

- Java 17 / Spring Boot 3.3
- PostgreSQL 16 + pgvector（Docker）
- Redis 7（Docker）
- 阿里云百炼：qwen-plus（对话）/ text-embedding-v3（向量，1024 维）

## 快速开始

```powershell
# 1. 启动中间件（首次会自动拉镜像）
docker compose up -d

# 2. 启动应用
mvn spring-boot:run
```

应用默认端口 `8080`，数据库映射到本机 `5433`（避开本机已有的 Odoo PostgreSQL 5432）。

### 验证

```powershell
curl http://localhost:8080/api/health
# {"status":"ok","db":"up","app":"ai-rag"}

curl -F "kbId=1" -F "file=@test-doc.txt" http://localhost:8080/api/documents
curl "http://localhost:8080/api/documents?kbId=1"
```

## 当前接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查（含数据库连通性） |
| POST | `/api/documents` | 上传文档（pdf/docx/doc/md/txt），落库为待解析 |
| GET | `/api/documents?kbId=1` | 文档列表 |
| GET | `/api/documents/{id}/status` | 单个文档状态 |

## 目录结构

```
src/main/java/com/kagtro/rag
├── RagApplication.java
├── controller/    HealthController、DocumentController
├── service/       DocumentService
└── dao/           DocumentDao（JdbcClient）

src/main/resources
├── application.yml
└── schema.sql      kb / document / chunk / qa_log / eval_case
```

## 数据模型

- `kb` 知识库
- `document` 文档（status: 0待解析 / 1解析中 / 2已就绪 / 3失败）
- `chunk` 切片（embedding vector(1024)，HNSW 余弦索引）
- `qa_log` 问答日志（token / 耗时 / 成本 / 缓存命中）
- `eval_case` 评测集

## 两周路线

- [x] Day1：环境、工程骨架、数据库建表、上传接口、健康检查
- [ ] Day2：PDF/DOCX 解析 + 切片
- [ ] Day3：Embedding + 异步索引任务
- [ ] Day4：向量召回 + 阈值
- [ ] Day5：Prompt + 引用 + 非流式回答
- [ ] Day6：SSE 流式 + 聊天页
- [ ] Day7：联调 + demo
- [ ] 第二周：多轮对话、重排、评测集、缓存限流、部署、简历
