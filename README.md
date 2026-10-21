# 座位票务与现场履约系统

一个以“具有唯一身份和空间关系的场次座位”为核心的票务项目，覆盖场馆模板、场次快照、选座与连座推荐、锁座下单、模拟支付、电子票、单票退票、座位回流和现场核销。

项目当前已实现购票、支付、超时安全取消、单票退款回流、本人 HMAC 票码与现场核销闭环，并提供逐次核销审计、运营一致性报告与后台检测。已有固定账号和角色鉴权、幂等下单、原子锁座/出票/回流、持久支付/退款恢复、场次独立快照、销售启停、座位图、确定性推荐和 246 座主演示数据；算法及锁座基线见对应文档。

2026-10-18 12:21:16 +08 完整 **422 项通过**（150 unit/API/调度 + 272 IT，零失败/错误/跳过）。真实 HTTP 购买、单票退款、同座重售、真实窗口核销、重复/旧码审计及应用重启后持久审计已验证，详见[第六周周报](docs/progress/week-06.md)。第五周完整345项及此前证据保留。15分钟到达不会自行释放座位，已有支付须查明结果并安全关单。第六周于2026-10-19按负责人要求完成本轮业务讲解及项目收尾；源码深入与尚未完全讲清的内容保留后续，不宣称全部实现已掌握。后续学习沿用完整业务流程、典型问题和少量必要确认的方式；第二周及第五周未完成的学习项目仍单列。

## 项目目标

- 完成一条可运行、可测试、可演示的购票与履约闭环。
- 在并发、超时、重复请求和回调乱序下保持关键业务不变量。
- 用可重复的测试和实验解释方案选择，不虚构生产数据或性能结论。
- 保持模块化单体和 MySQL 事实来源，只有验证收益后才增加 Redis、消息队列等组件。

## 核心边界

