# 场次配置与销售启停：运行和代码导读

## 本轮能力与入口

已确认规则见 [ADR-0006](../decisions/0006-serialize-session-management.md)，场景和备选见[方案](../design/session-sales-review.md)。本轮管理接口暂用于本机开发与演示；Q-05 登录及角色校验仍未实现。

| 方法与路径 | 输入和行为 |
| --- | --- |
| PUT /api/sessions/{id}/configuration | 时间、forbidNewOrphan、priceTiers 编辑列表、seats 配置列表；返回 Session；两个列表可为空 |
| PUT /api/sessions/{id}/sales-status | {"salesStatus":"ON_SALE"} 或 {"salesStatus":"STOPPED"}；返回 Session |
| PUT /api/sessions/{id}/price-tiers/{tierId}/enabled | {"enabled":false} 或 true；返回 PriceTier |

输入由 [CatalogRequests](../../src/main/java/com/ticketing/catalog/CatalogRequests.java) 校验时间非空、嵌套 ID、价格精度等；[CatalogController](../../src/main/java/com/ticketing/catalog/CatalogController.java) 调用 [SessionManagementService](../../src/main/java/com/ticketing/catalog/SessionManagementService.java)。不提供直接任意写数据库状态的接口。

配置使用本场次的票档 ID 和场次座位 ID，不使用来源模板 ID。票档名称/价格变更同步所有引用它的座位，含 UNAVAILABLE 位；单座重分配使用本次更新后的票档价格。UNAVAILABLE 可保留票档，也可将三项价格全部清空；AVAILABLE 必须有完整本场次价格。票档代码、座位 ID、来源与布局保持不变，不重新读取物理模板。

## 主流程、数据变化与事务

configure 在一个显式 REPEATABLE READ 本地事务中：

1. [CatalogMapper.lockSession](../../src/main/java/com/ticketing/catalog/CatalogMapper.java) 执行 SELECT FOR UPDATE，拿到场次当前状态并持锁。
2. first_opened_at 非空即拒绝；校验时间、票档归属、重复 ID 和可售座位必须有票档。
3. 更新 session 时间/孤座规则；更新已有 price_tier 名称/金额；调用 inventory.refreshTierPrice 同步关联座位快照。
4. 将座位配置和本次解析后的票档名称/金额交给 inventory.configureSeats，校验座位归属/重复、已有状态和成组价格后写入。
5. 正常返回后 Spring 提交；后续失败连同前面已执行的更新一起回滚。

inventory 的写操作使用 MANDATORY 加入外层事务，不独立提交；inventory 不依赖 catalog 的请求或 Mapper。配置不增删七表中的任何业务记录，也不更改其他场次或物理模板。

setSalesStatus 也先锁场次。DRAFT 首次转 ON_SALE 时至少有一个 AVAILABLE 且票档启用的座位，同时写 first_opened_at；ON_SALE 可暂停，STOPPED 可恢复。COALESCE 保留首次时间。相同 ON_SALE/STOPPED 请求直接返回，updated_at 也不变；DRAFT 不能直接暂停或作为设置目标。

setTierEnabled 先锁所属场次，仅更新 price_tier.enabled/updated_at。允许全部停用，不改变 session_seat 的布局、价格或库存状态；恢复销售仍需至少一座可售。已处于 ON_SALE 的重复设置不重新执行开售校验。未来消费者接口必须同时检查场次状态、票档开关和库存状态，本轮没有下单接口。

## 为什么开售和配置要锁同一条记录

仅仅写 @Transactional 不能阻止两个事务都先读到 DRAFT。一方开售后，另一方继续改价，会违反冻结规则。

本轮三个管理写入口都先锁 session 行，并在取得锁后读取最新状态：配置先拿锁则先完整提交新价格，随后开售；开售先拿锁则写入首次时间并提交，配置等待后读到已开售，返回冻结冲突。同场次管理写操作串行，不同场次各锁各的记录。普通快照查询不需要这一行锁，创建时读物理模板的隔离策略没有变化。

锁一直保持到事务结束，不能在校验后提前释放，也不能某个入口省略锁；未来新增管理写操作需要沿用这个协议。数据库 CHECK 仍保障行内合法性，不能独自表达“曾开售后不可改价”的跨步骤规则。

## 异常分支

| 情况 | 响应 / 结果 |
| --- | --- |
| 字段、价格精度、嵌套空项错误 | 400 INVALID_REQUEST |
| 时间、票档归属/重复、可售位缺票档 | 400 INVALID_SESSION_CONFIGURATION |
| 座位归属/重复、不完整价格 | 400 INVALID_SEAT_CONFIGURATION；之前的配置写入也回滚 |
| 场次或本场次票档不存在 | 404 NOT_FOUND |
| 曾开售后编辑配置（含暂停状态） | 409 SESSION_CONFIGURATION_FROZEN |
| 非法销售迁移 | 409 INVALID_SALES_TRANSITION |
| 没有可售且票档启用的座位开售/恢复 | 409 SESSION_NOT_SALEABLE |
| 请求涉及 HELD/SOLD 座位 | 409 SEAT_NOT_CONFIGURABLE；没有提供绕过库存状态的管理操作 |
| 写入中途未知异常 | 整个配置回滚，500 INTERNAL_ERROR |

## 演示与验证

应用启动后执行：

~~~powershell
.\scripts\demo-session-sales.ps1
~~~

可指定 -BaseUrl 与 -Namespace。默认 W2-SALES 的 DRAFT 种子不变，另建 SESSION-MANAGED-W2-SALES：A 档改为 420 元、为 C1 补票档并启用，56 座中 55 可售；开售、重复设置、停用/启用票档、暂停、尝试修改返回 409、恢复；最终 ON_SALE 且 A 档启用。重复运行复用同一场次和 first_opened_at。脚本停止在中间时可能保留 STOPPED 或停用票档，重跑继续；不会删除历史或清库。

自动化测试见 [SessionManagementIT](../../src/test/java/com/ticketing/catalog/SessionManagementIT.java) 和 [Week2ApiIT](../../src/test/java/com/ticketing/catalog/Week2ApiIT.java)。使用固定/可控 UTC Clock，测试库 ticketing_test；两个并发事务通过 CountDownLatch 在真实持锁后协调起跑，覆盖两种先后顺序。真实写入后注入异常验证全量回滚，不将异常注入描述成进程中断实验。

执行结果、数量、环境及演示证据以[第二周周报](../progress/week-02.md)为准；没有用正确性测试推导性能指标。本轮未实现 246 座主演示数据、登录、连座、锁座或交易闭环。
