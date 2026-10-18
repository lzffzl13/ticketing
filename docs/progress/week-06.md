# 第六周进度：现场核销、审计与一致性检查

- 更新日期：2026-10-18（Asia/Shanghai）
- 当前状态：核销、审计、一致性检测代码与文档已完成，2026-10-18 12:21:16 +08 完整 **422 项**回归通过，真实 HTTP 全流程、原命名空间重跑及应用重启后的审计持久性验证通过；负责人学习验收尚未执行。

## 实际起点与确认

从第五周 `c85ef98` 创建 `codex/week6-checkin-consistency`，原有未跟踪 `img/` 保留。阅读项目基线、README、第五周周报、开放问题及相关用例/状态/流程、实际代码和迁移。第五周 345 项通过是已有历史证据，不作为本轮执行结果。

核销和 Q-08 两位只读评审后形成[第六周方案](../design/week-06-checkin-consistency-review.md)。负责人回复“两组都采纳，直接编码（推荐）”，记录 ADR-0019 / 0020，关闭 Q-08。按本次要求先完成编码与工程验证，之后再做学习验收，不把方案接受写成理解通过。

## 本次范围

核销入口、逐次安全审计、查询、末票核销完成汇总；单场次一致快照报告与定期扫描；重复/并发/退款竞争和故障回滚测试；从固定种子开始的真实 HTTP 购买、退款回流、再购和核销演示。

不增加 Redis、MQ、真实支付或候补。截止后未到场订单的批量 COMPLETED 归集暂缓；实时入场截止仍严格生效。检测报告以接口及日志交付，不新增报告持久表或外部告警系统。

## 环境准备

开始时 Docker Desktop 引擎未运行；已启动现有 Desktop 和项目开发/测试 MySQL，保留原数据卷和密码。`scripts/check-infra.ps1 -IncludeTest` 已验证两个应用账号、数据库与 MySQL 8.4.11 健康。此项只证明环境可用，不代表迁移或业务测试已通过。

## 编码、验证与演示

### 已实现

- V6 `checkin_record` 保存每次已受理扫描，含工作人员、服务端请求标识、所选场次/原提交编号、可空原票、SHA-256 摘要、时间及结果；唯一生成列约束每票最多一条 SUCCESS。
- CHECKER `POST /api/checkins` 核销；CHECKER / OPERATOR `GET /api/checkins` 按场次、票、半开时间范围和 id 游标查询。格式和权限拒绝位于受理前；业务失败追加审计并提交，系统写失败整体回滚，不能凭 HTTP 200 放行，须看 SUCCESS。
- 核销沿场次→订单→座位→票锁方向，在锁等待后取时间；VALID→CHECKED_IN、成功审计和满足条件的 COMPLETED 同事务。库存保持 SOLD；已退/退款中票先按原身份拒绝，不访问已重售占用。
- 补齐其他票已核销后既有退款成功的 COMPLETED 汇总，全部退款仍为 FULLY_REFUNDED；重复旧支付/退款结果不倒退完成状态。
- consistency 独立只读包，单场次统一 RR 快照检查本地跨表不变量；运营报告与默认 60 秒/批量 10 的定期扫描，固定上界回绕、查询失败保留游标、单场次失败隔离。正常渠道未完成工作单列 recovery，不猜测修表。
- 完整演示脚本保存原购买/退款/重售身份和敏感原票码于忽略目录，真实等待入场；已完成重跑只读检查。接口、数据变化、异常与核心代码见[核销导读](../development/checkin-consistency.md)和[演示说明](../development/week-06-demo.md)。

### 实际测试证据

| 执行 | 结果 | 证据与范围 |
| --- | --- | --- |
| 首次定向回归，12:15:48 +08 | 149 单元/API/调度 + 39 IT，共188通过 | CheckinIT 26、RefundIT 13；`.local/week6-checkin-targeted.log` |
| 完整 `scripts/test.ps1 -Integration`，12:21:16 +08 | **150 单元/API/调度 + 272 IT，共422通过** | failures=0、errors=0、skipped=0；`.local/week6-full-regression.log` 与本轮 Maven summary / failsafe-summary.xml |

环境：Windows 11 amd64、Temurin Java 21.0.12.1、Maven 3.9.9、MySQL 8.4.11；隔离测试库 ticketing_test:3308。Flyway V1–V6 校验通过，新 V6 实际迁移成功。计数不混入 target 历史 benchmark XML。

