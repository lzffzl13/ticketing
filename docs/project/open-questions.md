# 开放问题清单

## 1. 使用方式

本清单只保留尚未决定、会影响后续设计或实现的问题。问题解决后应更新相关业务文档或 ADR，并从“开放”改为“已解决”，不只在聊天中留下结论。

优先级含义：`P0` 阻塞下一阶段，`P1` 必须在对应模块开发前解决，`P2` 可以等待基线或后续范围确认。

## 2. 当前开放问题

| 编号 | 优先级 | 问题 | 为什么需要决定 | 最晚决策时间 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q-06 | P1 | 支付与超时任务的重试、退避和人工介入条件 | 持续未决需可检测并介入，未知时不能错误释放；ADR-0001 要求交易支付模块实现前明确 | 第四周支付/取消恢复机制开发前 | 开放；第五周沿用并验证超时扫描 |
| Q-07 | P1 | 电子票二维码载荷、签名和密钥轮换 | 决定票码验证、泄露风险和旧密钥兼容 | 第五周二维码凭证开发前 | 开放；第四周最小票记录不作为核销凭证 |
| Q-08 | P1 | 首版对账只报告什么、自动修复什么 | 自动修复错误可能扩大损害，需要先定义可信规则 | 第六周一致性检查前 | 开放 |
| Q-09 | P2 | 候补支付期限、触发方式和回流事务边界 | 候补是条件增强，只有达到启动门槛才实施 | 候补启动前 | 开放 |
| Q-10 | P2 | 是否引入 Redis、消息队列或延迟队列 | 必须依据数据库基线和故障边界决定 | 第七周方案定稿 | 等待基线 |

## 3. 已解决问题索引

| 主题 | 结论位置 |
| --- | --- |
| Q-01 已解决：Java 21、Boot 4.1.1、Maven 3.9.9、MyBatis | [ADR-0004](../decisions/0004-engineering-baseline.md) |
| Q-02 已解决：单 Maven 工程，按业务分包与约束依赖方向 | [ADR-0004](../decisions/0004-engineering-baseline.md) |
| Q-03 已解决：Flyway、Docker Compose MySQL、开发与测试实例隔离 | [ADR-0004](../decisions/0004-engineering-baseline.md) |
| 第二周七表结构、独立快照、创建事务与重复代码处理 | [ADR-0005](../decisions/0005-session-seat-snapshots.md) |
| 场次配置与销售启停的行锁、冻结和重复目标 | [ADR-0006](../decisions/0006-serialize-session-management.md) |
| 第三周只读一致性、实时扫描和确定性中心排序 | [ADR-0007](../decisions/0007-read-only-seat-recommendations.md) |
| 手动选座不强制连座，系统推荐保证连座（2026-10-08 调整） | [ADR-0008](../decisions/0008-manual-seat-selection.md) |
| Q-04 已解决：MySQL 当前持有者、场次行锁、锁后重验与完整事务 | [ADR-0009](../decisions/0009-serialize-session-inventory-writes.md)、[ADR-0012](../decisions/0012-order-hold-schema-and-transaction.md) |
| Q-05 已解决：BCrypt 与 MySQL 摘要不透明 Bearer 令牌，Spring Security 鉴权 | [ADR-0013](../decisions/0013-demo-opaque-bearer-login.md) |
| 下单幂等：消费者与请求编号绑定原订单，重放不续期 | [ADR-0010](../decisions/0010-idempotent-order-creation.md) |
| 模拟器持久化、独立提交、首次受理截止、关单防复活与最低两表 | [ADR-0014](../decisions/0014-persist-independent-payment-simulator.md)；不代表 Q-06 已解决 |
| Q-11 已解决：距入场截止至少 15 分钟才可新下单，保留完整期限 | [ADR-0011](../decisions/0011-order-cutoff-before-admission-closes.md) |
| 支付截止与座位释放 | `docs/decisions/0001-resolve-payment-before-releasing-seats.md` |
| 孤座规则 | `docs/decisions/0002-forbid-creating-new-orphan-seats.md` |
| 候补支付策略 | `docs/decisions/0003-waitlist-lock-then-pay.md` |
| 自由选座、单票退票、退票截止、身份和核销窗口 | `docs/requirements/core-use-cases.md` |
