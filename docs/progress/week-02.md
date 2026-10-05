# 第二周进度：工程骨架与场馆 / 场次

- 更新日期：2026-10-05（Asia/Shanghai）
- 状态：第二周工程与数据编码项完成，自动化及 API 演示证据齐备；负责人代码理解与整周学习验收尚未完成

## 已确认与已实现

- Java 21、Spring Boot 4.1.1、Maven 3.9.9、MyBatis、业务分包、Flyway 和 Docker Compose MySQL，见 ADR-0004；Q-01 至 Q-03 已解决。
- 项目内 JDK 下载与 SHA-256 校验、Maven Wrapper、开发/测试独立 MySQL、启动与检查脚本已实现。
- 请求编号、统一响应、参数及异常处理、健康检查和 OpenAPI 已实现。
- 项目负责人于 2026-10-04 确认[七表与快照方案](../design/week-02-schema-review.md)，决策记入 ADR-0005。
- V1 已创建 venue、area、seat_template、event、session、price_tier、session_seat；稳定归属使用外键，快照来源 ID 不使用外键。
- 场馆/区域维护、座位模板原子批量添加、修改与删除、演出创建及查询已实现。
- 创建场次在同一个 REPEATABLE READ 本地事务中生成场次、票档、全部快照；DRAFT 初始状态，唯一代码防重复并返回 409。
- 查询场次座位只读快照；不可售位保留布局，允许不配置票档。
- 规则测试厅种子与快照演示脚本已实现；重跑核对并复用，不自动清理数据库。脚本基准时间可通过 -BaseTime 注入，Java 业务及测试使用 Clock。
- 项目负责人于 2026-10-05 确认场次管理写操作先锁场次行、开售前置条件和重复目标规则，见 ADR-0006；首次开售冻结沿用 ADR-0005。
- 首次开售前原子修改场次时间/孤座规则、已有票档名称/价格及已有座位票档/可售性；票档变更同步所有关联快照，不改变来源模板或座位布局。
- DRAFT→ON_SALE、ON_SALE→STOPPED、STOPPED→ON_SALE 已实现，首次开售时间永久保留；重复设置当前销售状态无副作用。开售/恢复至少需要一个可售且票档启用的座位。
- 曾开售后配置冻结，暂停也不能改价；票档开关独立启停，不改变任何座位快照或状态。销售演示使用单独场次，可重复运行。
- 协作约定补充：已确认方案下的编码、测试和文档直接推进，重要点集中讲解，先讲解再按需检查理解。
- 246 座星河艺术中心种子已通过现有业务 API 实现，两个区域/14 排/242 可售座位，三个场次和 9 个独立票档，共 738 个座位快照。基础账号按 Q-05 延后，种子明确输出账号数 0。
- 主演示种子先预检现存对象再写入，按稳定代码复用，时间统一到微秒；支持从已提交但未开售的 DRAFT 场次恢复，拒绝漂移并保留现存数据。规格及代码导读见[主演示数据](../development/galaxy-seed.md)。

## 自动化验证证据

环境：Windows 11 amd64，Temurin 21.0.12.1+1，Maven 3.9.9，Docker Desktop 4.75.0 / Engine 29.5.2，MySQL 8.4.11。开发端口 3307、测试端口 3308，库和数据卷独立。

最近一次 Java 完整命令：`scripts/test.ps1 -Integration`；2026-10-05 14:02:41 +08:00 完成，BUILD SUCCESS。前一轮快照交付的 27 项通过记录为 2026-10-04 13:40:33 +08:00。本轮主演示数据仅修改 PowerShell 和文档，未改 Java 或迁移，没有把上述记录写成新一轮 Maven 执行。

| 测试类 | 数量 | 结果 |
| --- | ---: | --- |
| ApiContractTest | 7 | 全部通过 |
| SessionSnapshotIT | 13 | 全部通过 |
| SessionManagementIT | 11 | 全部通过 |
| Week2ApiIT | 7 | 全部通过 |
| ApplicationStartupIT | 2 | 全部通过 |
| 合计 | 40 | failures=0、errors=0、skipped=0 |

本轮业务测试验证：

