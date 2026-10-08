# 后端骨架：运行与代码导读

本文讲解可构建、可启动、可验证的工程基础。当前已有场馆模板、独立快照、配置和销售启停、座位图与连座推荐，以及固定账号登录、幂等下单和整组锁座。业务入口见[场次快照](session-snapshots.md)、[场次销售](session-sales.md)、[座位推荐](seat-recommendations.md)及[登录与订单导读](order-holds.md)。支付、出票、取消和自动释放仍待后续闭环。

## 1. 运行与测试

在项目根目录运行：

~~~powershell
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\test.ps1 -Integration
.\scripts\run-app.ps1
~~~

普通接口测试使用 test.ps1，不加 -Integration，不要求数据库运行。集成模式执行接口测试、打包及真实 MySQL / HTTP 启动测试。

run-app 读取本地 .env，将开发库连接参数和 DEMO_PASSWORD 传给 Java 进程；application.yaml 只引用环境变量。脚本验证 Java 21，使用固定 Maven 3.9.9 的 Wrapper，依赖缓存保存在被忽略的 .local/m2。前台启动后用 Ctrl+C 停止；IDE 直接运行主类时同样需要数据库环境变量，DEMO_PASSWORD 用于固定账号启动种子，详见[本地环境](local-environment.md)。

| 地址 | 验证什么 |
| --- | --- |
| http://127.0.0.1:8080/api/system/ping | 应用能够处理 HTTP 请求及统一响应 |
| http://127.0.0.1:8080/actuator/health | 包括数据库连接在内的应用健康状态 |
| http://127.0.0.1:8080/v3/api-docs | 机器可读的 OpenAPI 文档 |
| http://127.0.0.1:8080/swagger-ui/index.html | 浏览和调用接口 |

这些 GET 地址允许匿名访问。Swagger 的 Authorize 输入登录返回的 token 原值；管理写入口使用 operator1，个人订单使用 consumer1 或 consumer2。旧管理种子/演示脚本自动先登录，第四周演示见[脚本说明](order-holds-demo.md)。

## 2. 正常请求如何经过代码

1. [TicketingApplication](../../src/main/java/com/ticketing/TicketingApplication.java) 启动 Spring Boot，扫描 com.ticketing 下的组件。
2. [RequestIdFilter](../../src/main/java/com/ticketing/common/web/RequestIdFilter.java) 为请求生成编号，放入日志 MDC 和 X-Request-Id 响应头。
3. [SecurityConfiguration](../../src/main/java/com/ticketing/common/config/SecurityConfiguration.java) 的过滤链决定公开路由和角色权限；携带 Bearer 时由 [BearerTokenFilter](../../src/main/java/com/ticketing/identity/BearerTokenFilter.java) 校验数据库令牌及账号，写入认证上下文。
4. [SystemController](../../src/main/java/com/ticketing/common/web/SystemController.java) 处理公开的 GET /api/system/ping。
5. [ApiResponse](../../src/main/java/com/ticketing/common/web/ApiResponse.java) 保存结果和当前请求编号，由框架序列化为 JSON。
6. RequestIdFilter 的 finally 清理 MDC，避免服务器线程处理下一请求时沿用旧编号；认证失败也保留本次请求编号。

~~~json
{
  "code": "OK",
  "message": "成功",
  "data": {"status": "UP"},
  "requestId": "本次请求的 UUID"
}
~~~

该接口没有数据库写入，也没有业务事务。它不能证明选座、锁座或订单功能完成。

## 3. 异常与参数校验

[ApiExceptionHandler](../../src/main/java/com/ticketing/common/web/ApiExceptionHandler.java) 处理控制器与业务失败；过滤链的认证/权限失败由 [SecurityErrors](../../src/main/java/com/ticketing/identity/SecurityErrors.java) 使用同一响应结构处理：

| 情况 | HTTP 状态与响应 |
| --- | --- |
| DTO 的 @Valid 校验失败 | 400，INVALID_REQUEST，data 提供字段问题 |
| JSON 格式错误 | 400，INVALID_REQUEST |
| 已开放路由中的资源不存在 | 404，NOT_FOUND |
| 未登录、无效或过期令牌 | 401，UNAUTHORIZED |
| 角色不匹配或已登录后访问未开放路由 | 403，FORBIDDEN |
| BusinessException | 保留调用方指定的 4xx 状态与业务错误码 |
| 未知异常 | 500，INTERNAL_ERROR；详情记录在带请求编号的日志里 |
| 框架产生的服务端错误 | 保留原 5xx 状态，INTERNAL_ERROR |

未知路由默认由安全链拒绝，匿名访问为 401，已登录访问为 403；不能一概按 404 处理。参数校验和基础异常分支由仅存在于测试源码中的 FixtureController 验证，该测试控制器不会出现在打包后的应用中。现有业务 Controller 复用这套基础约定，身份和角色使用实际过滤链验证。

## 4. 数据与迁移

[application.yaml](../../src/main/resources/application.yaml) 配置开发数据库、连接池、Flyway、MyBatis 和日志；测试配置固定使用独立测试实例。生产业务已有 venue、catalog、inventory、identity 与 trade 各自的 Mapper，跨模块通过服务协作。common 提供响应、请求编号、时钟、UTC 时间映射和安全配置。

Flyway 在启动阶段连接 MySQL 并维护 flyway_schema_history。最初骨架验证时没有业务迁移；第二周七表评审完成后，V1 在开发和测试库执行。本轮新增 V2 身份、订单与锁座六表及当前持有者约束，启动会校验两个迁移并将数据库升级到 v2；迁移是否成功以实际启动日志为准。

模板快照与管理事务见 ADR-0005/0006；第四周表结构和下单事务见 ADR-0012，认证见 ADR-0013。后续结构变化新增迁移，保留已执行版本，不通过修改 V1 来绕过约束。

## 5. 验证证据位置

- 接口基础契约：src/test/java/com/ticketing/common/web/ApiContractTest.java。
- MyBatis、Flyway 和真实 HTTP 启动：src/test/java/com/ticketing/support/ApplicationStartupIT.java。
- 接口测试报告：target/surefire-reports；集成测试报告：target/failsafe-reports。
- 骨架阶段历史构建日志：.local/build.log；临时打包启动验证日志：.local/app-stdout.log（本机保留时可查看）。

骨架阶段历史结果见[第二周进度](../progress/week-02.md)，当前订单、身份及真实演示证据见[第四周进度](../progress/week-04.md)。基础契约测试通过不能替代业务状态、并发和事务测试；各项执行情况以相应周报为准。
