# ADR-0013：固定账号使用 MySQL 摘要不透明 Bearer 令牌

- 状态：已接受，2026-10-08 负责人理解 JWT / Session 比较后确认方案并授权实施
- 范围：Q-05，首版演示身份和权限

## 问题与取舍

后端必须可信地识别购买者，拒绝消费者修改管理数据或读取别人的订单。只接受 customerId、X-User-Id 或前端角色不构成认证。

比较静态令牌、随机令牌服务端状态与 JWT 后，选择 BCrypt 验证固定种子账号密码，生成 32 字节安全随机字符串，以 Authorization: Bearer 携带。数据库只保存 SHA-256 的 CHAR(64) 十六进制摘要、账号与创建/到期时间，有效期 2 小时。每次验证检查令牌期限及账号启用状态，身份与角色写入 Spring Security 上下文。

这仍是凭编号查服务端状态的会话思路。使用 Bearer 头和 MySQL 表替代 Servlet HttpSession/Cookie 路径；禁用 HttpSession 不代表没有服务端登录状态。JWT 同样可满足需求，但当前没有引入签名密钥及声明管理的明确收益。代价是身份验证查询依赖数据库。

## 权限与范围

固定账号 consumer1、consumer2、operator1、checker1；密码由被忽略的本地 .env 的 DEMO_PASSWORD 提供，迁移和日志不保存明文。账号重复种子复用已有记录，角色/启用/密码偏离则报错并回滚；不静默重置。

登录、健康检查、Swagger 及明确列出的公开只读接口允许匿名。管理写接口需 OPERATOR；下单及个人订单需 CONSUMER。错密码、无效或过期令牌为 401，角色错误为 403，订单不存在或不归属统一 404；未知路径默认拒绝。Bearer 是唯一认证入口，不开放 Cookie 登录，因此不采用基于 Cookie 自动携带凭证的 CSRF 流程。

不开放注册、找回密码、刷新令牌、角色编辑或完整令牌清理任务。CHECKER 的核销入口稍后实现。IdentityServiceTest 与 IdentityIT 验证摘要存储、边界、停用、角色、归属和种子事务，执行证据见第四周周报。