- 固定 Clock、两个区域、五排、56 座（54 座默认可售），过道与单座票档覆盖正确。
- 不可售位允许没有票档；不同场次的票档 ID、名称和金额独立。
- 区域名称、模板坐标、排座、分区或可用性变更，以及删除模板，不影响旧快照；新场次使用变更后的布局。
- 未知/遗漏/重复映射、非法入场窗口和停用来源拒绝创建，没有三类目标数据残留。
- 真实快照写入后注入异常，验证场次、票档、全部座位回滚。
- 两个线程用 CyclicBarrier 同时创建相同代码，恰好一个成功、一个冲突，只有一套完整快照。
- 两个事务用 CountDownLatch 协调模板变更与创建；本次读到旧的完整版本，下一次读到新版本，没有通过真实 sleep 碰运气。
- 数据库拒绝重复座位/快照、跨场次票档、缺失或非正数价格；模板批量插入中途冲突整体回滚，排排序规则生效。
- 真实 HTTP 验证 201/200、400/404/409、嵌套数组参数校验、UTC 转换、快照隔离、请求编号和 OpenAPI。
- 场次配置同步关联座位（包括不可售位）票价，其他场次和来源模板不变；启用无票档位被拒绝，补票档后启用成功；未知/重复/跨场次 ID 和无效窗口均整体回滚。
- 三条销售迁移、重复状态/票档开关、首次时间保留、开售和暂停后的配置冻结、无可售座位或无启用票档的开售/恢复拒绝。
- 真实修改场次、票档、座位之后注入异常，三类配置全部回滚；没有把异常注入报告为进程终止实验。
- 两线程两事务用 CountDownLatch 在真实 SELECT FOR UPDATE 持锁后协调：改价先持锁则配置提交后开售；开售先持锁则等待的改价读到最新状态并返回冻结冲突。
- HTTP 验证配置/销售/票档开关的完整流程、新接口 OpenAPI、嵌套空项与负价格、未知状态和 404/409。

记录位置：本地被忽略的 `.local/week2-build.log`（前一轮）、`.local/session-management-build.log`（最新完整运行），`target/surefire-reports` 和 `target/failsafe-reports`。测试代码和固定夹具已保留，日志中的数值不是性能实验结果。

首轮业务集成测试曾失败：直接在事务代理上设置 Spy 故障注入触发 MANDATORY 检查，且 MySQL CHECK 3819 的驱动异常类型与断言不一致。改为注入代理后的 Spy 目标并检查具体 MySQL 错误码后通过，未弱化生产事务或数据库约束。

本轮首跑的两个并发用例曾因 Mockito 对 MyBatis 接口调用 callRealMethod 失败，持锁信号未到达。工作线程异常诊断明确为 abstract real method；改为在协调 Spy 中显式委托 SqlSessionTemplate 的真实 Mapper，SQL 仍加入当前线程事务，完整测试随后通过，未改弱生产锁策略。诊断记录在 `.local/session-management-diagnostic.log`。

## 开发库演示证据

- 打包 jar 曾在本机 18080 临时运行，开发库 Flyway V1 成功、健康检查 UP。
- 实际开发库已查询到七张业务表及 flyway_schema_history，迁移版本 1、success=1。
- `demo-snapshot.ps1 -BaseUrl http://127.0.0.1:18080` 多次成功：旧快照全部不变、新快照坐标 777.125、重复代码返回 409，finally 恢复模板。
- W2 命名空间重复运行复用原数据：1 场馆、2 区域、56 模板、1 演出、2 场次、4 票档、112 场次座位，没有重复增加。
- 已手工构造种子模板差异：seed 拒绝继续且保留改动，没有静默覆盖；验证结束恢复模板，再次演示通过。
- 本轮销售 jar 在 18081 运行且健康 UP，开发库迁移仍为 V1/success=1，没有修改已执行 V1 或手工建表。
- `demo-session-sales.ps1 -BaseUrl http://127.0.0.1:18081` 首次及重复执行通过。W2-SALES 保留 DRAFT 种子场次 3（56 座/54 可售），管理场次 4（56 座/55 可售）最终 ON_SALE，A 档 420 元，first_opened_at 为 2026-10-05T06:04:15.655836Z，重复执行保持。
- 票档启停、暂停和恢复不改已记录的座位快照；暂停后配置请求返回 409。原 W2 快照隔离脚本在新版本应用上再次通过并恢复模板。
- 演示记录在 `.local/session-sales-demo-first.log`、`.local/session-sales-demo-repeat.log`、`.local/snapshot-demo-after-management.log`。
- 两轮临时 jar 进程都已按 PID 与完整命令路径校验后停止；MySQL 容器保持运行。用户按 run-app.ps1 启动默认 8080。

