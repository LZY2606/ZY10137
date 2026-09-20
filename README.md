# 野外身份假设审阅服务

这是一个 Spring Boot + SQLite 的身份不确定性审阅示例。系统保存原始观测、自动候选评分和人工决定，不会用一次最高分匹配覆盖多候选、新个体或时空冲突。

## 运行

```bash
mvn -q -DskipTests package
mvn -q test
mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5337
```

访问：<http://127.0.0.1:5337>

默认在当前目录创建 `wildlife-review.db`，并在首次启动导入演示数据。可通过环境变量调整：

```bash
APP_DB=/path/to/review.db mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5337
APP_DEMO_SEED=false mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5337
```

## 身份假设模型

- `observations`：不可覆盖的原始观测，包括标记片段、花纹摘要、观察者置信度、地点、时间和时间不确定度。
- `candidate_scores`：自动评分结果，按观测×个体保存；可能同时有多个 `CANDIDATE`，系统总是保留“新个体”选项。
- `candidate_decisions`：人工确认或否定，独立于自动分保存，带原因代码和说明。
- `hypothesis_revisions`：已发布或草稿假设版本。草稿从已发布版本复制，并用整型 `version` 做乐观并发检查。
- `revision_items`：某版本中观测到个体的工作分配；草稿可为空，已发布版本必须给每条观测一个个体。
- `published_assignments`：发布快照。同一个已发布版本中，一条观测只属于一个个体。
- `identity_operations`：确认、否定、拆分、合并、直接分配的审计流水，保留基础版本、下一版本和原因。
- `review_conflicts`：并发冲突时同时保存提交方载荷与服务端当前条目、近期操作上下文，观测和已有决定不被覆盖。

多个草稿/未决假设可以并存；只有发布版本生成捕获历史。修订不会改写旧发布快照，而是产生新的版本号。

支持原因代码：

- `MARKER_SHED`：标记脱落。
- `DUPLICATE_CODE`：重复编码。
- `DATA_ENTRY_ERROR`：观察记录输入错误。
- `PATTERN_CONFIRMATION`：花纹人工确认。
- `OBSERVER_REVIEW`：观察者复核。
- `SPLIT_DISTINCT_INDIVIDUAL`：拆分为不同个体。
- `MERGE_SAME_INDIVIDUAL`：合并为同一体。

## 自动候选与冲突

自动分由三部分组成：

- 标记片段相似度：40%
- 花纹 token 相似度：35%
- 观察者置信度：25%

系统取最新已发布版本中的同体锚点，用经纬度 Haversine 距离和双方观测时间不确定度计算最短可达间隔。当前阈值为 `5 m/s`。如果同一已发布个体在时间上不可能从锚点到达新地点，候选保留但自动状态变为 `REJECTED`，分数压到 0.12 以下，并记录距离、间隔、所需速度和原因。审阅者确认被拒绝候选时会被 API 阻止；观测本身不会被删除。

## 时间区间和时区

调查时段由 `zone_id`、本地 `start_at`、本地 `end_at` 定义，在导入时转换为 UTC Instant。区间是半开的：

```text
[start_at, end_at)
```

因此恰好在换班时刻的观测只属于下一期，上一期结束时刻不包含该观测。带显式偏移的观测时间如 `2026-05-02T08:00:00+09:00` 按该偏移解释；无偏移的时间按请求中匹配调查时段的 `zone_id` 解释。

## 缺失值和零值

`survey_sites` 表示某期某地点确实被调查。

- `OBSERVED`：该采样场合至少有一条观测。
- `ZERO`：该场合被调查，但没有任何观测；这是观测到的零。
- `NOT_OBSERVED`：该期×地点不在显式采样场合中，不能推断为零，CSV 导出为 `NA`。

捕获历史按“个体 × 调查期 × 地点”导出。某个体在零观测或未观测场合都没有被该个体捕获，但后者不能用于把未采样当零捕获。

`/api/captures/summary` 仅提供方法验证用基础描述：

- `survey_sites`：显式采样场合数。
- `observed_occasions`：至少有一条观测的采样场合数。
- `captures`：观测记录总数。
- `raw_capture_probability = observed_occasions / survey_sites`。

这不是闭合种群、协变量或异质性模型估计，也不输出生态管理建议。

## REST API 概览

- `POST /api/imports`：幂等导入地点、时段、个体和观测。
- `GET /api/timeline?revision_id=<id>`：时线、候选、冲突、个体和版本。
- `POST /api/revisions`：基于最新或指定已发布版本创建草稿；`client_key` 幂等。
- `POST /api/revisions/{id}/observations/{obs}/candidates/{individual}/confirm`：确认候选。
- `POST /api/revisions/{id}/observations/{obs}/candidates/{individual}/deny`：否定候选。
- `POST /api/revisions/{id}/observations/{obs}/split`：把观测拆到新提出的个体。
- `POST /api/revisions/{id}/observations/{obs}/merge/{fromIndividual}`：把该观测合并到目标个体。
- `POST /api/observations/{obs}/assign`：草稿内直接分配，或设为未决空值；空值不能发布。
- `POST /api/revisions/{id}/publish`：发布草稿；`client_key` 幂等。
- `GET /api/captures/summary`：基础捕获概率摘要。
- `GET /api/captures/history`：JSON 捕获历史。
- `GET /api/captures/history.csv`：CSV 捕获历史，未观测为 `NA`。

所有草稿修改都必须提交读取到的 `base_version`。版本不一致返回 `409`，响应包含当前版本和双方上下文。

## 导入幂等性

导入批次由 `batch_id` 标识，并用完整 JSON 载荷的 SHA-256 校验：

- 相同 `batch_id`、相同载荷：短路返回 `reused=true`，不重复插入。
- 相同 `batch_id`、不同载荷：返回 `409`，防止客户端错误重放。
- 基线批次首次导入会创建 1 号已发布版本；重复导入返回同一基线版本。

参考数据按业务编码 upsert。观测编码全局唯一，重复观测编码不会重复插入。

## 演示数据

演示数据包含：

- 多个时区相同的显式半开调查时段，边界观测落在上一期结束前一秒。
- 换班时刻观测，只属于下一期。
- 模糊标记和重复编码，使一条观测同时保留多个候选。
- 同体锚点在短时间内远距不可达，候选被自动拒绝但观测保留。
- 已采样但零观测地点。
- 相邻但未采样时段与地点，导出为 `NOT_OBSERVED/NA`。
- 基线发布数据和后续未决观测，可在页面创建草稿并执行确认、否定、拆分、合并和发布。

## 测试

集成测试覆盖歧义候选、不可达冲突、半开端点、零/未观测区分、导入幂等、发布幂等、乐观锁冲突上下文和拆分发布：

```bash
mvn -q test
```
