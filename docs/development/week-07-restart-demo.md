# 第七周真实进程中断与恢复演示

- 日期：2026-10-21（Asia/Shanghai）
- 入口：`scripts/start-week7-infra.ps1` 和 `scripts/demo-week7-restart.ps1`。
- 实际状态：原命名空间强制终止、受系统睡眠影响后的原编号续跑恢复、成功后业务只读验证均有执行证据；首个重启尝试失败记录保留。

## 为什么单独准备实验数据库

已有 18090 应用仍可能扫描整个开发库。新建命名空间只能隔离数据身份，不能阻止旧进程扫描新订单；若旧进程先恢复，无法证明是新进程重启后完成。因此使用 `compose.week7.yaml` 启动独立 MySQL 8.4.11，固定数据库/账号 `ticketing_week7`，默认本机端口 3309、独立 Compose 项目和卷。密码复用忽略 `.env` 中测试密码，演示账号仍使用 DEMO_PASSWORD，不打印凭证。

这个实例只服务实验，不改变首版运行架构。启动脚本不清开发库、测试库或历史实验，不删除卷，不停止已有应用。应用仍使用原 V1–V6 迁移、同一个打包 jar 和现有受控票码密钥。

## 运行

先按 README 准备 Java 21、Docker、`.env`、应用 jar 和票码密钥。已有密钥不要重新初始化：

```powershell
.\scripts\start-week7-infra.ps1
.\scripts\demo-week7-restart.ps1 -Namespace W4PW71021A -KeyFile .local/ticket-credential-runtime/ticket-keys.properties
```

KeyFile 示例是本机既有密钥位置，新机器应传自己的既有受控文件。默认 jar 为 `target/ticketing-0.0.1-SNAPSHOT.jar`，可通过 `-JarPath` 指定；独立应用端口默认 18091，数据库端口默认 3309。主命名空间最长十四字符，内部追加 P / R，符合已有演示脚本最长十五字符约束。新的独立实验使用新命名空间。

## 流程、数据与事务

1. 复制指定 jar 到忽略目录 `.local/demo-week7-restart/<Namespace>/application.jar`，保存 SHA-256。使用原密钥，隐藏启动一个禁用支付/退款/一致性后台扫描的应用。
2. 复用原 `demo-payments.ps1 -PauseAfterChannelSuccess` 建立 P 场次：渠道 SUCCEEDED，原单仍 PENDING_PAYMENT、ACTIVE、两座 HELD、零票、通知未确认。
3. 用另一 R 场次复用退款演示：原两票购买成功，只申请其中一票退款；渠道退款 SUCCEEDED，本地仍处理中，原票 REFUNDING、座位 SOLD、成功通知未确认。
4. 再次读取并验证两组暂停事实，保存原编号与快照。核对当前调用创建的进程对象、PID、创建时间、java.exe、精确副本 jar 路径和端口，强制终止它。不会从旧证据读取 PID 来停止进程。
5. 同 jar、同密钥、同数据库重启，开启原持久扫描。后续仅做业务查询，必要时登录；不调用运营 recover、结果控制或通知 deliver 来推进结果。
6. 观察原支付订单 PAID / CONSUMED / 两张原身份 VALID 票 / 两座 SOLD，支付成功通知仍未主动投递；原单票退款 SUCCEEDED、原票 REFUNDED、座位 AVAILABLE、另一票仍 VALID，原收款不变。退款恢复包含其通知确认。
7. 对两个场次读取一致性报告，要求无差异、未决与人工恢复均零，原十五分钟期限和所有业务身份不变。最后只停止本次创建的应用进程并还原进程环境变量，MySQL 数据和日志保留。

渠道先独立提交，本地出票或退款回流沿原完整事务提交。这里的真正进程终止发生在请求之间、渠道已提交且本地尚未处理的窗口，证明持久事实可在重启后重新发现；不宣称在任意 SQL 之间精确杀进程或数据库断电。事务内部 SQL 后回滚由既有异常注入测试覆盖，两类证据分别记录。

## 重跑、失败与保留证据

成功后相同命名空间自动进入业务只读验证，也可加 `-VerifyOnly`。新进程关闭扫描，核对原票/支付/退款与报告，不新增购买、退款或核销。首次真实恢复快照和完成时间保留，后续观测放在对应 invocation，避免重跑覆盖原始证据。

脚本保存暂停、进程终止及恢复阶段。失败保留进度与原演示请求，先查应用日志、原业务编号、租约、数据库与密钥，再用相同参数续跑；不得为使脚本通过而 SQL 改状态、缩短期限或删除历史。若身份读取失败，脚本保守拒绝停止；可能留下本次启动的进程，需核对日志和实际进程后人工处理。不会误停已有 18090 应用。

