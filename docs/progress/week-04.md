# 第四周进度：锁座、订单与模拟支付

- 更新日期：2026-10-08（Asia/Shanghai）
- 状态：首个闭环全部方案已确认；登录、幂等下单与整组锁座已实现，165 项测试及真实 API 演示通过；核心代码理解检查待完成

## 本轮已完成

- 阅读项目规则、计划、第三周周报和开放问题，并核对实际分支和代码。开始时分支为 main，HEAD 为 b716455，仅有未跟踪的 img/，本轮保留该目录。
- 确认第三周座位图、推荐、选座规则及本轮学习验收已完成；第二周整周学习验收仍单列，不重复要求第三周确认。
- 完成[身份与锁座下单评审稿](../design/week-04-order-hold-review.md)：包含 Q-04 / Q-05 / Q-11 备选方案、同段不同座位导致新孤座的竞争例子、读取隔离陷阱、六表与当前持有者草案、幂等事务、模块边界和 12 组最低验收场景。
- 补出 Q-11：过期场次若仍 ON_SALE，单靠现有销售状态不能阻止下单。初稿比较完整 15 分钟与入场截止的衔接方式；负责人随后采纳建议，已记入 ADR-0011 并更新正式业务规则，见下方确认记录。
- 将第四周计划里的支付失败释放描述对齐已确认状态规则：明确失败允许截止前重试；安全取消才释放。

## 本轮实际执行证据

2026-10-08 16:08:11 +08:00，执行仓库现有 `scripts/test.ps1 -Integration`，BUILD SUCCESS。Java 21.0.12.1、Maven 3.9.9、MySQL 8.4.11；测试实例 ticketing_test:3308。开发和测试 MySQL 均完成应用账号 SELECT 1 / 库名 / 字符集只读检查。

| 测试类 | 数量 | 结果 |
| --- | ---: | --- |
| ApiContractTest | 7 | 通过 |
| SeatSelectionRulesTest | 42 | 通过 |
| SeatRecommendationEngineTest | 31 | 通过 |
| SeatQueryIT | 9 | 通过 |
| SessionManagementIT | 11 | 通过 |
| SessionSnapshotIT | 13 | 通过 |
| Week2ApiIT | 7 | 通过 |
| ApplicationStartupIT | 2 | 通过 |
| 合计 | 122 | failures=0、errors=0、skipped=0 |

日志 `.local/week4-baseline.log`，XML 报告在 target/surefire-reports 和 target/failsafe-reports。最初受限运行中 Docker 管道访问被拒绝、Maven Wrapper 自检出现空数组异常；以允许访问本机依赖的环境重跑原脚本后成功，未修改 Wrapper 或跳过测试。

这是**已有功能进入第四周前的回归基线**。该次执行时尚无新迁移、身份或交易实现；后续首个闭环的新增测试及演示证据单独列在下方，支付、电子票和锁座性能数据仍未交付。

## 2026-10-08 并发讲解与确认

负责人指出一次要求确认整份技术方案时尚无法判断。本轮改为逐项讲解：以同段 1–5、甲买 1/2、乙买 4/5 为例，说明不重叠请求也可能共同制造新孤座，以及场次行锁、锁后重验、条件更新的作用与串行代价。

另说明数据库行锁在事务提交后释放，业务座位占用继续保留 15 分钟。负责人表示能够理解，并明确确认并发建议，记入 [ADR-0009](../decisions/0009-serialize-session-inventory-writes.md)。当次只接受并发建议，其余方案随后分别获得确认；设计确认与代码/测试证据分开记录。

## 2026-10-08 幂等与时间边界确认

以提交成功但响应丢失、10:01 重试仍返回原订单和 10:15 原期限为例讲解请求编号。负责人明确采纳下单幂等与此前时间边界建议，分别记入 ADR-0010 与 ADR-0011，Q-11 已解决。正式项目规则、用例和开放问题已同步。

负责人要求讲解登录，随后澄清需要比较通用 JWT / Session 与本项目随机令牌草案，而非核对其旧项目实现。讲解后负责人表示全部采纳并授权开始，身份、六表、完整事务、隔离级别与模块边界获批，见 ADR-0012 / 0013；Q-04 / Q-05 已解决。

## 首个闭环已实现

- V2 新增 account、demo_login_token、customer_order、order_item、seat_hold、seat_hold_item，扩展 session_seat.current_hold_id、同场次复合外键与 HELD/持有者双向 CHECK；V1 未改写。
- BCrypt 固定账号登录、32 字节随机令牌、MySQL SHA-256 十六进制摘要与两小时期限；真实 Spring Security 检查角色，默认拒绝未知路由，统一 401 / 403。排除未使用的默认内存账号配置。
- POST /api/orders 从认证上下文取消费者；规范化请求与成功订单持久幂等。场次行锁内 READ COMMITTED 读取最新资格/库存，锁后取微秒 Clock，完整 15 分钟期限；后端价格、订单成交快照、整组锁及条件占座共同提交。
- GET /api/orders/{id} 查询本人完整订单，未知或他人订单统一 404；相同请求重放返回原状态和原期限，不续锁。
- 演示 .env 增加 DEMO_PASSWORD，旧管理脚本自动登录 operator1；W4 演示在 POST 前保存非敏感请求参数，重跑沿用原键和座位，不删除交易。
- 入口、主流程、异常、数据和事务说明见[订单导读](../development/order-holds.md)；演示见[脚本说明](../development/order-holds-demo.md)。

