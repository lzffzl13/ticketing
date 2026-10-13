# 第四周支付、出票、取消与恢复导读

方案已经分别获负责人确认，见 ADR-0015。负责人选择先完成编码与验证，再集中讲解；2026-10-13 已完成本轮核心业务与事务代码阅读及场景理解检查，具体范围和反馈见第四周周报。本导读保留为后续代码复习入口，实际测试/演示证据仍以周报为准。

## 入口

| 身份 | 方法与路径 | 返回与用途 |
| --- | --- | --- |
| 消费者 | POST /api/orders/{id}/payments，Idempotency-Key 请求头 | 本地支付记录；INITIATED 只表示意图保存，PROCESSING 表示已同步受理 |
| 消费者 | GET /api/orders/{id}/payments | 本人订单所有支付历史，不主动改变渠道状态 |
| 消费者 | GET /api/orders/{id}/tickets | 本人订单票记录；票号暂不是二维码核销凭证 |
| 消费者 | POST /api/orders/{id}/cancel | 整单当前状态与取消/恢复信息；PENDING_PAYMENT + 取消意图表示尚待安全确认 |
| 运营 | GET /api/operations/orders/{id}/payment-state | 检测 INITIATED、异常次数、下次恢复时间、人工标记与领取截止 |
| 运营 | POST /api/operations/orders/{id}/recover | 主动恢复原编号，允许越过退避但不抢活动租约 |
| 运营 | POST /api/operations/payments/{number}/succeed 或 /fail | 只保存模拟渠道结果与通知，便于控制迟到/乱序 |
| 运营 | GET /api/operations/payments/{number}/notifications | 保存过的消息快照及送达标记 |
| 运营 | POST /api/operations/notifications/{number}/deliver | 原通知投递/重投，本地结果提交后才确认送达 |

消费者身份来自 Bearer 上下文。他人订单统一 404，错误角色 403，未登录 401。客户端不指定金额、订单归属或支付成功结果。金额与原截止来自后端订单快照。

## 正常支付

1. PaymentService 校验请求编号，调用独立本地短事务 begin。
2. 锁场次→订单→支付集合，在锁后读微秒 Clock。原请求键返回原支付；新键要求待支付、无取消意图、严格早于原截止、无进行中或成功支付。
3. 同事务创建 INITIATED，保存订单恢复 token/60秒租约和立即可检测的恢复时间，然后提交。
4. 无本地事务/库存锁时按原编号查模拟器，缺行补发原意图；模拟器独立提交事实。首次渠道受理仍检查实际截止。
5. applyChannel 在独立 READ COMMITTED 事务核对编号、订单、后端金额和原截止。PROCESSING 只同步受理；明确 FAILED 保留失败历史，订单和锁可在截止前用新请求键重试。
6. SUCCEEDED 同事务改支付成功、锁消耗、整组 HELD→SOLD 并清 current_hold_id、每项签发 VALID 票、订单 PAID。核对原持有者和整组数量，任一失败全部回滚。

已受理支付的最终成功可以在截止后应用，场次暂停/票档停用不影响既有订单结果。INITIATED 可以直接收到成功，不假定通知总在受理响应之后。

## 重复与乱序

本地终态稳定：同终态返回原记录，旧 PROCESSING 不倒退，相反终态报 PAYMENT_FACT_CONFLICT 并标记人工核查。原请求键始终返回当前持久的原尝试，即使已失败、取消或过期；重放不自动创建另一渠道请求。新的失败后重试仍使用原订单截止。

通知投递读取保存的 observed_status，不能把旧 PROCESSING 快照替换为最新 SUCCEEDED。真实渠道绑定内容用查单核对；applyChannel 的 REQUIRES_NEW 提交完成后接收器才返回，模拟器随后确认 delivered_at。出票事务失败则通知保持未送达；出票提交后、ack前中断会重投，幂等入口保证原票不重复。

## 取消与截止