- 场馆座位模板与场次座位快照分离。
- 订单、支付、座位、电子票和退票分别建模状态。
- 系统推荐保证连座，手动选座不强制连座；两者都遵守可售与孤座规则。
- 连座推荐不等于锁座成功，提交时必须重新校验并整组锁定。
- 支付截止不等于座位立即释放；已有支付必须查单、关单后安全处理。
- 退票成功、原票失效和座位回流在同一个本地事务中完成。
- 所有核销尝试都留痕，包括重复核销和无效票码。
- 候补是条件增强项，不属于最低交付线。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [PROJECT.md](PROJECT.md) | 项目定位、MVP、业务不变量和架构原则 |
| [PROJECT_PLAN.md](PROJECT_PLAN.md) | 八周节奏、每周验收和降级规则 |
| [核心用例](docs/requirements/core-use-cases.md) | 消费者、运营人员、核销人员和系统任务的用例 |
| [领域关系模型](docs/domain/domain-model.md) | 模板、场次、交易和履约对象之间的关系 |
| [状态迁移](docs/domain/state-transitions.md) | 座位、锁、订单、支付、票、退票和候补状态机 |
| [核心流程](docs/domain/core-flows.md) | 正常流程、支付竞争、退票回流和核销流程 |
| [不变量测试场景](docs/testing/invariant-scenarios.md) | 19 条不变量和 32 个可执行场景 |
| [种子数据设计](docs/data/seed-data-design.md) | 规则夹具、246 座主演示场馆和规模数据方案 |
| [开放问题](docs/project/open-questions.md) | 尚未决定的业务与工程问题 |
| [架构决策](docs/decisions/) | 已确认方案、备选方案和取舍 |
| [工程基线](docs/decisions/0004-engineering-baseline.md) | Java 21、Boot、MyBatis、模块边界及数据库方式 |
| [本地环境](docs/development/local-environment.md) | Java 21、Docker MySQL 启动、验证和恢复 |
| [后端骨架导读](docs/development/backend-skeleton.md) | 启动、接口流程、异常处理及测试依据 |
| [七表与快照方案](docs/design/week-02-schema-review.md) | 已确认的字段、约束、快照隔离与创建事务 |
| [场次快照导读](docs/development/session-snapshots.md) | API、种子演示、主流程、事务和测试依据 |
| [场次销售导读](docs/development/session-sales.md) | 配置、开售/暂停/恢复、冻结与并发测试 |
| [主演示数据导读](docs/development/galaxy-seed.md) | 246 座、三个场次、种子重跑和中断续跑 |
| [座位图与连座导读](docs/development/seat-recommendations.md) | 查询入口、算法顺序、孤座复用、事务、演示与学习检查 |
| [第三周方案](docs/design/week-03-seat-recommendation-review.md) | 已确认的查询一致性、扫描算法、排序和接口 |
| [ADR-0007](docs/decisions/0007-read-only-seat-recommendations.md) | 推荐只读、确定性中心评分和最终锁座边界 |
| [ADR-0008](docs/decisions/0008-manual-seat-selection.md) | 推荐保证连座，手动选座无需关闭连座开关 |
| [算法基线](docs/testing/seat-recommendation-baseline.md) | 实际环境、规模、生成参数、耗时与原始样本 |
| [第三周进度](docs/progress/week-03.md) | 已实现、已执行证据、核心代码学习检查及第四周范围 |
| [第四周首个闭环评审](docs/design/week-04-order-hold-review.md) | 已确认的并发、幂等、时间、身份、V2 结构与完整事务 |
| [登录与锁座下单导读](docs/development/order-holds.md) | 入口、主流程、数据变化、异常、事务及关键代码 |
| [锁座下单演示](docs/development/order-holds-demo.md) | 固定账号、首次购买、原键重放与独立夹具 |
| [ADR-0009](docs/decisions/0009-serialize-session-inventory-writes.md) | 已确认的场次行锁、锁后最新重验及整组条件更新 |
| [ADR-0010](docs/decisions/0010-idempotent-order-creation.md) | 已确认的下单请求身份、内容冲突与重放不续期 |
| [ADR-0011](docs/decisions/0011-order-cutoff-before-admission-closes.md) | 已确认的入场截止与完整 15 分钟支付期限衔接 |
| [ADR-0012](docs/decisions/0012-order-hold-schema-and-transaction.md) | 订单/锁六表、当前持有者与原子提交边界 |
| [ADR-0013](docs/decisions/0013-demo-opaque-bearer-login.md) | 不透明 Bearer 登录及 Spring Security 权限 |
| [第四周进度](docs/progress/week-04.md) | 登录下单、支付模拟器及支付闭环的实现、验证与理解验收进度 |
| [第四周支付评审](docs/design/week-04-payment-review.md) | 已确认的模拟器、本地支付/票结构、原子结果事务、安全取消与恢复协议 |
| [ADR-0014](docs/decisions/0014-persist-independent-payment-simulator.md) | 已确认的模拟器持久化、独立提交、首次受理截止与关单防复活 |
| [支付模拟器导读](docs/development/payment-simulator.md) | 内部入口、当前事实与通知快照、事务、并发、故障及 25 项数据库测试 |
| [ADR-0015](docs/decisions/0015-local-payment-ticket-cancellation-recovery.md) | 本地支付与票、整组成功/取消事务、订单恢复信息和 60 秒领取协议 |
| [支付闭环代码导读](docs/development/payment-loop.md) | 支付意图、原子结果、库存归属、安全取消与恢复 token 的关键代码 |
| [第四周业务逻辑复习笔记](docs/development/week-04-business-logic-notes.md) | 本次对话的完整场景、事务边界、安全取消、恢复含义及并发保护区别 |
| [支付闭环演示](docs/development/payment-loop-demo.md) | 消费者发起、运营控制与投递、本人票查询、安全取消及手动恢复 |
| [锁座数据库基线](docs/benchmarks/order-holds-baseline.md) | 同场次完整下单服务的规模、并发、串行对照及指标定义；执行证据另列 |
| [第五周首个闭环评审](docs/design/week-05-timeout-recovery-review.md) | 已确认的自动超时/恢复扫描、规则复用、锁内重验与验收场景 |
| [第五周票码评审（Q-07）](docs/design/week-05-ticket-credential-review.md) | 已确认的二维码载荷、真实性、密钥轮换、旧码处理与本人查询方案 |
| [ADR-0017](docs/decisions/0017-hmac-ticket-credentials.md) | 按需 HMAC 票码、旧密钥准确保留上界和紧急撤销生效机制 |
| [本人票码与密钥导读](docs/development/ticket-credentials.md) | 本人文本/PNG 查询、严格验真、密钥初始化/轮换/撤销与演示 |
| [第五周单票退款评审（已确认）](docs/design/week-05-single-ticket-refund-review.md) | 原单/原支付下的单票退款、请求幂等、独立渠道事实、原子回流和恢复方案 |
| [ADR-0018](docs/decisions/0018-single-ticket-refunds-and-durable-recovery.md) | 单票全额退款、三表结构、成功回调及按退款单持久恢复 |
| [单票退款代码与演示导读](docs/development/refund-loop.md) | 入口、原单场景、短事务、失败/通知确认恢复、真实重售与测试依据 |
| [第五周进度](docs/progress/week-05.md) | 三条闭环交付、测试与演示证据、讲解反馈及收尾接续 |
| [ADR-0016](docs/decisions/0016-scan-durable-payment-recovery.md) | 应用内 MySQL 扫描、默认参数、有限批次与持久恢复 |
| [自动扫描导读](docs/development/payment-recovery-scanning.md) | 自动入口、数据变化、事务、故障处理、配置与演示 |
| [第六周已确认方案](docs/design/week-06-checkin-consistency-review.md) | 核销独立审计、原子事务、Q-08 检测与修复边界 |
| [ADR-0019](docs/decisions/0019-atomic-checkin-and-append-only-audit.md) | 每次扫描独立结果、同事务成功审计与履约完成归集 |
| [ADR-0020](docs/decisions/0020-read-only-consistency-reports.md) | 同场次一致快照、只报告差异与既有恢复协议 |
| [核销与一致性代码导读](docs/development/checkin-consistency.md) | 入口、优先级、事务、异常、恢复限制与后续学习材料 |
| [完整履约演示](docs/development/week-06-demo.md) | 固定种子购买、退款回流、同座重售、真实等待核销及只读重跑 |
| [第六周进度](docs/progress/week-06.md) | 工程交付、真实证据、业务学习收尾及第七周接续 |
| [第七周可靠性评审](docs/design/week-07-reliability-review.md) | 锁座及超时替代方案、证据边界与仍开放的 Q-10 |
| [第七周可靠性实验](docs/testing/week-07-reliability.md) | 多用户重叠抢座、支付组合故障、复现入口与原始结果 |
| [第七周进度](docs/progress/week-07.md) | 本轮执行、方案讨论及尚未完成的任务 |

