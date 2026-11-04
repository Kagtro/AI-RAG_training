# 评测集说明（30 题）

> 语料：6 份公开法律法规/规章（劳动与人事领域）
> 评测集：`eval_cases.csv`（30 题），可直接导入 `eval_case` 表：`load_eval.sql`
> 状态：题目已就绪，评测脚本（Recall/正确率/拒答/延迟）是下一步

---

## 1. 语料来源（全部为公开文件）

| 文件 | 来源 | 说明 |
|---|---|---|
| `01_labor_contract_law.txt` | [福建省人社厅转载](https://rst.fujian.gov.cn/fw/xzqlyx/bssxyj/201912/t20191212_5149303.htm) | 中华人民共和国劳动合同法 |
| `02_social_insurance_law.txt` | [中国政府网](https://www.gov.cn/guoqing/2021-10/29/content_5647616.htm) | 中华人民共和国社会保险法 |
| `03_annual_leave_regulation.txt` | [中国政府网](https://www.gov.cn/zhengce/content/2008-03/28/content_6636.htm) | 国务院令第 514 号 |
| `04_work_injury_regulation.txt` | [中国政府网](https://www.gov.cn/zhengce/content/2008-03/28/content_7416.htm) | 国务院令第 375 号（2003 版） |
| `05_housing_fund_regulation.txt` | [人民日报](https://paper.people.com.cn/rmrb/pc/content/202608/19/content_30175820.html) | 2026 年修订版 |
| `06_wage_payment_regulation.txt` | [中国政府网](https://www.gov.cn/zhengce/2022-08/31/content_5711284.htm) | 劳部发〔1994〕489 号 |

> 这些都是公开的法律法规文本，可以放心用于测试；不要用公司内部资料做语料。

## 2. 为什么选这组语料

- **同一领域**（劳动/人事/社保），这样才出得了"跨文档"题；
- 每篇 1000–11000 字，足够切出多个 chunk，检索才有区分度；
- 内容有明确条款和数字，**标准答案客观**，评测不受主观影响；
- 公开、可复现、无版权/隐私风险。

## 3. 评测集结构

共 **30 题**：

| 类型 | 数量 | 目的 |
|---|---|---|
| `answerable` | 21 | 单文档可答，测检索 + 生成 |
| `cross_doc` | 5 | 跨两份文档，测多片段整合 |
| `unanswerable` | 4 | 文档里没有，测拒答能力 |

CSV 字段：

| 字段 | 说明 |
|---|---|
| `id` | 题号 |
| `category` | answerable / cross_doc / unanswerable |
| `question` | 问题 |
| `ground_truth` | 标准答案（人工写的参考答案） |
| `expected_files` | 期望命中的文档文件名（多个用 `;` 分隔） |
| `keywords` | 自动判分用的关键词（多个用 `;` 分隔） |

## 4. 指标定义

| 指标 | 定义 | 计算方式 |
|---|---|---|
| **Recall@5** | 检索前 5 条里是否包含期望文档 | 单文档题：命中该文档即 1；跨文档题：两份都出现才算 1（同时记录部分命中） |
| **答案正确率** | 回答与标准答案是否一致 | 先关键词命中，再人工抽检 0 / 0.5 / 1 |
| **拒答准确率** | 不可答题是否明确说"没有" | 回答包含"没有/未找到/无法"且引用为空 |
| **P95 延迟** | 从请求到回答完成的时间 | 取 95 分位 |
| **首字延迟** | 流式第一个字的时间 | 可选 |
| **单次成本** | token × 单价 | 按百炼价格估算 |

## 5. 如何导入

### 5.1 上传语料

先启动应用（`docker compose up -d` + `mvn spring-boot:run`），然后：

```powershell
cd E:\code\AI-RAG_training
.\eval\upload_corpus.ps1
```

脚本会把 `test-docs/corpus/*.txt` 逐个上传到 kbId=1，并等待索引完成。

> 注意：文档名必须和 `expected_files` 一致（脚本保留原文件名）。

### 5.2 导入评测题

```powershell
Get-Content .\eval\load_eval.sql -Raw |
  docker exec -i rag-postgres psql -U postgres -d rag -v ON_ERROR_STOP=1
```

### 5.3 检查

```sql
select category, count(*) from eval_case group by category;
-- answerable 21 / cross_doc 5 / unanswerable 4
```

## 6. 运行评测（已实现）

启动应用后：

- **查看结果（不花钱）**：浏览器打开 `http://localhost:8080/eval.html`；
- **重新评测（会调模型）**：页面点“重新评测”，或 `POST /api/eval/run?topK=8&minScore=0.1`；
- `GET /api/eval/latest`：最近一次结果 JSON（只读文件）；
- `GET /api/eval/report`：下载最近一次 CSV。

实现：`EvalController` → `EvalService` → `EvalDao`，逐题执行：

1. 调检索（含多意图查询拆解），判断期望文档是否进前 K → `recallAtK` / `crossRecallAtK`；
2. 调问答，拿回答 + 引用；
3. 关键词命中 → `answerAccuracy`；
4. 引用文件是否覆盖期望文档 → `groundedness`（衡量答案有没有"脱稿"）；
5. 不可答题是否拒答 → `refusalAccuracy`；
6. 记录检索/回答/总耗时 → `p95LatencyMs` / `avgLatencyMs`；
7. 写 `eval/report-时间.csv`（已 gitignore）。

当前结果（`topK=8, minScore=0.1`，开启查询拆解）：

| 指标 | 结果 |
|---|---|
| Recall@8（可答 21 题） | 1.0 |
| 跨文档召回（5 题） | 1.0 |
| 关键词正确率（26 题） | 1.0 |
| 拒答准确率（4 题） | 1.0 |
| 引用覆盖率（groundedness） | 1.0 |
| P95 延迟 | 9.2s |
| 平均延迟 | 2.7s |

> 开启查询拆解后跨文档召回从 0.8 提升到 1.0，但 P95 延迟上升（多一次拆解调用 + 多次检索）。可用 `rag.query-decomposition-enabled=false` 做 A/B 对比。

## 7. 注意事项

- 这 6 份文档里有 **2003 版工伤保险条例** 和 **2026 版公积金条例**，答案以语料原文为准；
- `keywords` 只是自动判分的粗筛，最终以人工抽检为准；
- `groundedness` 只看引用文件是否覆盖期望文档，不代表答案一定忠实于原文；
- 题量 30 是"够用"的规模，不是学术评测；面试时讲"30 条自建评测集"完全成立；
- 换 Embedding 模型/改切片参数后，**必须重建索引**再评测，否则对比不公平。

