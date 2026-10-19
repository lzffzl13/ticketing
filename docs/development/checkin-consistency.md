# 第六周核销、审计与一致性检查导读

本轮负责人已确认 [ADR-0019](../decisions/0019-atomic-checkin-and-append-only-audit.md) / [ADR-0020](../decisions/0020-read-only-consistency-reports.md)，要求先完成编码，再集中学习验收。工程验证与学习验收分别记录，本文提供之后阅读的顺序。

## 入口与业务结果

`POST /api/checkins` 使用 checker1 的 Bearer，提交 `{ "sessionId": 1, "credential": "原票码" }`。只有 `data.result=SUCCESS` 才表示本次核销成功。HTTP 200 表示扫描业务结果已持久化，不能仅看 HTTP 状态放行。重复扫描返回 ALREADY_CHECKED_IN，并新增一条记录。

| 结果 | 含义与数据变化 |
| --- | --- |
| SUCCESS | VALID→CHECKED_IN，成功审计同事务；座位继续 SOLD |
| ALREADY_CHECKED_IN | 原票已核销，本次独立审计，不再改变票 |
| REFUNDING / REFUNDED | 原票退款处理中或已退票；不访问已重售的新占用 |
| INVALID_CREDENTIAL | 验真失败或验真后没有原票，票身份不受信任 |
| WRONG_SESSION | 真票不属于工作人员选择的当前场次 |
| TOO_EARLY / TOO_LATE | 有效票不在 `open <= now < close` 窗口 |
| SESSION_NOT_FOUND | 选择的场次不存在，保留提交编号，关联场次为空 |
| INCONSISTENT_STATE | 有效票关联的已付订单或已售座位不一致，拒绝并交运营检查 |

结果优先级为不存在场次、验真/票不存在、错场次、票状态、时间窗口、关联业务事实。已核销或已退款票即使过期，仍可明确返回原票状态。格式错误、超长或空票码、非正场次编号、未登录和权限拒绝在受理前返回 HTTP 错误；不进入业务审计。系统错误或数据库事务回滚不表示已经入场，恢复后重试并查询记录。

`GET /api/checkins` 允许 CHECKER / OPERATOR，支持 sessionId、ticketId、from、to、afterId、limit；时间区间为 `[from,to)`，id 升序，limit 1–100。返回 records 与 nextAfterId，后者为空表示没有下一页。审计只有 SHA-256 摘要，没有原票码；记录 requestId 可与响应 X-Request-Id 关联。

## 一张票如何完成核销

入口是 [CheckinController](../../src/main/java/com/ticketing/checkin/CheckinController.java)，事务主流程在 [CheckinService](../../src/main/java/com/ticketing/checkin/CheckinService.java)。先锁所选场次并验证签名，之后通过原票身份锁订单、座位和票；全部等待结束后取当前时间。这样排队时尚未截止的请求，真正拿到锁时已经截止也不能入场。

整笔事务完成票状态迁移、满足条件时的订单 COMPLETED 归集和 SUCCESS 记录。审计写入或提交失败会把票和订单一起回滚，不能出现“票已使用却没有成功记录”。生成列唯一索引提供每票最多一次成功的数据库兜底。

订单和座位由各自模块提供明确入口，核销不从其他模块直接调用写 mapper。支付、退款与核销沿相同场次锁方向串行，原退款流程先取得 VALID 时把票改为 REFUNDING；开放后核销看到处理中便拒绝。退款失败恢复原票后，窗口内可再次核销；成功后旧码始终绑定已退原票，无法使用回流重售的新票。

订单完成只归集全部未退款票已核销的情况，已有退款成功导致最后剩余票均履约时也按相同规则归集。全部退款保留 FULLY_REFUNDED。截止后无人到场订单的批量归集暂缓，因此已过期 PAID 不表示还能核销。

## 检测与恢复的边界

运营 `GET /api/operations/sessions/{id}/consistency` 返回 consistent、counts、recovery 和 differences。consistent 只表示本地规则没有发现差异，渠道仍可能有未完成工作；recovery 展示待支付、未决和人工数量，不能据此推断未付款。