## 当前进度

- [x] MVP 范围和角色用例
- [x] 领域对象及关系
- [x] 核心状态迁移
- [x] 业务流程和竞争边界
- [x] 不变量与测试场景
- [x] 固定种子数据设计
- [x] Q-01 至 Q-03 技术基线确认并记录
- [x] Java 21 / Docker Compose MySQL 环境脚本
- [x] Spring Boot 工程骨架与接口基础测试
- [x] 七表迁移、模板维护与 DRAFT 场次快照
- [x] 固定规则测试厅与可重复的快照演示脚本
- [x] 场次销售启停、票档及可售配置修改
- [x] 246 座主演示数据及重复/差异/续跑验证
- [ ] 第二周负责人代码理解与整周学习验收
- [x] 完整座位图、库存状态与可选择资格分离
- [x] 确定性连座推荐、共用选座规则与 73 项规则/算法测试
- [x] 只读一致性、候选状态变化、真实 API 演示和算法基线
- [x] 第三周核心代码讲解与本轮理解检查
- [x] 固定账号登录、角色权限及订单归属
- [x] 幂等下单、整组锁座、成交快照与个人订单查询
- [x] 持久化支付模拟器、独立渠道事务、查单关单与通知快照重投
- [x] 真并发、微秒截止边界、数据库约束与部分写入回滚测试
- [x] 本地支付、原子出票、本人票查询、安全取消及运营恢复代码
- [x] 第四周支付闭环测试、真实演示、应用重启恢复与锁座数据库基线
- [x] 第四周核心业务与事务代码阅读及本轮场景理解检查
- [x] 可购买的纵向闭环（选座→下单→支付→出票 / 安全取消）
- [x] 自动超时/恢复扫描、锁内资格重验及后台重启恢复验证
- [x] 第五周自动扫描本轮业务场景讲解与理解反馈记录
- [ ] 第五周自动扫描核心代码理解检查
- [x] Q-07 票码方案、密钥准确保留上界与紧急撤销规则确认
- [x] 本人 HMAC 凭证/二维码查询、完整回归与真实换钥演示
- [x] 第五周票码本轮场景讲解与理解反馈记录（未逐行阅读代码）
- [x] 单票全额退款、原子失效回流、失败/重启恢复及真实重售验证
- [x] 第五周退款本轮场景讲解与理解反馈记录
- [ ] 第五周退款核心代码阅读与剩余边界独立复述
- [x] 第六周正式核销、逐次审计查询与订单履约完成汇总代码
- [x] Q-08 只读一致性报告与定期扫描代码
- [x] 第六周422项完整回归、真实 HTTP 履约演示与重启后审计验证
- [x] 第六周本轮业务闭环、典型问题讲解与收尾（2026-10-19，负责人要求）
- [ ] 第六周核心代码深入与剩余理解边界（负责人选择后续有时间再进行）
- [x] 第七周多轮多用户重叠抢座与支付通知/恢复组合故障定向验证
- [x] 第七周首个小闭环完整回归 426 项通过（2026-10-21）
- [ ] 第七周 Q-10 替代方案讨论与最终架构确认
- [ ] 第七周独立进程中断实验、基线复测及整周收尾