相比第五周345项增加77项：ConsistencyRulesTest20、ConsistencyScannerTest6、ConsistencySchedulingTest2、CheckinIT26、ConsistencyIT23。核销5轮双入口同步起跑，每轮恰一成功、一重复且两个审计；四微秒边界、场次锁等待跨截止、退票等待跨开放、退款成功/失败通知竞争、真实审计 INSERT 后异常回滚及 MySQL 重复 SUCCESS 约束均执行。测试日志中的注入异常是预期故障路径，不作为真实生产事故。

一致性覆盖13类明确 SQL 故障（仅测试库）、检测前后全部本地/渠道表不变、正常取消后重售、失败退款后重试、真实退款回流及核销，以及两连接/latch 暂停读取期间实际提交支付或退款，验证 RR 快照不混读新旧版本。另验证提前 COMPLETED 和实际全部履约却错误退回 PAID 的归集异常；仅因截止而尚未归集仍允许。

[实际非零报告样例](../testing/examples/week6-consistency-difference.json)由本轮 ORDER_TOTAL 故障生成；原760元订单被测试夹具改为761元，检测到 C01/C04 两条关联差异。checkedAt 来自受控测试 Clock，编号为本轮测试库自增，不是生产事实。生成方式与人工处置见[测试场景](../testing/invariant-scenarios.md)。

### 真实 HTTP 全流程与重启

应用使用与完整回归同一次打包的 `.local/week6-checkin.jar` 副本，SHA-256 `38A07855167888D58658C31F4B9B9512F1D77C0346CB544C7B0A61286837BE14`，开发库 ticketing:3307，地址 `http://127.0.0.1:18090`。启动PID2424，V5→V6实际迁移成功，保留原全部历史。使用既有受控票码密钥，不输出凭证。

运行 `scripts/demo-checkins.ps1 -BaseUrl http://127.0.0.1:18090 -Namespace W6RUN1018`，种子准备后设置真实开场为基准+62分钟：入场开放北京时间 **2026-10-18 12:20:44.915192**，截止 **13:15:44.915192**。默认开场前60分钟开放、前5分钟截止，支付原15分钟保留；购退款完成后真实分段等待，没有改应用 Clock 或 SQL 伪造业务状态。

| 事实 | 结果 |
| --- | --- |
| 场次35、consumer1原订单17 | 两张原票17/18，原成功支付保留；单票退款3仅退票17，另一票保持有效 |
| consumer2重售订单18 | 回购座位3631，新票19与原票17不同；旧退款成功通知重放不改变新SOLD占用 |
| 窗口开放后核销 | 新票19和保留票18各一条SUCCESS；两订单COMPLETED；旧票REFUNDED、两入场票CHECKED_IN、两座SOLD |
| 重复与旧码 | 审计1/2为两条SUCCESS，3为新票ALREADY_CHECKED_IN，4为原旧票REFUNDED；旧票无成功记录 |
| 运营报告 | consistent=true，differences=[]；订单2、历史票3、退款1、成功核销2，未决/人工恢复0 |
| 原命名空间重跑 | VERIFIED，只读业务验证，不新增订单/退款/核销 |
| 真实进程重启 | 验证PID2424的本项目jar后停止，以同jar/原密钥重启为22968；健康UP，-VerifyOnly通过，四条原审计全部字段前后相同 |

本机证据：`.local/week6-http-demo.log`、`week6-http-demo-repeat.log`、`week6-http-demo-restart-verify.log`、`week6-audit-before-restart.json` / `week6-audit-after-restart.json`、`week6-app.out.log` / `week6-restarted-app.out.log`。敏感状态仅在 `.local/demo-checkins/W6RUN1018.json`；安全报告单独保存为 `W6RUN1018-consistency.json`。这些日志不进Git，新机器按提交脚本产生自己的编号。应用副本保持在18090运行，后续学习可查询既有事实。

### 代码审查和交付检查

另一位代理对核销与一致性集成做只读二审，检查同快照、历史重售、事务和调度边界；补齐实际全部履约却未完成的C08盲点后才执行完整回归。检查迁移、权限、种子窗口、运行/API说明与代码一致，`git diff --check`通过。原有 `img/` 不纳入交付。Git提交编号以实际提交结果为准。

## 学习验收与后续范围

本轮代码集中讲解、负责人独立复述和学习验收尚未执行。第二周及第五周历史未验收项目仍按各周记录保留。第七周继续并发/故障实验和方案比较；条件候补须先满足计划门槛。
