# 第五周进度：超时取消、退票回流与票生命周期

- 更新日期：2026-10-14（Asia/Shanghai）
- 当前状态：首个自动超时/恢复扫描闭环已实现，完整 255 项回归通过；后台出票与重放、既有到期订单自动释放、真实进程重启自动恢复已验证。二维码与退款尚未实现；本闭环核心代码理解检查待完成。

## 实际起点

- 已阅读 AGENTS.md、PROJECT.md、PROJECT_PLAN.md、README、第四周周报和开放问题，并核对代码与 Git。
- 起始分支 codex/week4-order-holds，HEAD 8ade6d5；仅有原有未跟踪 img/，保留，不纳入本次交付。
- 第四周已实现登录下单、模拟支付、原子出票、安全取消和运营主动恢复，229 项通过的既有证据为 2026-10-10；本轮核心代码理解检查于 2026-10-13 完成。二维码、退款及正式核销尚未实现。
- 创建 codex/week5-ticket-lifecycle 分支，从当前已提交实现接续；未假定 main 已合并第四周代码。

## 本次范围

完成[自动超时与恢复扫描评审稿](../design/week-05-timeout-recovery-review.md)，先复用 ADR-0001 / 0015 的安全规则，不改变已确认的期限、退避、领取、人工介入或本地结果事务。

评审比较应用内 MySQL 扫描、延迟消息/独立组件和仅人工恢复，负责人回复“采纳建议，实施这个闭环”，已记录 ADR-0016。默认每轮结束 1 秒后运行、每批最多 50 个候选、可配置启停；不新增表或中间件。

已修复只读审查发现的两个边界：自动入口在取消副作用前锁内重验完整资格；过期未结算租约先计入中断故障并检查人工阈值，再决定是否安全取消。不能只依赖候选筛选，也不能因本地支付已 FAILED / CLOSED 绕过第 6 次中断门槛。

## 已实现内容

- PaymentRecoverySchedulingConfiguration 默认启用应用内调度，单轮错误后继续后续调度；测试配置禁用真实后台任务，避免修改其他测试夹具。
- PaymentRecoveryScanner 每次完整遍历固定最大订单 ID 上界，跨批推进，到尾回绕。单笔异常隔离；查询失败不推进游标；重启仅丢访问顺序，持久任务仍可发现。
- 候选查询覆盖从未支付且 next_recovery_at 为 NULL 的到期订单；有恢复时间时严格遵守退避，排除人工与活动租约。
- 复用 PaymentService.recoverIfDue。查询与领取、模拟器事实、本地结果分别为短事务，整批不包长事务；未知继续 HELD、渠道成功原子出票、关单成功才释放。
- 新增 scripts/demo-payment-recovery.ps1，保存原请求和票号，支持后台查单出票重放及原 15 分钟期限的两阶段超时演示。
- 调整第四周普通演示允许后台在通知投递前先行出票；暂停点需要关闭后台扫描，历史执行记录仍保留。
- 入口、主流程、数据变化、异常、事务与关键代码见[扫描导读](../development/payment-recovery-scanning.md)。

## 测试与交付状态

- W5-T01 至 T12 对应的扫描、恢复与调度测试已实现并执行；重启与真实后台行为的证据另列下节。
- 2026-10-13 23:58:14 +08:00，执行 scripts/test.ps1 -Integration，BUILD SUCCESS：87 项单元/API 测试 + 142 项集成测试，共 229 项；failures=0、errors=0、skipped=0。Java 21.0.12.1、Maven 3.9.9、MySQL 8.4，隔离测试库 ticketing_test:3308，Flyway V1–V4 校验通过。
- 日志保存在被忽略的 .local/week5-baseline-escalated.log；计数使用本轮 Maven 输出及 failsafe-summary.xml。target 里历史实验 XML 不并入本轮回归数。
- 最初受限运行停在 Mockito 测试 JVM 初始化，终止后在获准访问本机依赖的环境重新执行原脚本通过，未跳过测试或更改工程实现。
- 2026-10-14 14:11:55 +08:00，scripts/test.ps1 -Integration BUILD SUCCESS：94 项单元/API/调度测试 + 161 项集成测试，共 255 项；failures=0、errors=0、skipped=0。环境为 Windows 11 amd64、Java 21.0.12.1、Maven 3.9.9、MySQL 8.4.11，隔离库 ticketing_test:3308；V1–V4 校验通过，无新迁移。
- 新增 26 项：PaymentRecoveryScannerTest 5 项、PaymentRecoverySchedulingTest 2 项、PaymentRecoveryScannerIT 14 项、PaymentRecoveryIT 新增 5 项（本类合计 26 项）。覆盖真实写入后回滚、自动重验、人工门槛、租约、双扫描器及通知竞争、批量和固定上界回绕。日志 .local/week5-scanner-regression.log；Maven 本轮输出与 failsafe-summary.xml 为计数依据。
- 首次新代码回归的 94 项单元已通过，但打包被仍运行的第四周演示 PID 8004 占用目标 jar 阻塞。核对其本项目 jar 和 18087 端口后停止该旧演示，再完整回归通过；未跳过打包或测试。新演示用 .local/week5-ticketing.jar 的构建副本运行，避免继续锁住 target 输出。
- 演示脚本首次暴露场次编号需要全大写，已修正并成功重跑；保留已有种子，不删除数据。