## 主演示种子验证证据（2026-10-05）

本轮复用同一 Java 21 / MySQL 8.4.11 环境，临时应用端口 18081、开发库端口 3307。PowerShell 脚本语法检查通过，调用真实 HTTP 与业务事务，没有直写种子 SQL。

- `seed-galaxy.ps1 -BaseUrl http://127.0.0.1:18081` 首次及重跑通过。DEMO 基准为 2026-10-05T06:19:11.054089Z；场次 5/6/7 分别为 DRAFT/ON_SALE/ON_SALE，各 246 座/242 可售；合计 738 快照。
- 只读 SQL 核对 DEMO、VERIFY、VERIFY-R 三个独立命名空间，各 2 区域、246 模板、242 启用模板。DEMO 售票场次有 48 座优选票 580 元、100 座标准票 380 元、94 座楼座票 280 元，另 4 位无票档。
- `verify-galaxy-seed.ps1 -BaseUrl http://127.0.0.1:18081` 首次及重复运行通过。比较所有对象 ID、数据和时间；重跑提供 +90 天 BaseTime，仍与前次完全相同，首次开售时间不变。
- VERIFY 的池座 A1 真实改为 999.125 后，种子拒绝且保留改动，三个旧场次全部不变；finally 恢复后种子再次通过。
- 初次手工准备 VERIFY-R 仅有一个已提交的售票 DRAFT 场次 11，然后运行种子补齐场次 12/13，复用原基准及 ID 11 并开售。这是构造中间状态的恢复验证，不是杀进程实验。第二次验证复用已有夹具，newlyPreparedPartial=False，没有重复制造中间状态。
- 验证数据保留在 VERIFY、VERIFY-R，不污染 DEMO、原 W2 或 W2-SALES；不清库、不移除数据卷。Flyway 仍为 V1/success=1。
- 日志位于被忽略的 `.local/galaxy-seed-first.log`、`.local/galaxy-seed-repeat.log`、`.local/galaxy-verification.log`、`.local/galaxy-verification-repeat.log`、`.local/galaxy-sql-verification.log`。这些数量是完整性证据，不是性能指标。
- 本轮临时 jar PID 40836 已核对完整命令路径后停止，MySQL 保持运行。需要演示时按 run-app.ps1 启动默认 8080。

## 讲解与后续范围

入口、主流程、数据变化、错误分支、事务与测试代码见[场次快照导读](../development/session-snapshots.md)、[场次销售导读](../development/session-sales.md)和[主演示数据导读](../development/galaxy-seed.md)。核心入口是 SessionService.create、SessionManagementService 和 seed-galaxy.ps1，跨模块依赖沿用已确认边界，不跨模块访问 Mapper。

第二周工程、种子与脚本验证已齐；学习和负责人演示验收单独记录，不将编码完成视为用户已经理解。重要点包括快照隔离、事务回滚、唯一键防重复、首次开售冻结、场次行锁，以及单场次事务与跨请求种子恢复的区别。下一开发阶段为第三周座位查询/座位图与连座推荐；已确认规则无需重新询问，新增重要决策仍先评审。

尚未实现：演示登录令牌及账号、第三周座位图/连座、锁座下单、支付、退票和核销。当前售卖开关尚无消费者下单入口可执行，未宣称已保护真实订单；没有把这些状态字段或已设计场景写成已完成测试。

用户确认第一周核心理解检查已完成一轮纠正；完整五分钟口头验收和本轮代码理解验收没有独立通过记录，二者与编码交付区分。

本地 main 与 origin 已配置，骨架提交为 b33af3d、快照提交为 46bf2d2、销售提交为 3cb8ef6；本轮主演示种子按脚本、数据、恢复说明和文档一致性检查后本地提交，本轮未推送。Git 完成状态以实际日志为准。原有 img/ 不在本轮范围中。
