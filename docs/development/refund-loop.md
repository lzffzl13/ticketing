# 单票退款、恢复与座位回流导读

依据：[第五周退款评审](../design/week-05-single-ticket-refund-review.md)、[ADR-0018](../decisions/0018-single-ticket-refunds-and-durable-recovery.md)。

本稿随实现编写；完整回归、真实演示和重启证据由第五周周报记录。下文描述已确认的处理规则与验收方式，不把测试代码存在当成已执行通过，也不代表负责人已完成理解验收。

## 1. 先记住原订单、原支付和原票

小林的原订单 O1 买了 A1/A2，原支付 P1 收款成功，生成 T1/T2。原订单项已经记下每个座位的成交价。只退 T1 时，退款金额取 T1 对应的原成交价，客户端只提交请求键。

```text
原购买：O1 PAID；P1 SUCCEEDED；A1/A2 SOLD；T1/T2 VALID
受理 R1：T1 REFUNDING；A1 仍 SOLD；T2/A2 不变
渠道退款成功：独立保存 R1 成功事实和成功通知，本地可能还没来得及处理
成功通知应用：R1 SUCCEEDED + T1 REFUNDED + A1 AVAILABLE + O1 PARTIALLY_REFUNDED
消费者二重购：新 O2、新 P2、新 T3，占用原来的 A1；T1 永久失效
```

原 P1 仍然表示当时收到的完整金额，不会被改成一笔较小的收款。退款金额从成功退款单汇总；原 CONSUMED 锁组也保留历史，不调用取消待支付订单时的整组释放。

## 2. 入口和请求身份

| 入口 | 身份与作用 |
| --- | --- |
| `POST /api/orders/{orderId}/tickets/{ticketId}/refunds`，`{"requestKey":"refund-demo-1"}` | CONSUMER，本人原订单和原票，金额由后端确定 |
| `GET /api/orders/{orderId}/refunds` | CONSUMER，查询本人退票历史、当前订单汇总和成功退款金额 |
| `POST /api/operations/refunds/{refundNumber}/succeed` 或 `/fail` | OPERATOR，控制模拟渠道的原退款结果，不直接改本地票或座位 |
| `GET /api/operations/refunds/{refundNumber}/notifications` | OPERATOR，查看原退款的持久通知快照 |
| `POST /api/operations/refund-notifications/{notificationNumber}/deliver` | OPERATOR，按通知编号投递后台保存的可信原快照 |
| `POST /api/operations/refunds/{refundId}/recover` | OPERATOR，重新处理原退款编号和原通知，不能强行指定退款结果 |

消费者与请求键唯一确定原申请。同键重试拿回同一个 R1，即使已经过了退票截止，也不会把原请求变成新申请；同键换票冲突。R1 明确失败后，原键仍返回失败的 R1，新意图使用新键创建 R2。同票最多一个进行中或成功的退款流程，失败历史保留。

新申请必须在取得业务锁并重新检查状态后满足 `now < admission_open_at`。相等时拒绝。此前已受理的 R1 跨过入场开放时间也继续恢复，不能半途撤销。

## 3. 为什么分几次提交

假设模拟渠道已完成 R1，但应用恰好退出。本地没有提交，不会让已退款的渠道事实消失。因此不能用一个包住全部步骤的大事务来表达整个流程。

| 短事务 | 提交内容 | 中断后如何找回 |
| --- | --- | --- |
| 本地受理 | 新退票单 REQUESTED、T1 REFUNDING、恢复时间 | 扫描原 R1，补发原请求 |
| 模拟渠道 | 原 R1 的 PROCESSING/终态、对应通知快照 | 按原编号查到，重复请求不再退一笔 |
| 本地结果 | 成功时退票、原票、单座及订单汇总一起更新；失败时退票 FAILED 与原票恢复 VALID 一起更新 | 重投同一个原通知 |
| 通知确认 | 接收方提交成功后标记原通知已送达 | 确认失败仍可重投，结果事务必须幂等 |
| 本地恢复结算 | 全部原通知确认后清理恢复资格 | 已确认通知可重复读取，再完成结算 |

**退款业务成功与通知确认完成是两件事。**R1 已 SUCCEEDED，但通知确认尚未完成时，仍保留恢复资格。否则在“结果已提交、通知未确认”的窗口退出，后台就会永久漏掉它。

本地写锁按场次、订单、原支付、原退票单、座位、原票的顺序取得；同类多行升序。调用模拟渠道时不持有本地业务锁。模拟渠道另按原支付行锁串行计算进行中和成功的退款总额，不能超过原收款。

## 4. 成功、失败和未知怎样影响旧票码

| 当前事实 | T1 | A1 | 旧截图 |
| --- | --- | --- | --- |
| R1 已受理但未明确结果 | REFUNDING | SOLD | 不能取得新码；未来核销须拒绝 |
| 渠道明确失败并应用原失败通知 | VALID | SOLD | 密钥仍受信且时间允许时可以继续使用，无须重新出票 |
| 原成功通知已原子应用 | REFUNDED | AVAILABLE，随后可被新买家占用 | 始终指向 T1，不能获得新 T3 的资格 |
| 渠道失败、超时或结果未知 | 保持 REFUNDING | 保持 SOLD | 不猜测失败，不提前恢复或回流 |