非敏感证据为 `.local/demo-week7-restart/<Namespace>/evidence.json`。已有退款演示状态还保存原二维码，只留忽略目录，不能整份复制进 Git。导出的公开证据须显式挑选原编号、状态、报告和进程元数据，不包含密码、Bearer、票码、签名密钥或恢复 token。

可在实验结束、应用已退出后停止独立 MySQL，保留卷和数据：

```powershell
docker compose --project-name ticketing-week7-reliability --file compose.week7.yaml --env-file .env stop mysql-week7
```

重新执行启动脚本即可复用。不要用删除卷的命令替代停止；不影响原 `compose.yaml` 的开发/测试服务。

## 本轮实际执行

2026-10-21，命名空间 `W4PW71021A`，HTTP 18091，独立 MySQL `ticketing_week7:3309`。生产 Java、迁移和配置默认值均未改，复用已有打包产物；jar SHA-256 为 `38A07855167888D58658C31F4B9B9512F1D77C0346CB544C7B0A61286837BE14`，与第六周已验证的同一业务二进制一致，不把旧文件构建时间写为今日重新构建。脚本副本与原受控密钥在各次启动间不变。

| 实际步骤（Asia/Shanghai） | 证据与结果 |
| --- | --- |
| 11:00:08 启动禁扫描 PID 42612 | P 场次 2 / 原订单 1：原支付 `dd54de6e-ee22-46df-9198-eeb6a0be85fe` 渠道成功，本地 PENDING_PAYMENT、两座 HELD、零票；R 场次 4 / 原订单 2：退款 1 渠道成功，本地 PROCESSING、原票 1 REFUNDING、座位 185 SOLD |
| 11:00:25.101 保存双暂停，11:00:25.646 强制终止 | 精确核验自己的进程对象/创建时间/java/副本路径/端口后停止 42612；未动原 18090 / PID 22968 |
| 首次重启 PID 6296 失败 | 11:00:39 记录应用 Started，随后至 11:21:29 日志中断，连接池报告约 1246–1249 秒等待和连接已关闭，启动账户初始化失败；脚本保留 PROCESS_TERMINATED 与原身份，清理自己的 6296 |
| 系统睡眠证据 | Windows Kernel-Power 记录 316885 / EventID 507，11:21:29.365，SleepEntered=true、DisconnectedStandby=true，SleepDurationInUs=1245145359（约 20 分 45 秒），与应用日志空白重合；不能由此认定每条连接关闭的精确原因，首轮不作为恢复时延样本 |
| 11:23:11 同命名空间续跑 PID 12252，11:23:20 完成观察 | 未重建订单/支付/退款，启用扫描后原单 1 PAID / CONSUMED，原座位 73/74 SOLD，新签发票 3/4 VALID；退款 1 SUCCEEDED、原票 1 REFUNDED、座位 185 AVAILABLE，原票 2 仍 VALID，原收款不变 |
| 11:25:00–11:25:11 业务只读验证 PID 22600 | 关闭扫描，只查询原业务及报告，票 ID 保持 3/4；首次恢复快照、原完成时间与一致性报告未被覆盖，后续记录归入该 invocation；结束清理 22600 |

原支付截止 **11:15:21.069253 +08** 未被缩短或延长。续跑已过截止，但渠道在截止前已经受理并成功，恢复按原成功事实出票，符合“截止结束新支付资格、已受理支付继续明确结果”的既定规则。本轮系统睡眠不是刻意安排的到期性能实验。

两个场次最终 `consistent=true`、`differences=[]`，未决与人工恢复数量均零。P 场次支付成功通知仍未投递，由后台查单完成；R 场次退款成功通知已由退款恢复确认。R 场次另含旧支付演示正常创建的安全取消订单 3，报告正确包含其历史。

公开执行快照、逐次进程身份与睡眠摘要见[第七周真实重启证据](../testing/results/week7-real-restart-20261021.json)。本机完整日志为 `.local/week7-restart-demo.log`、`week7-restart-demo-resume.log`、`week7-restart-demo-verify.log`；敏感原退款请求仍仅存忽略目录。没有新增支付、退款或核销来替代失败原单，也没有手动 recover/deliver 推进续跑。

本轮结束后按上述 stop 命令停止隔离 MySQL、保留卷，18091 无监听；原开发/测试实例仍健康，18090 仍是原 PID 22968。源码规范化摘要与验证范围见[交付清单](../testing/results/week7-engineering-20261021-manifest.json)。
