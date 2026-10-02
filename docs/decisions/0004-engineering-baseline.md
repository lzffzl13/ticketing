# ADR-0004：第二周工程与本地运行基线

- 状态：已接受，用户于本对话确认
- 日期：2026-10-02
- 解决问题：Q-01、Q-02、Q-03

## 背景与不变量

第二周要完成“场馆模板 → 创建场次 → 独立座位快照 → 快照隔离测试”。工程应便于负责人理解核心代码，并能在 Windows 上重复启动、验证和演示。

已有业务约束继续生效：MySQL 是事实来源；模板修改不能污染已有场次；创建场次、票档和全部场次座位应整体成功或失败；同一场次不能重复生成两套有效座位。

## 备选方案与取舍

| 问题 | 备选方案 | 选择及理由 |
| --- | --- | --- |
| JDK | 沿用本机 Java 17；采用 Java 21 | 用户选择 Java 21；与 Boot 4.1.1 兼容，不因本机已有 17 限制项目版本 |
| 构建 | Maven；Gradle | Maven 3.9.9，使用已生成的固定版本 Maven Wrapper |
| Web 框架 | Boot 3.x；Boot 4.x | Spring Boot 4.1.1；使用对应官方文档，旧教程配置需核对后使用 |
| 持久层 | JdbcTemplate；MyBatis；JPA | MyBatis Starter 4.0.0，显式 SQL 有利于解释条件更新和事务；接受 Mapper 与映射维护成本 |
| 模块 | 全局技术分层；业务包；多个 Maven 子模块 | 单 Maven 工程内按业务分包，先建立职责与依赖约束，避免额外构建复杂度 |
| 迁移 | 手工 SQL；Flyway；Liquibase | Flyway，版本化 SQL 与执行历史容易评审、复现 |
| 本地依赖 | 本机 MySQL；Docker Compose | 从第二周使用 Compose 运行 MySQL，Java 应用在本机 IDE 运行；接受 Docker 的启动与内存成本 |

## 决策

### 版本与运行方式

- Java 21；本次项目内准备 Eclipse Temurin 21.0.12.1+1，下载来源及 SHA-256 保存在 scripts/java21-release.json。
- Maven 3.9.9、Spring Boot 4.1.1、MyBatis Starter 4.0.0。
- MySQL 8.4 系列；Compose 固定官方 mysql:8.4.11 镜像。
- Flyway 和 MySQL Connector/J 采用 Boot 管理的版本；MySQL 迁移需要 flyway-mysql 模块。
- API 文档使用 springdoc-openapi 3.1.1；已验证与 Boot 4.1.1 一起构建和提供实际端点。
- Java 21 编译、Maven 运行和 IDE JDK 保持一致；新增语言或线程特性按需要使用，不预设性能收益。

### 模块边界

| 业务包 | 职责 |
| --- | --- |
| venue | 场馆、区域、物理座位模板 |
| catalog | 演出、场次、票档、创建场次流程编排 |
| inventory | 场次座位快照，之后扩展库存查询和锁座 |
| common | 基础响应、异常、公共配置；不承担业务编排 |

模块内部使用 Controller、Service、Mapper 和模型等必要结构。跨模块调用明确的服务接口，不直接调用其他模块的 Mapper。catalog 编排创建场次，调用 venue 提供的模板数据和 inventory 的快照写入能力；后两者不反向依赖 catalog 内部实现。

创建场次、票档和座位快照的写入使用同一数据源、同一本地事务，禁止下层另开独立提交事务。具体表结构、约束、模板读取的一致性和防重复生成方案在实现前另行评审。

交易支付、票务履约和核销在对应周增加业务包，当前不提前创建空模块。

### 数据库与迁移

- 开发实例：127.0.0.1:3307，库及应用账号 ticketing。
- 测试实例：127.0.0.1:3308，库及应用账号 ticketing_test；仅在启用 test profile 时启动。
- 两个实例使用独立账号、密码和数据卷，测试仅访问测试实例。
- 首次启动脚本生成被 Git 忽略的 .env；模板不包含实际密码。
- Flyway 是业务结构的唯一迁移入口，SQL 位于 src/main/resources/db/migration；不混用自动建表和手工 schema.sql。
- 数据卷保存数据库状态。停止容器与删除数据分开处理，日常启动脚本不删除卷。
- MySQL DDL 可能隐式提交。迁移失败时检查实际结构与历史，确认差异后补偿或重建明确的测试库；不假设整批 DDL 都已回滚，不自动 repair。

## 验证与边界

本次已验证 Java 21/Maven、Compose、Boot/MyBatis/Flyway 实际启动及接口基础测试，证据见第二周进度记录。当前只有 Flyway 迁移历史表，没有业务迁移或快照隔离测试结果。

Docker 提供运行依赖的能力。Redis、MQ 是否引入仍依赖业务收益与验证；本次不改变 MVP 范围。

## 参考

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- [MyBatis Starter 版本对应](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)
- [Spring Boot 数据库迁移](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [MySQL 官方镜像](https://hub.docker.com/_/mysql)
- [MySQL 隐式提交规则](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html)
