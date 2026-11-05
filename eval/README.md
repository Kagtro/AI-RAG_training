# 评测集说明（150 题）

> 语料：6 份公开法律法规/规章（劳动与人事领域）
> 评测集：`eval_cases.csv`（150 题），可直接导入 `eval_case` 表：`load_eval.sql`
> 状态：题目已就绪，评测服务已实现（Recall/正确率/拒答/延迟）

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

共 **150 题**：

| 类型 | 数量 | 目的 |
|---|---|---|
| `answerable` | 86 | 单文档可答，测检索 + 生成 |
| `cross_doc` | 40 | 跨两份文档，测多片段整合 |
| `unanswerable` | 24 | 文档里没有，测拒答能力 |

CSV 字段：

| 字段 | 说明 |
|---|---|
| `id` | 题号 |
| `category` | answerable / cross_doc / unanswerable |
| `question` | 问题 |
| `ground_truth` | 标准答案（人工写的参考答案） |
| `expected_files` | **必选**文档（多个用 `;` 分隔），召回/引用覆盖必须命中 |
| `optional_files` | **可选**文档：引用到不扣分，没引用也不扣分（用于内容重叠的题） |
| `keywords` | 自动判分用的关键词（多个用 `;` 分隔） |
| `difficulty` | `easy` / `hard`；hard 为新增难例 |

## 4. 指标定义

| 指标 | 定义 | 计算方式 |
|---|---|---|
| **Recall@K** | 检索 topK 里是否包含必选文档 | 必选文档全部命中才算 1；可选文档不影响 |
| **答案正确率** | 回答与标准答案是否一致 | 先关键词命中，再人工抽检 0 / 0.5 / 1 |
| **引用覆盖率** | 引用是否覆盖必选文档 | 必选文档全部被引用才算 1；可选文档不影响 |
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
-- answerable 86 / cross_doc 40 / unanswerable 24
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

当前结果（`topK=8, minScore=0.5`，150 题，其中 80 道难例）：

| 指标 | 结果 |
|---|---|
| Recall@8（可答 86 题） | 1.0 |
| 跨文档召回（40 题） | 1.0 |
| 关键词正确率（126 题） | 1.0 |
| 拒答准确率（24 题） | 1.0 |
| 引用覆盖率（groundedness） | 1.0 |
| P95 延迟 | 9.9s |
| 平均延迟 | 3.7s |

### 6.1 阈值扫描

`topK=8`、150 题，分别跑不同 `minScore`：

| minScore | Recall@8 | 跨文档召回 | 引用覆盖 | 正确率 | 拒答率 | P95 |
|---|---|---|---|---|---|---|
| 0.1 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 11.4s |
| 0.3 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 11.5s |
| **0.5** | **1.0** | **1.0** | **1.0** | **1.0** | **1.0** | **9.9s** |
| 0.7 | 1.0 | 0.95 | 0.984 | 1.0 | 0.958 | 10.2s |

结论：

- 0.1~0.5 在这套 150 题上没有指标差异；
- 0.5 的 P95 最低，说明适当提高阈值可以减少无效候选；
- 0.7 开始漏召回（跨文档 0.95、拒答 0.958），说明不能太高；
- 因此默认阈值从 **0.1 调整为 0.5**，但这是基于当前语料和 150 题的结论，不是通用最优值。

> 查询拆解解决多意图漏召回；混合检索（向量 + BM25 + RRF）提升精确词/编号查询的鲁棒性；可分别用 `rag.query-decomposition-enabled` / `rag.hybrid-enabled` 做 A/B 对比。

## 7. 注意事项

- 这 6 份文档里有 **2003 版工伤保险条例** 和 **2026 版公积金条例**，答案以语料原文为准；
- `keywords` 只是自动判分的粗筛，最终以人工抽检为准；
- `groundedness` 只看引用文件是否覆盖期望文档，不代表答案一定忠实于原文；
- 其中 80 道为难例（场景判断/精确条款/跨文档/不可答），用于避免指标虚高；语料再扩到 20+ 份时可继续增加；
- 换 Embedding 模型/改切片参数后，**必须重建索引**再评测，否则对比不公平。






