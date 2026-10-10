# 第四周支付、出票与安全取消演示

- 状态：2026-10-10（Asia/Shanghai）正常演示、原命名空间重跑、真实应用重启后主动恢复均实际通过。
- 脚本：`scripts/demo-payments.ps1`。
- 范围：本人两座订单、同键支付、渠道成功、通知出票、重复/乱序通知、权限，以及先关渠道交易再整组取消。

## 启动与运行

先启动 Docker MySQL，再启动当前应用。数据库迁移、账号和本地密码沿用已有脚本；不用清库或删除卷。

```powershell
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\check-infra.ps1 -IncludeTest
.\scripts\run-app.ps1
```

在另一个终端执行：

```powershell
.\scripts\demo-payments.ps1
.\scripts\demo-payments.ps1
```

应用监听其他本地端口时传入 `-BaseUrl http://127.0.0.1:18084`。脚本只允许本机 HTTP 地址，不输出密码或 Bearer 令牌。使用相同命名空间重跑保留原订单、原截止、支付编号及票号；使用新命名空间演示一次新的购买：

```powershell
.\scripts\demo-payments.ps1 -BaseUrl http://127.0.0.1:18084 -Namespace W4PSECOND
```

命名空间须以 `W4P` 开头，随后最多 12 位大写字母、数字、下划线或连字符。同一命名空间的脚本按顺序执行，避免同时写入本地接续文件。

## 重启后主动查单恢复演示

两个可选参数分别控制渠道成功后的暂停点与恢复顺序，每次调用只使用一个：

| 参数 | 行为 |
| --- | --- |
| `-PauseAfterChannelSuccess` | 渠道成功持久提交后检查本地仍 PENDING_PAYMENT / ACTIVE / 两座 HELD、票数为 0、成功通知未送达，打印非敏感身份后返回；不投递通知、不创建取消订单 |
| `-RecoverBeforeDelivery` | 渠道事实保存后先调用运营恢复，检查已 PAID 并生成原两张票，再投递原成功及旧通知，验证不重复出票 |

使用新的独立命名空间开始，在应用运行时执行：

```powershell
.\scripts\demo-payments.ps1 -BaseUrl http://127.0.0.1:18084 -Namespace W4PRESTART -PauseAfterChannelSuccess
```

看到 `channel=SUCCEEDED、local=PENDING_PAYMENT、tickets=0` 后停止应用，再启动同一版本 jar，保留原数据库、命名空间与本地 JSON。随后执行：

```powershell
.\scripts\demo-payments.ps1 -BaseUrl http://127.0.0.1:18084 -Namespace W4PRESTART -RecoverBeforeDelivery
.\scripts\demo-payments.ps1 -BaseUrl http://127.0.0.1:18084 -Namespace W4PRESTART
```

第二次运行主动查回原渠道事实并先出票，接着投递原成功通知仍保留原两票；第三次完成普通重跑验证。参数本身不停止或重启应用，应记录实际停止/启动日志及同一 jar 的身份。已经 PAID 的命名空间不能使用暂停参数回退状态，脚本会明确拒绝；需要新的暂停点时创建新命名空间。

## 固定数据及接续

沿用 56 座规则测试厅：2 区域、5 排、2 票档，54 个可售座位、2 个不可售座位。原种子场次始终 DRAFT；购买只发生于独立的 `SESSION-PAYMENT-{Namespace}` 场次。新场次时间来自种子保存的原基准时间，默认开场为创建种子时的 UTC 时间加 30 天。

`.local/demo-payments/{Namespace}.json` 保存非敏感的场次、座位、下单键、支付键、原截止、支付编号及票号。每次 POST 前先保存请求意图；响应丢失后使用相同请求继续，不重新选座、不延长原期限。写入使用临时文件再替换原文件。

脚本不删除订单、票、通知、渠道事实或数据库卷。发现场次配置、状态、请求绑定或原票号发生变化时停止并保留证据。若首次渠道受理已经跨过原截止，不把同键当作新付款；先检查原状态，需要新的独立购买时使用新命名空间。

## 可观察的流程

| 操作 | 入口 | 必须观察的结果 |
| --- | --- | --- |
| 推荐并下单 | `GET /api/sessions/{id}/recommendations`、`POST /api/orders` | 推荐不占座；两座待支付订单及整组锁成立 |
| 发起支付、原键重放 | `POST /api/orders/{id}/payments`，`Idempotency-Key` | 一个原支付编号；最多一笔本次支付尝试，不续期限 |
| 仅保存渠道成功 | `POST /api/operations/payments/{number}/succeed` | 渠道成功已持久保存；本地订单状态仍与通知前相同 |
| 投递成功通知 | 查询渠道通知后 `POST /api/operations/notifications/{number}/deliver` | 订单 PAID、锁 CONSUMED、两座 SOLD、两张 VALID 票 |
| 重投成功、再投旧 PROCESSING | 同一投递入口 | 原票号及两张票数量不变，订单不倒退 |
| 权限核验 | 他人查询本人票、消费者控制渠道结果 | 分别返回 404、403 |
| 新订单发起进行中支付后取消 | `POST /api/orders/{id}/cancel` | 原渠道交易 CLOSED，订单 CANCELLED、锁 RELEASED、座位整组 AVAILABLE、没有票 |
| 重复取消 | 原取消入口 | 状态与原支付编号不变，不释放其他订单座位 |

脚本最终检查本演示场次 `SOLD=2、AVAILABLE=52、HELD=0、UNAVAILABLE=2`。第一份两座订单与票保留；第二份订单、关闭支付和通知历史同样保留。