[ConsistencyService](../../src/main/java/com/ticketing/consistency/ConsistencyService.java) 用单个 REPEATABLE_READ 只读事务读取场次内本地表，[ConsistencyRules](../../src/main/java/com/ticketing/consistency/ConsistencyRules.java) 对该快照执行纯规则。避免分别调用多个 REQUIRES_NEW 查询服务或使用 FOR UPDATE 当前读，否则读到不同提交版本会制造假异常。

检查覆盖订单金额与锁明细、当前锁归属、成功支付与整组出票、已售座位与当前占座票、票和退款对应、退款汇总、成功核销审计。历史锁或退款成功后不要求座位一直 AVAILABLE，新消费者可正常购买。

默认后台每轮结束 60 秒后扫描，批量 10 个场次，固定最大 ID 跨批前进、到尾回绕；查询失败保留游标，单场次失败隔离并在下轮遍历重试。重启从头检查持久事实。配置为 CONSISTENCY_ENABLED、CONSISTENCY_FIXED_DELAY_MS、CONSISTENCY_BATCH_SIZE；测试禁用实际后台调度。

发现差异先保存报告和相关编号，检查应用/数据库错误及原支付或退款渠道事实。已知未完成流程用原运营恢复入口重试原编号；终态或归属矛盾先修复缺陷并人工核查，不直接改库存或猜测补票。原支付/退款仍有各自的扫描、租约、退避与人工规则。检测器不执行修复，也不改变人工标记。

检测失败通过 consistency_session_failed / consistency_scan_failed 日志和运营手动查询发现；差异以 consistency_difference 记录。第一版没有报告持久表或外部告警平台，日志丢失后需重新查询，不能承诺历史报告可追溯。

## 学习与代码验收方式（2026-10-19调整）

工程验证见[第六周周报](../progress/week-06.md)，真实演示见[端到端脚本导读](week-06-demo.md)。负责人明确反馈连续场景判断题过于常识化且重复，无法检验核心实现理解。此前真实回答保留，但不据此认定已经读懂核心代码。

后续每次选择一段真实核心代码，先把业务目的、调用链、依赖的语言/框架/数据库机制及代价讲清楚；再用一个综合任务检验必要的理解。生僻机制须先教，不能把不了解框架当成业务理解错误。不以连续增加题目难度替代教学。

| 核心主题 | 应先讲清的实现内容 | 有效的验收依据 |
| --- | --- | --- |
| 核销事务与失败审计 | scan进入/退出事务的位置；save正常返回与异常向外抛出的区别；MANDATORY如何参与同一事务；异常在哪一层被捕获 | 能顺着实际调用链解释哪些SQL提交/回滚、业务拒绝为什么仍能保存审计，并定位对应集成测试 |
| 并发与数据约束 | 场次锁、订单/票锁、条件更新和SUCCESS唯一生成列各自保护什么；同场次粗粒度串行的代价 | 能结合SQL和事务解释两次扫描如何交错，说明某项保护改动影响哪些路径，使用实际测试作为证据 |
| 一致快照检测 | inspect的一次事务实际包含哪些SELECT；首次读何时确定快照；多个独立事务会怎样混读不同提交版本；检测报告与业务恢复各自入口 | 能对照真实支付/退款并发提交测试解释每次读取看到的版本，指出拆开事务后产生误报的具体读取顺序 |

采用“完整讲解一段→负责人复述核心机制或分析一处相关改动→必要时用现有测试验证”的节奏，一次聚焦一个主题。不要围绕已答对的规则反复更换时间/人物再问同一判断；不要把“能不能放行、能不能释放”的常识题作为实现掌握证据。场景必须先交代初始状态、事务归属、提交点和正常暂态/人为故障的来源，避免制造无关歧义。

验收记录分别说明业务判断、实现机制、代码阅读及测试分析实际做到哪一步；允许暂时跳过，不虚报整周通过。2026-10-19已获得回滚与时间口径等场景反馈，核心代码阅读和整周学习验收尚未完成，详见[学习记录](../progress/week-06.md)。