## 运行方式

在项目根目录运行以下 PowerShell 命令。需要已启动的 Docker Desktop 和 JDK 21；仓库自带 Wrapper 获取固定 Maven 3.9.9，无需另外安装 Maven。已有 JDK 21 可用 use-java21.ps1 的 -JdkHome 指定目录，否则运行 setup 脚本。

~~~powershell
.\scripts\setup-java21.ps1
.\scripts\use-java21.ps1
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\check-infra.ps1 -IncludeTest
.\scripts\test.ps1 -Integration
~~~

首次启动应用前执行 `.\scripts\ticket-keys.ps1 -Action Init`，将独立随机密钥与生命周期记录保存到被忽略的 `.local`；已有密钥时不重复初始化。随后执行 `.\scripts\run-app.ps1`，重启复用原密钥。缺失配置会拒绝启动，不能用临时密钥替代。轮换与紧急撤销步骤见[本人票码与密钥导读](docs/development/ticket-credentials.md)。测试 profile 使用独立公开测试夹具，不读取开发密钥。

应用启动后，在另一个窗口执行：

~~~powershell
.\scripts\seed-week2.ps1
.\scripts\demo-snapshot.ps1
.\scripts\demo-session-sales.ps1
.\scripts\seed-galaxy.ps1
.\scripts\demo-seat-recommendations.ps1
.\scripts\demo-order-holds.ps1
.\scripts\demo-payments.ps1
.\scripts\demo-payment-recovery.ps1
.\scripts\demo-ticket-credentials.ps1
.\scripts\demo-refunds.ps1
.\scripts\demo-checkins.ps1 -Namespace W6A
~~~

第三周查询入口为 `GET /api/sessions/{id}/seat-map` 和 `GET /api/sessions/{id}/recommendations?quantity=2&limit=5`。候选不占座，最终下单仍需重新校验。演示使用独立 W3 数据，可重复执行。编译后运行 `.\scripts\benchmark-seat-recommendations.ps1` 可重做单线程纯算法基线；它不访问数据库，指标不代表 HTTP 或锁座吞吐。详见[导读](docs/development/seat-recommendations.md)。

第四周支付演示使用独立 W4P 场次，保存原请求键、支付编号和票号，可用相同命名空间重跑；接口与观察结果见[演示导读](docs/development/payment-loop-demo.md)。消费者通过 `POST /api/orders/{id}/payments` 发起，使用 `GET /api/orders/{id}/tickets` 查询本人票；渠道结果控制、通知投递及主动恢复均需 OPERATOR。

第五周默认自动扫描到期与未决订单，每轮结束 1 秒后再运行，单批 50 个候选，可通过 PAYMENT_RECOVERY_ENABLED、PAYMENT_RECOVERY_FIXED_DELAY_MS 和 PAYMENT_RECOVERY_BATCH_SIZE 配置。运行 `.\scripts\demo-payment-recovery.ps1` 演示不投通知、仅由后台查单出票；`-Scenario Timeout` 分两次命令观察原 15 分钟期限前后的自动取消。配置、失败恢复与实际验收见[扫描导读](docs/development/payment-recovery-scanning.md)。暂停后台可稳定复现第四周未出票暂停点；启用时普通支付演示允许后台先行出票。

