# 支付模拟器导读

本页对应第四周第二部分的模拟渠道组件：持久化交易、查单/关单、成功/失败控制及通知保存和重投。方案和两表见 [ADR-0014](../decisions/0014-persist-independent-payment-simulator.md)。本地支付、原子出票与恢复已由 trade 编排接入，见[支付闭环导读](payment-loop.md)及 ADR-0015；本页的 25 项测试仅代表模拟器组件证据，完整闭环验证另见第四周周报。

## 1. 入口与身份边界

`PaymentSimulatorService` 是跨模块调用入口，trade 在不持有库存锁的编排阶段调用。运营控制和通知投递由 trade 的 PaymentOperationsController 提供并要求 OPERATOR；消费者不能直接触发成功。

| 方法 | 行为 |
| --- | --- |
| start(binding) | 按编号发起或返回原交易；首次过期保存明确失败 |
| find(number) | 查询当前渠道事实，未知编号返回 Optional.empty |
| close(binding) | 关闭进行中交易，或为尚不存在编号写关闭记录；已成功/失败/关闭返回原终态 |
| succeed(number) / fail(number) | 将 PROCESSING 确定为成功或明确失败；同终态重放无副作用，冲突终态拒绝 |
| notifications(number) | 查询该支付的历史通知快照 |
| createNotification(number) | 从真实当前渠道事实保存一条新通知，可验证不同通知表达同一结果 |
| deliver(notificationNumber, receiver) | 读取原快照，事务外调用接收者；接收者成功提交并返回后才确认送达 |

Binding 包含支付编号、商户订单编号、金额与原截止。编号为区分大小写的 1–36 位可打印 ASCII 字符且不含空白。金额大于 0、不超过 DECIMAL(12,2) 容量，精确规范为两位小数，不舍入；期限截到数据库微秒精度。编号绑定的订单、金额和规范化截止永久不变。

## 2. 首次发起与相同编号重试

```text
入口校验并规范化绑定内容
→ 独立短事务锁读渠道交易
→ 已有：核对绑定，返回原事实；close 仅可结束 PROCESSING
→ 尚无：INSERT RESERVED，依靠唯一支付编号取得处理权
→ 此后读取微秒 Clock；首次受理严格 now < 原截止
→ 写 PROCESSING 或 FAILED/CLOSED，并保存一条通知
→ 同一模拟器事务提交
```

RESERVED 只在尚未提交的事务内表示“这个编号由本事务处理”，不是可返回的支付结果。正常出口会先写真正的状态；任何中途异常整体回滚。公开模型不包含 RESERVED，读取异常遗留也不能将它解释为未支付证明。

两请求都发现编号不存在时，不能靠锁不存在的行仲裁。两边实际尝试 INSERT，由主键确定胜者；失败方退出并回滚原事务，再通过另一事务锁读已提交胜者。初查和恢复都通过独立代理，避免沿用旧的 MyBatis 空结果缓存。

首次受理越过截止，保存 FAILED / EXPIRED_BEFORE_ACCEPTANCE、accepted_at 为空。已在截止前受理的原编号重试或结果确认不重新判断受理资格，原受理时间、截止和金额不变。

## 3. 数据变化与终态

start 首次受理增加一条 PROCESSING simulated_payment 和一条 PROCESSING simulated_notification。成功控制将渠道交易改为 SUCCEEDED，同时增加成功通知；明确失败使用 FAILED / SIMULATED_FAILURE；关单将 PROCESSING 改为 CLOSED。以上都不会修改 customer_order、seat_hold、session_seat 或签发票。

```text
PROCESSING → SUCCEEDED / FAILED / CLOSED
```

SUCCEEDED、FAILED、CLOSED 均不可改成另一个终态。同终态重试直接返回原时间和原结果，不再生成通知；如需重投，使用已保存通知，或从当前事实显式创建另一条通知。FAILED 的安全关单仍返回 FAILED，SUCCEEDED 的关单返回已支付，不能当未支付释放的依据。

尚无交易也可 close：保存 CLOSED 且 accepted_at 为空，绑定原订单、金额和截止。迟到 start 只能返回 CLOSED。这堵住“本地先取消释放、原发起请求后来才到渠道”的窗口；未来本地取消仍需重新锁订单和库存核验，模拟器关单不替代本地取消事务。

## 4. 事务与通知恢复

PaymentSimulatorService 使用 NOT_SUPPORTED 编排；PaymentSimulatorTransactionService 经独立 Spring 代理使用 REQUIRES_NEW / READ COMMITTED。调用方不能持有本地场次或订单写锁调用渠道；挂起事务不会自动释放调用方已经取得的锁。

渠道状态与通知快照在同一事务提交，不能出现成功记录有了而成功通知漏写。渠道提交后，调用方回滚不撤销它。本地 PaymentTransactionService.applyChannel 以同支付编号确认，原子更新订单、库存和票；PaymentService.deliver 保证接收器在本地结果提交后才返回。

