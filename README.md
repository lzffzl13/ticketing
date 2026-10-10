# 座位票务与现场履约系统

一个以“具有唯一身份和空间关系的场次座位”为核心的票务项目，覆盖场馆模板、场次快照、选座与连座推荐、锁座下单、模拟支付、电子票、单票退票、座位回流和现场核销。

项目当前已完成第四周第一个闭环：固定账号登录、角色鉴权、幂等创建待支付订单、整组锁座及本人订单查询。2026-10-08 完整自动化 165 项通过，其中新增 43 项身份与交易测试。已有场次独立快照、销售启停、座位图、确定性推荐和 246 座主演示数据；第三周算法基线见对应文档。

首个闭环全部方案已获负责人确认，Q-04 / Q-05 / Q-11 已解决。2026-10-10 新增已确认的持久化支付模拟器内部服务：独立提交、查单/关单、成功/失败控制及通知快照重投，25 项新增数据库测试和完整 190 项回归通过。消费者订单支付、出票、安全取消和自动释放尚未接入；15 分钟到达不会自行释放座位。第二周整周学习验收和本轮关键代码理解检查仍单列待完成。

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
| [第四周进度](docs/progress/week-04.md) | 首个闭环实现、165 项验证、演示与后续范围 |
| [第四周支付评审](docs/design/week-04-payment-review.md) | 已确认的模拟器与两表，后续待确认的支付/票结构、结果事务、安全取消与恢复候选 |
| [ADR-0014](docs/decisions/0014-persist-independent-payment-simulator.md) | 已确认的模拟器持久化、独立提交、首次受理截止与关单防复活 |
| [支付模拟器导读](docs/development/payment-simulator.md) | 内部入口、当前事实与通知快照、事务、并发、故障及 25 项数据库测试 |

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
- [ ] 第四周首个闭环核心代码理解检查
- [ ] 可运行的纵向业务闭环
- [ ] 交易履约状态自动化测试与完整端到端演示脚本

## 运行方式

在项目根目录运行以下 PowerShell 命令。需要已启动的 Docker Desktop 和 JDK 21；仓库自带 Wrapper 获取固定 Maven 3.9.9，无需另外安装 Maven。已有 JDK 21 可用 use-java21.ps1 的 -JdkHome 指定目录，否则运行 setup 脚本。

~~~powershell
.\scripts\setup-java21.ps1
.\scripts\use-java21.ps1
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\check-infra.ps1 -IncludeTest
.\scripts\test.ps1 -Integration
.\scripts\run-app.ps1
~~~

应用启动后，在另一个窗口执行：

~~~powershell
.\scripts\seed-week2.ps1
.\scripts\demo-snapshot.ps1
.\scripts\demo-session-sales.ps1
.\scripts\seed-galaxy.ps1
.\scripts\demo-seat-recommendations.ps1
.\scripts\demo-order-holds.ps1
~~~

第三周查询入口为 `GET /api/sessions/{id}/seat-map` 和 `GET /api/sessions/{id}/recommendations?quantity=2&limit=5`。候选不占座，最终下单仍需重新校验。演示使用独立 W3 数据，可重复执行。编译后运行 `.\scripts\benchmark-seat-recommendations.ps1` 可重做单线程纯算法基线；它不访问数据库，指标不代表 HTTP 或锁座吞吐。详见[导读](docs/development/seat-recommendations.md)。

主演示种子默认 DEMO 命名空间；独立验证可执行 `scripts/verify-galaxy-seed.ps1`，默认保留 VERIFY 和 VERIFY-R 两套夹具，不清库。时间与恢复方式见[主演示数据导读](docs/development/galaxy-seed.md)。

开发数据库端口为 3307，测试数据库端口为 3308；数据库及演示账号密码由脚本生成在被忽略的 .env。管理脚本自动登录 operator1；消费者下单使用 consumer1 / consumer2，密码来自 DEMO_PASSWORD。登录令牌有效 2 小时，Swagger Authorize 可输入 token 原值。详细说明见[本地环境](docs/development/local-environment.md)与[下单演示](docs/development/order-holds-demo.md)。

启动后访问 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。运行与代码说明见[后端骨架导读](docs/development/backend-skeleton.md)。

开发库与测试库均由 Flyway 执行 V1 七表、V2 身份/订单/锁座六表和 V3 模拟渠道交易/通知两表迁移。固定种子重跑会核对和复用已有数据；检测到改动时保留数据并报错。新下单演示可重放同一订单且不续期；独立购买使用新的 W4 命名空间，不删除历史交易。模拟器当前通过内部服务及隔离测试验证，Swagger 暂无消费者支付或模拟器控制路由。

## MVP 之外

真实支付、复杂退改签、候补预付款、自动扣款、门票转赠、动态定价、分销、多租户、反黄牛、复杂个性化推荐和离线核销不属于首版范围。