通知传入本地结果入口的状态来自持久快照，因此可先投 SUCCEEDED 再投旧 PROCESSING。消费者不提交“支付成功”状态；运营演示控制仅产生渠道事实。通知接收者的本地结果事务成功提交后，模拟器才确认 `delivered_at`；确认前中断可以重投，所以本地结果入口必须幂等。

## 手动恢复入口

第四周提供运营手动恢复，第五周扫描复用同一服务。手动恢复只处理原支付编号，不新增支付尝试、不续期限；可以越过退避时间与人工介入标志，但仍遵守活动中的 60 秒领取租约，不抢占尚未到期的 token。自动恢复入口遵守退避与人工介入暂停规则。调试时查询 `GET /api/operations/orders/{id}/payment-state` 可以查看订单、支付及恢复信息，然后调用 `POST /api/operations/orders/{id}/recover`。

若渠道已成功、服务在通知处理前中断，重启后可以查询原事实并恢复出票；若已有取消意图，恢复须查单或关单后再决定出票或释放。渠道未知时保留 PENDING_PAYMENT / ACTIVE / HELD，不强行释放。连续失败 6 次或异常持续 10 分钟进入人工介入；退避间隔为 5、15、30、60、120 秒，之后封顶 120 秒。恢复使用 60 秒领取 token，事务提交失败后仍可重新查询原渠道事实。

以上恢复路径的自动化数据库测试和执行证据应以本轮测试报告为准。正常演示脚本不制造真实断电或自动扫描，也不代表二维码核销、退款已完成。

## 当前证据

实际环境：Temurin Java 21.0.12.1+1、Maven 3.9.9、MySQL 8.4.11；开发库 `ticketing:3307`，应用地址 `http://127.0.0.1:18087`。2026-10-10 **22:57:40 +08:00**，Flyway 在原 V3 上执行 V4；22:57:41 应用启动完成，PID 8416，运行 `target/ticketing-0.0.1-SNAPSHOT.jar`。此次没有重建开发库或删除旧订单。

| 演示 | 实际身份与结果 | 日志 |
| --- | --- | --- |
| 正常购买与取消 | W4PRUN，场次 19；订单 2 支付成功并保留两张票；订单 3 的进行中渠道交易关闭后安全取消 | `.local/week4-payment-demo-first.log`，首次种子基准为 22:57:52 +08:00，日志于 22:57:54 完成写入 |
| 原命名空间重跑 | 订单 2 / 3、原支付编号与两张原票全部复用，最终 SOLD=2 / AVAILABLE=52 / HELD=0 / UNAVAILABLE=2 | `.local/week4-payment-demo-repeat.log`，22:58:17 +08:00 完成写入 |
| 渠道成功后暂停 | W4PRESTART，场次 21、订单 4；渠道 SUCCEEDED，本地 PENDING_PAYMENT / ACTIVE / HELD=2，票数 0；未投成功通知，未创建取消订单 | `.local/week4-payment-restart-before.log`，22:58:18 +08:00 完成写入 |
| 重启后先查单恢复 | 原订单 4、原渠道编号主动恢复为 PAID / CONSUMED / 两票；再投原成功及旧 PROCESSING 通知不增票；订单 5 安全取消 | `.local/week4-payment-restart-after.log`，22:59:12 +08:00 完成写入 |
| 恢复后普通重跑 | 订单 4 / 5、原渠道编号和原两票不变，库存再次收敛到 SOLD=2 / AVAILABLE=52 / HELD=0 / UNAVAILABLE=2 | `.local/week4-payment-restart-repeat.log`，22:59:13 +08:00 完成写入 |

正常演示原支付编号为 `c14a52b5-77e4-4f56-b552-b07a0aef8d45`；原票号为 `043568fa-75dc-4377-b4f0-2aa09efb2340`、`7a59c1f3-6320-4f88-8748-71387e7bd22c`。重复成功及迟到 PROCESSING 均保持这两个票号；他人票查询返回 404，消费者模拟器控制返回 403。

重启恢复原支付编号为 `72ad6870-5365-4ea3-8216-19a36bc40605`，暂停时原成功通知为 `2900b02e-1800-4dff-8e62-2c693567f787`。停止 PID 8416 后重新启动同一路径 jar，22:59:06 新进程 PID 8004 开始启动，22:59:09 恢复在相同端口 18087 监听；Flyway 验证四份迁移并报告 V4 已是最新。启动证据分别在 `.local/week4-payment-app.log` 与 `.local/week4-payment-restarted-app.log`。重启后的主动查单先签发 `87cd9cda-ac0a-4dfc-981b-67661a63362b`、`9eda06e8-93a4-4857-aa72-e48cafd4abd2` 两票，随后通知投递和普通重跑仍复用这两票。

以上证明真实进程退出后，已提交的渠道事实与原购买意图可供新进程恢复；本次没有模拟机器断电或数据库不可用，也不将它等同于所有故障窗口已经覆盖。支付本地中途/提交前回滚、领取竞争和失败阈值另由本轮自动化验证：完整回归 **229 项 = 87 unit + 142 IT** 通过，其中 PaymentLifecycleIT 18 项、PaymentRecoveryIT 21 项。锁座基线单独执行的 1 项实验不计入这 229 项回归。

本机接续文件为 `.local/demo-payments/W4PRUN.json` 与 `.local/demo-payments/W4PRESTART.json`，只保存非敏感身份及请求参数；开发库保留这两套交易、票和通知。日志与接续文件位于被 Git 忽略的 `.local`，可在本机查阅；新机器运行脚本会获得新的实际编号。
