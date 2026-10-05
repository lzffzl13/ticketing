# 座位票务与现场履约系统

一个以“具有唯一身份和空间关系的场次座位”为核心的票务项目，覆盖场馆模板、场次快照、选座与连座推荐、锁座下单、模拟支付、电子票、单票退票、座位回流和现场核销。

项目当前已完成第二周工程骨架、场馆模板 → 场次独立快照、首次开售前配置修改、销售启停和票档开关，以及 246 座主演示数据。已提供 Java 21、Docker Compose MySQL、七表迁移、维护与查询 API、固定种子和事务/并发测试；负责人代码理解与整周学习验收仍待进行。

## 项目目标

- 完成一条可运行、可测试、可演示的购票与履约闭环。
- 在并发、超时、重复请求和回调乱序下保持关键业务不变量。
- 用可重复的测试和实验解释方案选择，不虚构生产数据或性能结论。
- 保持模块化单体和 MySQL 事实来源，只有验证收益后才增加 Redis、消息队列等组件。

## 核心边界

- 场馆座位模板与场次座位快照分离。
- 订单、支付、座位、电子票和退票分别建模状态。
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
~~~

主演示种子默认 DEMO 命名空间；独立验证可执行 `scripts/verify-galaxy-seed.ps1`，默认保留 VERIFY 和 VERIFY-R 两套夹具，不清库。时间与恢复方式见[主演示数据导读](docs/development/galaxy-seed.md)。

开发数据库端口为 3307，测试数据库端口为 3308；凭据由脚本生成在被忽略的 .env。详细说明见[本地环境](docs/development/local-environment.md)。

启动后访问 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)。运行与代码说明见[后端骨架导读](docs/development/backend-skeleton.md)。

开发库与测试库均由 Flyway 执行七表迁移。固定种子重跑会核对和复用已有数据；检测到改动时保留数据并报错。详细接口、代码主流程和演示说明见[场次快照导读](docs/development/session-snapshots.md)及[场次销售导读](docs/development/session-sales.md)。

## MVP 之外

真实支付、复杂退改签、候补预付款、自动扣款、门票转赠、动态定价、分销、多租户、反黄牛、复杂个性化推荐和离线核销不属于首版范围。
