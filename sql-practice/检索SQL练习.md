# 检索相关 SQL 练习（面试向）

> 在 IDEA 的 Database 里连上 `rag` 库，切到 `public` schema 直接写。
> 当前库里有 2 个测试文档（id=5、6）和 2 个切片（id=6、7），够练。
> 要求：先自己想，写不出来就查 PostgreSQL/pgvector 文档，再问我。不要直接看答案（我也没给）。

## 题目 1：向量 TopK（pgvector 核心）

以 `chunk` 表里 `id = 6` 这条切片的 `embedding` 作为查询向量，
找出与它最相似的 **5 条**切片（**排除它自己**），返回：

- `id`
- `doc_id`
- `page_no`
- 相似度（列名 `score`，用 `1 - 余弦距离`）

按相似度从高到低排序。

提示：
- 余弦距离运算符是 `<=>`，值越小越相似；
- 子查询可以取出 id=6 的 embedding：`(select embedding from chunk where id = 6)`；
- 排除自己：`id <> 6`；
- 相似度 = `1 - (embedding <=> (子查询))`。

**面试考点**：pgvector 的距离运算符、子查询、排序、TopK（LIMIT）。

---

## 题目 2：带阈值 + 关联文档（检索 SQL 的常见写法）

查出 `kb_id = 1` 下、**相似度 >= 0.5** 的切片，要求：

- 关联 `document` 表，返回 `file_name`；
- 只保留 `document.status = 2`（已就绪）的文档；
- 返回：`chunk.id`、`document.file_name`、`chunk.page_no`、相似度 `score`；
- 按 `score` 降序。

查询向量用 `chunk id = 6` 的 embedding。

**面试考点**：多表 JOIN、WHERE 多条件、别名、阈值过滤写在哪一层。

---

## 题目 3：每个文档的切片数（GROUP BY 高频题）

统计每个文档的切片数量：

- 返回 `document.id`、`document.file_name`、切片数 `chunk_count`；
- **只统计已就绪的文档**（`status = 2`）；
- 一个切片都没有的文档也要显示（数量为 0）；
- 按切片数降序。

**面试考点**：`LEFT JOIN`、`GROUP BY`、`COUNT`、含 0 统计（和之前 SQL 练习集第 5、15 题同类型）。

---

## 题目 4：没有切片的文档（NOT EXISTS）

找出 `document` 表里**完全没有对应切片**的文档 id 和文件名。
提示：可以用 `NOT EXISTS`，也可以用 `LEFT JOIN ... IS NULL`，两种都写写看，比较一下。

**面试考点**：`NOT EXISTS` vs `LEFT JOIN` 的取舍（哪种更容易走索引）。

---

## 题目 5：每个知识库的平均切片数（聚合 + NULL 处理）

按 `kb_id` 统计：

- 该知识库下的文档数；
- 该知识库下的切片总数；
- 平均每个文档的切片数（保留 2 位小数）；
- 没有切片的文档也要计入文档数。

提示：
- 注意 `COUNT`、`SUM`、`AVG` 对 NULL 的处理；
- 除法要防止整数除法（`100.0 *` 或 `::numeric`）；
- 可能需要 `COUNT(DISTINCT ...)`。

**面试考点**：多表聚合、NULL、整数除法、`ROUND`。

---

## 提交方式

把 5 条 SQL 和查询结果截图/贴给我，我逐条 review：
- 语法问题；
- 逻辑漏洞（漏数据、重复计数）；
- 有没有更好的写法；
- 面试时怎么讲思路。
