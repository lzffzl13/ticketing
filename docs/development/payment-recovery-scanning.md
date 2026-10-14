# 自动超时取消与支付恢复导读

## 业务背景与入口

消费者 10:00 下单，原支付截止 10:15。时间到达只会禁止新支付，不会自行改变 MySQL 的订单或座位状态。自动扫描负责发现到期订单和中断支付，调用第四周已有的安全处理流程；方案见 ADR-0016。

应用启动默认启用，使用三个配置：

| 配置 / 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| ticketing.payment-recovery.enabled / PAYMENT_RECOVERY_ENABLED | true | 是否注册后台调度任务 |
| ticketing.payment-recovery.fixed-delay-ms / PAYMENT_RECOVERY_FIXED_DELAY_MS | 1000 | 一轮执行完后等待多少毫秒再开始下一轮，必须为正数 |
| ticketing.payment-recovery.batch-size / PAYMENT_RECOVERY_BATCH_SIZE | 50 | 单轮最多获取的候选数，范围 1–500 |

默认值是开发参数，没有承诺所有到期订单都能在 1 秒内释放。每单可能进行查单、关单和多个短事务，积压也需要分批处理。

## 主流程与资格重验

1. 一次完整遍历开始时读取当前最大订单 ID，固定上界。
2. 短只读事务取下一批候选 ID。候选必须待支付、非人工介入、无活动租约；有恢复时间就遵守它，无恢复时间则须已到期或有取消意图。
3. 结束候选查询事务，逐单调用 PaymentService.recoverIfDue。
4. 按场次→订单→支付加锁并重读当前状态，再检查自动资格。候选查询后可能已有新支付、回调或人工核查，不能依据旧查询继续取消。
5. 未结算的过期租约先记录一次中断故障；达到人工阈值暂停。允许处理时领取一个新的 60 秒 token。
6. 无支付或所有支付已确定 FAILED / CLOSED 时，安全取消；有未决支付时，在本地锁释放后查原渠道编号，按真实结果出票或关单。
7. 当前批处理结束后向前推进游标；达到上界或后方没有候选就回绕。新订单下一次遍历纳入，旧订单不会因持续新增而长期错过复查。

Scanner.scanOnce 的返回值是“此次选中的候选数量”，包含后来锁内重验跳过和恢复失败的候选，不是成功取消数或成功支付数。

## 数据变化、异常与事务

| 情况 | 数据变化 |
| --- | --- |
| 从未发起支付的到期订单 | 同事务订单 CANCELLED、锁 EXPIRED、全部座位 AVAILABLE，清 current_hold_id，无票 |
| 到期前受理、渠道已成功 | 现有成功事务：支付 SUCCEEDED、订单 PAID、锁 CONSUMED、座位 SOLD、每项一张 VALID 票 |
| 到期且渠道 PROCESSING | 原编号安全关单；取得 CLOSED 事实后才执行取消事务 |
| 查询/关单/本地结果异常 | 保持原占座，保存恢复时间和错误；到退避时间再处理 |
| 人工标志、未来恢复时间、活动租约 | 自动入口不写取消意图、不改领取、不释放 |
| 旧候选已 PAID / CANCELLED | 返回当前状态，无新业务结果 |

整批无外层事务。候选事务、领取事务、渠道独立事务和本地结果事务各自结束，不能把它们当成天然共同提交。单笔异常隔离，后续候选继续；已领取后中断依靠过期租约再发现。候选查询异常不推进游标，下轮再试。进程重启只丢访问顺序，订单期限、恢复计划、渠道结果都仍在 MySQL。

实际故障退避与人工条件沿用 ADR-0015。达到人工门槛时运营查看 GET /api/operations/orders/{id}/payment-state，修复依赖后 POST /api/operations/orders/{id}/recover；继续使用原编号，不强行释放未知支付座位。

## 运行与演示

```powershell
.\scripts\run-app.ps1
.\scripts\demo-payment-recovery.ps1 -Namespace W5RRUN
.\scripts\demo-payment-recovery.ps1 -Namespace W5RRUN
```

脚本保留原请求意图，创建两座订单，保存渠道成功后只查询等待后台出票，不投递通知、不调用运营恢复。必须观察 PAID、CONSUMED、两座 SOLD、两张 VALID 票，原成功通知 delivered_at 仍为空。相同命名空间重跑检查原订单、支付期限、支付编号和票号；不删除历史或重新选座。

实际 15 分钟超时演示分为两次命令，不缩短期限或更改 Clock：

```powershell
.\scripts\demo-payment-recovery.ps1 -Namespace W5RTIME -Scenario Timeout
# 首次输出原 paymentExpiresAt；到该时间以后重新运行相同命令。
.\scripts\demo-payment-recovery.ps1 -Namespace W5RTIME -Scenario Timeout
```

首次建立从未发起支付的待支付订单并输出等待阶段；截止后重跑检查自动 CANCELLED / EXPIRED / AVAILABLE、无支付和票。等待阶段不算取消验收通过。开发数据默认固定虚拟规则厅，接续意图在被忽略的 .local/demo-payment-recovery，账号和令牌不写入该文件。

停机恢复演示可在后台关闭的应用中建立渠道成功事实，保留数据库后重启并启用扫描；观察原单自动出票。第四周脚本的 PauseAfterChannelSuccess 需要关闭自动扫描才能稳定保留暂停点，旧脚本普通流程也要允许本地状态在投递前被扫描推进。

## 测试与关键代码

普通 test profile 禁用后台调度，测试手动调用单轮扫描并移动可控 Clock；真实 Spring 调度启停由独立的调度测试验证。关键测试包含无支付截止、原编号关单、遗漏通知补出票、查单/关单故障、退避/人工/租约重验、候选后状态变化、两扫描器竞争、与通知竞争、旧订单不释放新持有者、批量与回绕。执行结果见第五周周报，不把场景计划当成已经通过。

| 文件 | 阅读重点 |
| --- | --- |
| src/main/java/com/ticketing/trade/PaymentRecoverySchedulingConfiguration.java | 调度启停与单轮错误后继续运行 |
| src/main/java/com/ticketing/trade/PaymentRecoveryScanner.java | 固定上界、有限候选、逐单恢复与异常隔离 |
| src/main/java/com/ticketing/trade/PaymentMapper.java | 候选条件和无恢复时间的到期订单 |
| src/main/java/com/ticketing/trade/PaymentTransactionService.java | claim 锁内重验、过期领取计数与取消顺序 |
| src/main/java/com/ticketing/trade/PaymentService.java | recoverIfDue、原渠道事实的处理 |
| src/test/java/com/ticketing/trade/PaymentRecoveryScannerIT.java | 真实 MySQL 最终状态与受控并发 |
| src/test/java/com/ticketing/trade/PaymentRecoveryIT.java | 自动资格拒绝路径和人工阈值 |

理解检查：10:14:59 渠道已收款但通知没到，10:15 扫描发现本地仍待支付，应释放还是出票？如果查单异常，应保留哪些状态、何时再试？核心理由是释放必须有未支付且不会再成功的证据，时间本身不能提供这份证据。

## 本轮执行证据

2026-10-14 14:11:55 +08 完整 255 项回归通过，含新增 26 项；后台遗漏通知补出票、原请求重放、既有到期订单整组释放，以及停止旧进程后启用扫描自动出票均已验证。新 Timeout 夹具只执行了建立与原期限前检查，取消证明来自真实既有到期订单及可控时钟数据库测试。具体环境、编号和本机日志见[第五周周报](../progress/week-05.md)。