先锁内保存 USER / TIMEOUT 意图；首次原因保留，阻止新支付。无支付或全部支付已是可信 FAILED / CLOSED 时可同事务直接 CANCELLED + RELEASED/EXPIRED + 整组 AVAILABLE。

存在未决尝试时先提交意图，再事务外查单；已成功应用成功结果，PROCESSING 需关单，查无编号也 close 留 CLOSED 记录。最后重新取得本地锁并读取全部支付与当前占用，只有全部确定不能再成功才释放，不能使用事务外旧结论。

调用失败/渠道未知/本地应用失败时保留 PENDING_PAYMENT、ACTIVE、HELD。取消返回这项状态和意图，不能把 HTTP 200 解读为座位已经释放。活动恢复租约期间重复取消只保存意图并返回；当前工作者或后续恢复再处理。第四周没有后台扫描；发起/取消/运营恢复中发现到期会安全处理，第五周扫描复用同一入口。

## 故障与恢复

- 创建意图后进程退出：租约和 next_recovery_at 已持久化；60秒后可接管原编号。
- 渠道成功后本地失败：独立 SUCCEEDED 仍可查回，所有本地变化回滚；恢复重做同一个结果事务。
- 渠道关闭后释放失败：独立 CLOSED 保留，本地仍整组 HELD；恢复后再整组释放。
- 一次恢复按订单用随机 token 领取60秒，调用渠道前提交领取。未过期领取不能抢占；旧 worker可幂等应用事实，但结算时锁内核对 token，不能重复计故障、清新领取或修改新尝试。
- 未完成租约过期重领记一次中断故障；明确失败后新支付意图刷新代次。正常待支付不计失败；无故障恢复清连续失败与异常起点，PROCESSING 的下次检查为 min(now+5秒,原截止)。
- 通知失败也保留恢复依据；本次领取者按 token 结算一次失败，无领取通知只记录异常起点/错误/恢复时间，不能累计别人的故障。旧 FAILED 通知不清新 INITIATED 的补发异常，旧 PROCESSING 不清渠道已成功时本地的出票异常。
- 实际失败依次退避5/15/30/60/120秒，第6次或持续实际异常10分钟进入人工介入；矛盾事实立即标记人工核查。recoverIfDue 遵守退避与人工标志，运营 recover 可以主动重试但不能抢活动领取。不存在强改未支付/强制释放接口。

本轮没有异步自动投递、自动恢复扫描、对账修复、二维码、退款和核销。持久意图、通知和恢复字段提供检测线索，运营入口提供重试；后续调度不能改变未知不释放的不变量。

## 关键代码与测试

| 文件 | 阅读目的 |
| --- | --- |
| src/main/java/com/ticketing/trade/PaymentService.java | 无事务编排、渠道交互、保存快照投递与失败分类 |
| src/main/java/com/ticketing/trade/PaymentTransactionService.java | begin / applyChannel / cancel / claim / complete / fail，短事务和租约结算 |
| src/main/java/com/ticketing/trade/PaymentQueryService.java | 本人归属与同一快照查询；恢复 token 不暴露 HTTP |
| src/main/java/com/ticketing/inventory/InventoryHoldService.java | 整组持有者重验、consume / release、任一变更回滚 |
| src/main/java/com/ticketing/ticketing/TicketService.java | 加入上层结果事务，每订单项一张票 |
| src/main/resources/db/migration/V4__create_payments_and_tickets.sql | 生成列唯一、复合外键、状态/时间 CHECK、订单恢复字段 |
| src/test/java/com/ticketing/trade/PaymentLifecycleIT.java | 成功/失败、幂等、乱序、截止、权限与真实出票故障 |
| src/test/java/com/ticketing/trade/PaymentRecoveryIT.java | 取消/关单、未知占座、真实释放故障、阈值和旧 token |

完整 HTTP 演示见 payment-loop-demo.md；锁座基线见 ../benchmarks/order-holds-baseline.md。测试数量和实际通过结果以周报及报告为准，不能由本导读中的场景列表推断。