通知保存当时的 observed_status / observed_at，交易后来成功也不改写旧 PROCESSING 通知。可先送成功、后送旧 PROCESSING，或者重复送同一通知，以验证接收方乱序和幂等。

deliver 的读取事务先结束，再调用 receiver，不持有模拟器数据库锁。接收方须在自己的本地结果事务提交后返回；失败抛错则不确认 delivered_at。确认送达独立提交，重复确认保留首次送达时间。若本地已提交但确认送达前中断，原通知仍会再次投递；出票接收器需要幂等，不能依赖“只投递一次”。

本阶段用可控服务调用驱动延迟/重复投递，无后台自动投递或重试扫描；失败后保留交易和未确认通知，调用 find / notifications 后按原编号重做操作即可。自动任务、退避与人工介入参数仍待 Q-06 评审，不能把这些恢复入口描述为已自动运行。

## 5. 异常分支与数据库兜底

| 情况 | 行为 |
| --- | --- |
| 非法绑定/编号/金额 | 400 INVALID_SIMULATOR_REQUEST，不产生渠道记录 |
| 原编号改变订单、金额或截止 | 409 SIMULATOR_BINDING_CONFLICT，保留原事实 |
| 成功/失败控制冲突终态 | 409 SIMULATOR_STATE_CONFLICT，不倒退 |
| 未知编号或通知 | find 返回空；其他命令 404 NOT_FOUND |
| 首次发起/关单锁等待失败 | 409 SIMULATOR_RETRY_REQUIRED，使用原编号和内容重试 |
| 数据库查询/结果控制/投递确认失败 | 异常向编排方传播，不能冒充未支付；重查保存事实后重做原操作 |
| 插入占号/状态/通知后发生异常 | 当次渠道写事务全部回滚，不留 RESERVED 或半条通知 |
| receiver 抛错 | 原通知未确认，允许重投 |

V3 的主键仲裁编号；CHECK 限制金额、合法状态、受理时间严格早于原截止、终态与结果时间、失败分类；通知外键只指向模拟器交易，CHECK 禁止 RESERVED 通知及倒序送达时间。业务绑定不可改、终态不可覆盖由服务及条件 SQL 维护，不声称 CHECK 能证明历史不可变性。

墙钟若后退，终态审计时间至少为原受理时间，确认时间至少为通知生成时间，避免写入自相矛盾的审计记录；截止资格仍按取得编号后的实际 Clock 检查。

## 6. 验证与关键代码

使用已有环境和脚本运行完整回归：

```powershell
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\check-infra.ps1 -IncludeTest
.\scripts\test.ps1 -Integration
```

新增 PaymentSimulatorIT 的 25 项真实 MySQL 测试覆盖：绑定重放与冲突、精确截止及锁等待跨截止、原已受理支付迟到成功、缺行首次 INSERT 并发、成功/关单竞争、实际写入后异常与原编号恢复、调用方事务回滚后的渠道独立提交、通知失败/乱序/重投、CHECK/FK。测试始终验证 ticketing_test，并按 notification → simulated_payment 先清理子表；不关闭外键或清开发库。

并发用屏障、锁点和 Future 结果，不以 sleep 猜先后；数据与通知故障在实际 SQL 写入后注入。独立提交测试证明调用方事务回滚不撤销渠道事实，尚不属于实际出票失败恢复。执行结果及日志见[第四周周报](../progress/week-04.md)。

| 代码 | 阅读重点 |
| --- | --- |
| src/main/java/com/ticketing/simulator/PaymentSimulatorService.java | 入口规范化、唯一键失败后的事务外恢复、通知投递 |
| src/main/java/com/ticketing/simulator/PaymentSimulatorTransactionService.java | 独立短事务、占号后时间、终态竞争与通知原子写入 |
| src/main/java/com/ticketing/simulator/PaymentSimulatorMapper.java | 唯一占号、条件状态更新、首次送达确认 |
| src/main/java/com/ticketing/simulator/SimulatorStorage.java | 禁止将内部 RESERVED 返回给编排方 |
| src/main/resources/db/migration/V3__create_payment_simulator.sql | 两表、CHECK、通知外键及索引 |
| src/test/java/com/ticketing/simulator/PaymentSimulatorIT.java | 25 项真实事务证据与故障注入 |

## 7. 学习检查与下一闭环

先阅读 open、finishLocked 与 deliver，解释三个例子：首次 start/close 都查不到编号时如何决定胜者；收款提交后调用方失败为何仍能查回成功；接收方已提交但送达确认失败为何允许重投。

负责人已确认 ADR-0015 的支付/票、结果事务、安全取消和 Q-06，并选择先完成编码，再集中阅读业务逻辑。2026-10-13 已完成本轮核心业务与事务代码阅读及场景理解检查，范围和反馈见第四周周报。完整闭环及异常恢复复习入口为 payment-loop.md，执行证据以第四周周报为准。