“旧截图的 HMAC 仍正确”只说明它的内容与原密钥相符，不表示 T1 当前有入场资格。本轮通过实际旧票状态、旧码绑定、旧票取码拒绝和新票正常取码验证；正式核销与全部尝试审计按第六周推进。

终态通知先核对原绑定，再返回原结果。A1 后来已被 O2 锁定或售出时，重投 R1 不会再次执行回流。退款后重投原 P1 成功也不能再次出票、把 O1 拉回 PAID，或改写新占用。

## 5. 自动恢复与人工边界

退款扫描读取每张退款单自己的恢复字段，与只扫描待支付订单的支付任务分开。默认每轮结束后 1 秒、单批 50 个，固定最大 ID 上界跨批前进并回绕；重启后重新发现持久任务。

配置为 `REFUND_RECOVERY_ENABLED`、`REFUND_RECOVERY_FIXED_DELAY_MS`、`REFUND_RECOVERY_BATCH_SIZE`。领取时再次检查候选资格，60 秒 token 租约协调同一退款的恢复；正常处理 5 秒复查。实际错误按 5/15/30/60/120 秒退避，第 6 次或持续实际异常 10 分钟转人工。旧 token 无权结算新领取；可信原通知仍可幂等应用。

进入人工状态只暂停自动恢复，不会擅自恢复原票或释放未知座位。运营查原事实、修复原因后重试原编号。没有引入 MQ、Redis 或新的外部退款渠道。

## 6. 可重复演示

启动本地应用后运行：

```powershell
.\scripts\demo-refunds.ps1
```

默认独立命名空间 `W4PRF`。首次复用支付演示建立真实 O1/P1/T1/T2，然后只退其中一票；消费者二通过真实下单和支付买回原座位，获得新 T3。脚本检查旧通知对新 HELD 和 SOLD 占用均无影响，重投原支付不重复出票，旧票取码返回冲突，新票取码成功。

脚本把原请求键、订单/退款/支付/票身份以及原票码保存在被忽略的 `.local/demo-refunds/<Namespace>.json`。原票码属于敏感凭证，不打印完整内容，也不提交 Git。相同命名空间重跑复用原请求；退款后不会再次调用只接受原订单 PAID 的购买演示。演示不清理历史交易。

需要明确暂停在“渠道已成功，本地尚未处理”时，先以 `REFUND_RECOVERY_ENABLED=false` 启动应用，再运行：

```powershell
.\scripts\demo-refunds.ps1 -Namespace W4PRFRESTART -PauseAfterChannelSuccess
```

脚本检查渠道 SUCCEEDED、原通知未送达、本地未结算、T1 REFUNDING、A1 SOLD 后返回。停止该应用，保持同一数据库和密钥，以恢复开启配置重新启动。后台应自动重投原通知并完成原子事务，再运行同一命名空间完成回购验证：

```powershell
.\scripts\demo-refunds.ps1 -Namespace W4PRFRESTART
```

`-RecoverBeforeDelivery` 另外用于演示运营主动恢复。自动重启恢复验收应先观察后台自行完成，再运行普通续跑；不能用手动恢复结果替代自动恢复证据。重新运行暂停参数不能把已经成功的退款倒回暂停点，应使用新的独立命名空间。

## 7. 测试入口与证据边界

关键代码依次为 [RefundController](../../src/main/java/com/ticketing/trade/RefundController.java)、[RefundService](../../src/main/java/com/ticketing/trade/RefundService.java)、[RefundTransactionService](../../src/main/java/com/ticketing/trade/RefundTransactionService.java)、[RefundQueryService](../../src/main/java/com/ticketing/trade/RefundQueryService.java)、[RefundRecoveryScanner](../../src/main/java/com/ticketing/trade/RefundRecoveryScanner.java) 和 [RefundSimulatorTransactionService](../../src/main/java/com/ticketing/simulator/RefundSimulatorTransactionService.java)。前者提供入口和无总事务编排，本地事务负责一致性，模拟器独立提交，扫描只发现候选并逐笔重验。

申请在场次锁前查原请求可快速重放，取得锁后必须用独立 `lockByRequest ... FOR UPDATE` 语句重查，避免 MyBatis 一级缓存复用锁前未命中的结果。恢复在读取通知批次后又出现新的成功通知时，保留下一次恢复机会；只有真实绑定/终态矛盾或终态持久通知缺失才转人工。

`RefundSimulatorIT` 使用隔离的 MySQL 测试库和可控时钟，覆盖独立渠道受理与终态、原绑定、额度并发、重复编号、终态竞争、外层回滚后渠道仍提交、真实通知 INSERT 后注入异常、接收方失败不确认、真实确认 UPDATE 后异常及重投。它不创建商户订单或票，因此不替代本地退款事务、库存回流或真实回购测试。

完整测试由项目根目录执行 `.\scripts\test.ps1 -Integration`，不能与清理同一测试库的其他集成测试或实验并行。真实演示使用开发库，完整回归使用隔离测试库。

实际执行时间、总数、环境、故障与重启结果及负责人理解反馈应以[第五周周报](../progress/week-05.md)为准。