## 真实应用与恢复演示

开发库 ticketing:3307，应用 http://127.0.0.1:18087；2026-10-14 14:12:40 +08:00 首次启动本轮构建副本，PID 36880，Flyway 校验四份迁移。数据库和历史交易均保留，没有缩短业务期限或修改应用 Clock。

| 演示 | 实际结果 | 证据 |
| --- | --- | --- |
| 原有到期未支付订单自动取消 | 订单 1 原截止为 2026-10-08 09:19:56.010743 UTC；启动前 PENDING_PAYMENT / ACTIVE、座位 2623/2624 为 HELD、支付/票均 0；启动后 CANCELLED / EXPIRED，两座 AVAILABLE、current_hold_id 为 NULL、原截止不变 | .local/week5-timeout-before.log（14:11:57 +08），.local/week5-timeout-after.log（14:13:09 +08） |
| 后台主动查单出票 | W5RRUN 独立场次，订单 6、原渠道成功；未投成功通知且没有调用人工恢复，后台生成两张 VALID 票、锁 CONSUMED、两座 SOLD | .local/week5-auto-payment-demo.log |
| 原请求重跑 | 原订单 6、期限、支付编号和两张票保持相同；成功通知仍未确认送达 | .local/week5-auto-payment-demo-repeat.log |
| 关闭扫描建立恢复起点 | W4PW5RST 场次 27、订单 8；渠道成功、本地 PENDING_PAYMENT / ACTIVE / HELD=2、票数 0，未投通知；关闭扫描进程 PID 34860 | .local/week5-restart-before.log，.local/week5-restart-disabled-app.log |
| 真实进程重启后自动出票 | 停止 34860，启用扫描启动同一 jar 为 PID 6148；14:16:18 +08 只读查询原订单，已 PAID / CONSUMED、两张 VALID 票、原成功通知 delivered_at 为空；没有人工恢复或投递动作 | .local/week5-restarted-app.log，.local/week5-restart-after.log |
| 新超时脚本建立阶段 | W5RTIME 订单 7，保存原 15 分钟截止，返回 WAITING_ORIGINAL_DEADLINE；该步骤不是新夹具取消验收 | .local/week5-timeout-fixture.log |

订单 6 原支付为 ad9e9958-0612-4d5c-b47e-8c2c028521d6；两票为 3eefbb49-3640-4758-a7d8-5509c11fbbb1、f2e84987-604d-4474-9dae-2d5e85218867，重放保持一致。重启订单 8 原支付为 3fa846b5-8700-4517-a3ac-f85255d75315，成功通知 b73507b7-de16-48ce-a018-eca158154bc2 仍未送达；新进程签发两票 0f534195-08e8-4d21-ae88-d947b2ef12aa、fa71eb62-34a8-46c5-8f8a-0839cea032b5。

日志和接续文件位于被忽略的 .local，只作为本机实际证据；新机器通过提交的脚本和测试获得新的编号。不宣称生产容量、精确释放时延、真实渠道或机器断电已验证。

## 后续顺序与待定决策

1. 收尾首个扫描闭环的文档与本地提交，再阅读核心代码并检查理解。
2. 逐项评审 Q-07 的二维码载荷、真实性验证与密钥轮换。
3. 评审退款结构、幂等、渠道事实与事务，再完成单票退票、失败恢复和回流重售。
4. 第六周接入正式核销与审计、完整退票/核销入口竞争及一致性检查。

第二周整周学习验收仍独立待办；已完成的第四周学习检查不重复要求确认。
