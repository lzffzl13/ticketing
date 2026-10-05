# 后端骨架：运行与代码导读

本文讲解可构建、可启动、可验证的工程基础。后续已完成场馆模板、独立快照、配置和销售启停，业务入口见[场次快照导读](session-snapshots.md)和[场次销售导读](session-sales.md)；订单与交易尚未实现。

## 1. 运行与测试

在项目根目录运行：

~~~powershell
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\test.ps1 -Integration
.\scripts\run-app.ps1
~~~

普通接口测试使用 test.ps1，不加 -Integration，不要求数据库运行。集成模式执行接口测试、打包及真实 MySQL / HTTP 启动测试。

run-app 读取本地 .env，将开发库连接参数传给 Java 进程；密码不写入 application.yaml。脚本验证 Java 21，使用固定 Maven 3.9.9 的 Wrapper，依赖缓存保存在被忽略的 .local/m2。前台启动后用 Ctrl+C 停止；已有 IDE 配置也可直接运行主类。

| 地址 | 验证什么 |
| --- | --- |
| http://127.0.0.1:8080/api/system/ping | 应用能够处理 HTTP 请求及统一响应 |
| http://127.0.0.1:8080/actuator/health | 包括数据库连接在内的应用健康状态 |
| http://127.0.0.1:8080/v3/api-docs | 机器可读的 OpenAPI 文档 |
| http://127.0.0.1:8080/swagger-ui/index.html | 浏览和调用接口 |

## 2. 正常请求如何经过代码

1. [TicketingApplication](../../src/main/java/com/ticketing/TicketingApplication.java) 启动 Spring Boot，扫描 com.ticketing 下的组件。
2. [RequestIdFilter](../../src/main/java/com/ticketing/common/web/RequestIdFilter.java) 为请求生成编号，放入日志 MDC 和 X-Request-Id 响应头。
3. [SystemController](../../src/main/java/com/ticketing/common/web/SystemController.java) 处理 GET /api/system/ping。
4. [ApiResponse](../../src/main/java/com/ticketing/common/web/ApiResponse.java) 保存结果和当前请求编号，由框架序列化为 JSON。
5. 过滤器 finally 清理 MDC，避免服务器线程处理下一请求时沿用旧编号。

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

[ApiExceptionHandler](../../src/main/java/com/ticketing/common/web/ApiExceptionHandler.java) 集中处理失败：

| 情况 | HTTP 状态与响应 |
| --- | --- |
| DTO 的 @Valid 校验失败 | 400，INVALID_REQUEST，data 提供字段问题 |
| JSON 格式错误 | 400，INVALID_REQUEST |
| 资源或路径不存在 | 404，NOT_FOUND |
| BusinessException | 保留调用方指定的 4xx 状态与业务错误码 |
| 未知异常 | 500，INTERNAL_ERROR；详情记录在带请求编号的日志里 |
| 框架产生的服务端错误 | 保留原 5xx 状态，INTERNAL_ERROR |

参数校验和异常分支由仅存在于测试源码中的 FixtureController 验证，/test 路径不会出现在打包后的应用中。后续业务 Controller 复用这套基础约定。

## 4. 数据与迁移

[application.yaml](../../src/main/resources/application.yaml) 配置开发数据库、连接池、Flyway 和日志；测试配置固定使用独立测试实例。MyBatis 的真实查询通过测试 Mapper 验证，生产代码尚无业务 Mapper。

Flyway 在启动阶段连接 MySQL 并维护 flyway_schema_history。最初骨架验证时没有业务迁移；后续七表评审完成，现已有 V1 业务迁移，并在开发和测试库成功执行。当前启动日志应显示校验 1 个迁移、数据库版本 v1。

后续创建场次的本地事务按照 ADR-0004 实现。本阶段不预先决定外键、索引、模板读取隔离或防重复生成策略。

## 5. 验证证据位置

- 接口基础契约：src/test/java/com/ticketing/common/web/ApiContractTest.java。
- MyBatis、Flyway 和真实 HTTP 启动：src/test/java/com/ticketing/support/ApplicationStartupIT.java。
- 接口测试报告：target/surefire-reports；集成测试报告：target/failsafe-reports。
- 本轮构建日志：.local/build.log；临时打包启动验证日志：.local/app-stdout.log。

详细本轮结果在 docs/progress/week-02.md。以上检查不等于业务状态测试或快照隔离测试通过。