退款入口为 `POST /api/orders/{orderId}/tickets/{ticketId}/refunds`，JSON 只提交 requestKey，金额取原订单项成交价。独立退款扫描使用 REFUND_RECOVERY_ENABLED、REFUND_RECOVERY_FIXED_DELAY_MS 和 REFUND_RECOVERY_BATCH_SIZE，默认同样为开启、1 秒及 50。`demo-refunds.ps1` 保存原单/支付/票/退款身份，验证 consumer2 真实回购；`-PauseAfterChannelSuccess` 需先关闭退款扫描，重启启用后先观察后台自动完成，再续跑原命名空间。接口、故障与演示详见[退款导读](docs/development/refund-loop.md)。

锁座实验通过 `.\scripts\benchmark-order-holds.ps1` 在隔离测试库生成独立夹具，保留订单，不删除历史数据。实验测量完整 `OrderService.create`，以并发度 1 为串行对照，包含数据库连接/行锁等待和事务提交；不代表 HTTP、支付或生产容量。参数、环境与输出见[实验说明](docs/benchmarks/order-holds-baseline.md)，运行时不要同时执行会清理测试数据的集成回归。

主演示种子默认 DEMO 命名空间；独立验证可执行 `scripts/verify-galaxy-seed.ps1`，默认保留 VERIFY 和 VERIFY-R 两套夹具，不清库。时间与恢复方式见[主演示数据导读](docs/development/galaxy-seed.md)。

开发数据库端口为 3307，测试数据库端口为 3308；数据库及演示账号密码由脚本生成在被忽略的 .env。管理脚本自动登录 operator1；消费者下单使用 consumer1 / consumer2，密码来自 DEMO_PASSWORD。登录令牌有效 2 小时，Swagger Authorize 可输入 token 原值。详细说明见[本地环境](docs/development/local-environment.md)与[下单演示](docs/development/order-holds-demo.md)。

启动后访问 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。运行与代码说明见[后端骨架导读](docs/development/backend-skeleton.md)。

开发库与测试库均由 Flyway 执行 V1 场馆场次、V2 身份/订单/锁座、V3 模拟支付、V4 本地支付/票、V5 退款及 V6 核销审计迁移。固定种子重跑会核对和复用已有数据；检测到改动时保留数据并报错。下单与支付原键重放不续期；独立购买使用新命名空间，不删除历史交易。Swagger 已提供消费者支付、票码、取消与退款，工作人员核销及审计，以及运营渠道控制、通知投递、恢复和一致性报告入口。

第六周 `POST /api/checkins` 需 CHECKER，只有 `data.result=SUCCESS` 才能放行；业务拒绝返回明确结果并追加安全审计。`GET /api/checkins` 允许 CHECKER / OPERATOR，支持场次、票、时间和游标分页。一致性报告 `GET /api/operations/sessions/{id}/consistency` 需 OPERATOR；默认后台每轮结束 60 秒、每批 10 场次，通过 CONSISTENCY_ENABLED / CONSISTENCY_FIXED_DELAY_MS / CONSISTENCY_BATCH_SIZE 配置。检测器只报告，恢复继续走原支付/退款协议。完整演示脚本约真实等待 2 分钟进入入场窗口，重跑默认只读验证；不改应用 Clock 或原支付期限。具体限制与恢复见[导读](docs/development/checkin-consistency.md)。

第七周执行 `.\scripts\verify-week7-reliability.ps1` 可单独重做两个测试类、四项可靠性实验，包括二十轮/二百四十次重叠抢座及三项支付组合故障。它清理专用测试库 `ticketing_test`，须与完整回归和基线实验串行执行；原始结果保存在 `.local`，提交证据见[实验报告](docs/testing/week-07-reliability.md)。工作线程数不等于数据库同时运行事务数，异常与 Clock 注入不代表真实进程终止，没有吞吐容量结论。

## MVP 之外

真实支付、复杂退改签、候补预付款、自动扣款、门票转赠、动态定价、分销、多租户、反黄牛、复杂个性化推荐和离线核销不属于首版范围。