## 首个闭环实际验证

2026-10-08 **17:02:38 +08:00**，`scripts/test.ps1 -Integration` BUILD SUCCESS。环境与基线一致：Java 21.0.12.1、Maven 3.9.9、MySQL 8.4.11，独立 ticketing_test:3308；V1/V2 由 Flyway 验证并执行。

| 测试类 | 数量 | 结果 |
| --- | ---: | --- |
| ApiContractTest | 7 | 通过，真实安全链 |
| IdentityServiceTest（新增） | 7 | 通过 |
| SeatSelectionRulesTest | 42 | 通过 |
| SeatRecommendationEngineTest | 31 | 通过 |
| SeatQueryIT | 9 | 通过，合法真实订单占用夹具 |
| SessionManagementIT | 11 | 通过 |
| SessionSnapshotIT | 13 | 通过 |
| Week2ApiIT | 7 | 通过，真实运营登录 |
| IdentityIT（新增） | 12 | 通过 |
| InventoryHoldFailureIT（新增） | 4 | 通过 |
| OrderHoldIT（新增） | 20 | 通过 |
| ApplicationStartupIT | 2 | 通过 |
| 合计 | 165 | failures=0、errors=0、skipped=0；87 unit + 78 IT |

新增 43 项覆盖身份、权限、期限、种子回滚、订单成交快照、手选/推荐规则、旧候选、重叠抢座、不重叠孤座竞争、暂停/票档竞争、同键和跨场次竞争、微秒边界及真实部分写入/提交前回滚。并发用屏障与真实事务，不用 sleep 猜测先后；不声称性能容量。

首次全套发现 MVC slice 缺少 HttpSecurity，通过主配置显式 EnableWebSecurity 接入，保留真实过滤链。独立审查补强跨场次同键测试：在两个真实 INSERT 前同步，实际揭示 NOT_SUPPORTED 外层同步绑定 MyBatis 会话缓存旧 null 的恢复读取问题。修复为 facade 初查及唯一键失败后的读取均进入独立 REQUIRES_NEW 只读 RC 事务；最终全套通过。没有以等待、关闭约束或跳过测试绕过问题。

测试日志 `.local/week4-first-loop-tests.log`，XML 在 target/surefire-reports 与 target/failsafe-reports。本机脚本受限环境下 Wrapper 自检失败，使用允许访问本机依赖的环境运行原脚本，未修改 Wrapper。

真实应用通过 `scripts/run-app.ps1 -Port 18084` 启动，开发库 ticketing:3307 在 17:03:15 完成 V1→V2，固定账号种子成功。17:04:54 开始 W4 演示，首次 201 创建订单 1 / 锁 1，两个座位合计 760.00；支付及锁截止均为 `2026-10-08T09:19:56.010743Z`。立即重跑返回 200，订单/锁/原期限不变。consumer2 同组购买 409 SEAT_NOT_AVAILABLE，读取原订单 404；56 座最终 2 HELD、52 AVAILABLE、2 UNAVAILABLE。

日志 `.local/week4-demo-first.log` / `week4-demo-repeat.log`，非敏感购买参数 `.local/demo-order-holds/W4.json`；不记录密码或令牌。W3 推荐脚本真实重跑通过（25 个合法两座组合、停用票档排除、STOPPED 409、库存与状态恢复），日志 `.local/week4-old-demo.log`。实际 OpenAPI 核对 bearer scheme、13 个受保护写入口和匿名场次 GET，保存 `.local/week4-openapi.json`。

演示后已结束本轮临时应用进程，数据库和交易保留；再次使用 run-app 启动。代码经并发、事务与文档独立审查。工作分支 `codex/week4-order-holds`，本地提交结果以 Git 日志和交付报告为准；未推送。原有未跟踪 img/ 保留，不纳入本轮提交。

## 下一步与未完成内容

1. 带负责人阅读首个闭环关键代码并完成理解检查，重点为场次行锁、整组事务、幂等回滚与原期限。第二周整周学习验收仍单列。
2. 评审下一闭环的支付模拟器、支付/票表、安全取消和恢复方式。Q-06 必须在第四周支付/取消恢复机制落地前明确；Q-07 在二维码凭证开发前明确。
3. 完成支付出票闭环和数据库锁座基线实验。当前尚无支付、出票、取消或自动超时释放；到期仍可能 HELD。第五周接入扫描，失败检测、重试与恢复须另外说明。
