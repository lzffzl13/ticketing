# 本人票码查询与本机密钥操作

本轮凭证用于已经存在的原电子票。原订单 O1、原支付 P1 成功后签发 T1；消费者查询二维码，只为 T1 生成当前签名密钥下的文本与图片，不重新付款、出票、续期或占座。正式核销及每次尝试的审计仍在第六周实施。随后单票退款已按 ADR-0018 接入，状态变化与回流重售见[退款导读](refund-loop.md)。

## 1. 凭证和真实性边界

凭证为 `TKT1.<kid>.<ticketNumber>.<mac>`。mac 是前三段原文 UTF-8 字节的 HMAC-SHA256，使用完整 32 字节结果的无填充 Base64URL 编码。kid 标识受信配置中的密钥，ticketNumber 标识原票；改票号、版本或 kid 都会改变校验结果。

二维码承载该文本，不包含消费者身份、票状态或自报有效期。HMAC 通过只说明内容匹配当前受信密钥。第六周核销仍须查原票，并在写入处判断当前场次、`VALID` 状态及 `admission_open_at <= now < admission_close_at`，原子争夺一次成功权。旧票退款失效后，即使同座位已售出新票 T3，旧码也只能找到原 T1。多份截图、K1 和 K2 生成的不同码，都不增加 T1 的入场次数。

本人票记录保留历史；取码仅允许本人 `VALID` 票且当前时间严格早于入场截止，开放前也可取。取码时票有效不代表以后扫描仍有效，后续正式核销要读最新事实。静态码不能阻止拿到截图的人抢先使用。

| 入口 | 返回与边界 |
| --- | --- |
| `GET /api/orders/{orderId}/tickets` | 原有本人票记录查询，历史状态仍可见 |
| `GET /api/orders/{orderId}/tickets/{ticketId}/credential` | 本人原票标识、状态和凭证文本；成功响应 `Cache-Control: no-store` |
| `GET /api/orders/{orderId}/tickets/{ticketId}/qrcode` | 同一生成规则的 `image/png`；成功响应 `Cache-Control: no-store` |

未登录返回 401，角色不符返回 403；他人订单、票不存在或与路径订单不匹配统一 404；本人票不再 VALID 返回 409 `TICKET_NOT_VALID`，已达入场截止返回 409 `TICKET_ADMISSION_CLOSED`。两个新增接口都不公开“验码即有效”的结论，也不消费入场资格。

## 2. 首次准备与启动

先完成[本地环境](local-environment.md)的 Java 21 和 Docker MySQL 准备。在项目根目录执行：

```powershell
.\scripts\ticket-keys.ps1 -Action Init
.\scripts\ticket-keys.ps1 -Action Status
.\scripts\run-app.ps1 -Port 8080
```

Init 使用安全随机源生成独立 32 字节秘密及全新的 kid，既有密钥或操作历史存在时直接拒绝，不覆盖、不重置。命令只显示编号、状态和期限，不打印秘密。每个秘密文件先建立当前 Windows 用户独占 ACL，再写入秘密；备份目录同样取消继承，只允许当前用户访问。此保护针对本地演示环境，不宣称是生产密钥托管服务。

默认本机文件均在 Git 忽略的 `.local/`：

| 文件 | 用途 |
| --- | --- |
| `ticket-keys.properties` | 应用启动读取的签发/验证密钥集合 |
| `ticket-keys.metadata.json` | 无秘密的编号历史、指纹、停签时间、保留上界、退役/撤销记录 |
| `ticket-keys.backups/<操作编号>/` | 当前用户独占 ACL 的操作前后完整备份与操作说明，含秘密 |

Java properties 文件只允许 `activeKid=<编号>` 和 `key.<编号>=<标准 Base64 的 32 字节秘密>`，示例不提供可直接运行的固定密钥。activeKid 用于新生成票码，其余配置项只用于验证旧码。编号大小写敏感，同一个编号不能重新绑定其他秘密；移除和撤销后，编号仍永久保留在 metadata，脚本拒绝复用。metadata 中的 SHA-256 指纹还可检测手工把同编号改配另一秘密。

`run-app.ps1` 将环境变量 `TICKET_CREDENTIAL_KEY_FILE` 默认为上述 properties 的绝对路径，先执行 Status 校验，再沿用已有 .env 数据库与演示账号配置。缺失密钥时提示显式 Init，不静默生成临时密钥。IDE 或直接运行 jar 时也必须设置 `TICKET_CREDENTIAL_KEY_FILE`；应用启动校验失败应先修复配置，再启动，不能以新随机秘密绕过错误。若自定义本机文件路径，须位于本项目 `.local/`，Init/Status/轮换均使用同一个 `-KeyFile`。

```powershell
# 自定义仅用于本机隔离演示的文件，不能覆盖默认密钥。
.\scripts\ticket-keys.ps1 -Action Init -KeyFile .local/isolated/ticket-keys.properties
$env:TICKET_CREDENTIAL_KEY_FILE = 'D:\project\ticketing\.local\isolated\ticket-keys.properties'
.\scripts\run-app.ps1 -Port 18087
```

测试使用隔离测试密钥，不能复制本机秘密进入源码或测试资源。票码文本、PNG、账号令牌和真实密钥都只保存在被忽略的本机文件，避免进入普通终端日志、URL 查询参数或 Git。

## 3. 普通轮换：精确保留期限

假设 T1 原截图用 K1 生成。正常换钥的目标是新查询用 K2，合法 K1 截图在原入场窗口结束前继续使用。旧密钥的保留期限不是 7 天、30 天、登录有效期或支付截止。

操作顺序如下：

1. 核对所有运行本项目的 Java 进程，正常停止全部旧签发者。命令不会代为停止进程，不触碰其他项目。
2. 执行 Rotate。脚本必须成功检查全机 Java 进程；能识别为本项目或无法读取命令行的进程会阻止操作。可附 `-StoppedProcessId` 核对已记录的旧进程编号确实退出。受限环境无法执行全机检查时一律拒绝换钥，改在允许检查的本机终端操作，不能把“沙箱没有显示进程”当作已停签的证据。
3. 停签后，以 Docker 开发库的原应用账号，在只读事务中读取所有既有票关联场次的最大入场截止。查询不筛选票状态，因为没有持久化每次生成票码的日志，不能假装知道 K1 精确签过哪些票。
4. 记录 K1 的停签时刻、所有票的数量及最大截止，精确到 UTC 微秒，将 K1 改为只验证，生成全新 K2 并设为 activeKid。重新启动应用后生效。
5. 到达记录的 `retainUntil`，即 `UTC now >= retainUntil`，才可 Retire K1，移出验证集合，再启动。无票时上界为本次停签时刻，可立即退役。查询失败时正常轮换不更改任何密钥；历史或上界不明时继续保留，禁止猜测退役。

只读查询的核心是：

```sql
SELECT MAX(s.admission_close_at)
FROM ticket t JOIN session s ON s.id = t.session_id;
```

脚本同时读 COUNT，并以 UTC `yyyy-MM-ddTHH:mm:ss.ffffffZ` 保存上界。数据库统一以 UTC DATETIME(6) 保存业务时间；不使用本机时区去推算场次。已经开售的场次时间窗口继续遵守原有冻结规则。本地单实例在检查后也不得启动旧配置的签发者；本方案没有跨实例租约，不能用于不停机多实例滚动轮换。

```powershell
# 在自己的终端停止并核对所有本项目旧 Java 进程后执行；编号示例应换成实际值。
.\scripts\ticket-keys.ps1 -Action Rotate -StoppedProcessId 12345
.\scripts\ticket-keys.ps1 -Action Status
.\scripts\run-app.ps1

# retainUntil 到达后，再停止本项目应用并退役旧验证密钥。
.\scripts\ticket-keys.ps1 -Action Retire -Kid K1 -StoppedProcessId 23456
.\scripts\run-app.ps1
```

K1/K2 只是解释用编号，命令中的 `-Kid` 必须换成 Status 中实际编号。不能重新启用 K1 签发；需要新签发者就生成从未使用过的编号。保守上界可能让部分已退款/核销票对应密钥保留较久，这是无票码生成日志的代价。

## 4. 紧急撤销：没有旧码宽限

如果 K1 疑似泄露，不能继续普通兼容：先停止所有本项目旧签发/验证进程，再撤销 K1、生成并激活全新 K2，重新启动。

```powershell
.\scripts\ticket-keys.ps1 -Action Revoke -Kid K1 -StoppedProcessId 12345
.\scripts\ticket-keys.ps1 -Action Status
.\scripts\run-app.ps1
```

Revoke 把 K1 从 properties 的验证集合移除，并在 metadata 永久标记 revoked。新进程只从该受信集合按 kid 找密钥；K1 不存在便拒绝，**不尝试其他密钥、不接受裸票号、不读取旧备份、不留宽限期**。修改文件不能改变已经运行进程的内存，因此必须先停止旧进程，再以更新后的文件启动；停机到启动期间无入场服务。应用不热加载。

撤销当前 activeKid 不依赖数据库查询；原 active 就是被撤销项，不再需要兼容期限。撤销一个更旧的验证密钥时，也生成新的 activeKid；原 active 转为只验证，能查询则记录其保留上界，数据库不可用则保留其未知上界并禁止普通退役，紧急撤销仍可完成。后续不能凭固定天数移除该未知上界密钥。

旧 K1 截图全部失效，包括合法用户保存的截图。消费者重新登录、查询原 T1 得到 K2 码；原订单 O1、原支付 P1、原票号 T1 和票状态均不变。若原票已经退款、正在退款、已核销或已到入场截止，重新查询也不会恢复入场资格。这里不提供单张截图单独撤销能力。

## 5. 失败检测、备份与恢复

Status 和 run-app 预检会拒绝 properties/metadata 缺失或不一致、重复字段、非法编号/编码/长度、缺少当前签发项、同编号秘密被替换、已撤销/退役编号重新出现。每个密钥文件还对应一个独占操作锁，避免两个脚本把同一密钥集合交错改写；锁竞争直接拒绝。Java 启动还独立校验密钥文件；不能把脚本预检当作业务验证组件的替代。

每次变更先保存受限的 before/after 备份，然后先替换 metadata，再替换 properties。两份文件不是数据库事务：如果操作中断在两次替换之间，预检会因不一致失败，禁止启动；保留现场，根据同一操作目录的 after 文件完成该次操作。不要盲目重新 Init 或删除历史。

如果密钥只是丢失且没有泄露，停止应用后从同一受控备份恢复成对且一致的文件，再运行 Status 并恢复当前用户独占 ACL。若发生过紧急撤销，必须保留最新 metadata 的撤销历史，**禁止恢复包含已撤销密钥的旧状态**，应完成撤销操作的 after 状态或生成新的未使用编号。脚本预检能阻止“新 metadata + 旧 properties”的回滚，不能抵抗同时回滚全部历史或绕开脚本手工篡改配置；这属于本机受控操作边界，不宣称拥有外部不可回滚的密钥账本。

备份含秘密，仅用于当前本机受控恢复；不要上传、粘贴到聊天、提交仓库、打印内容或恢复到公共目录。撤销不会自动销毁受控历史备份，也不让已泄露秘密恢复可信。未经确认不要清理旧备份或 metadata；换机器的秘密迁移与更强的托管方案需另行规划。

## 6. 业务写入与验证范围

取码查询读原票与场次，不新增票/支付/订单业务写入；正常轮换只读开发数据库，Init/Rotate/Revoke/Retire 仅改受控本机文件，不改订单、支付或票表。取码失败修复后可重复请求，仍绑定同一张原票。核销、退票、状态原子变更与审计不由密钥脚本代办。

验收时应分别记录：文本与 PNG 解码一致、本人归属与角色矩阵、篡改/未知编号拒绝、重启稳定性、K1/K2 正常兼容、精确截止前不许退役、到期退役与紧急撤销、配置错误失败且不泄密。业务测试和实际演示证据记录在[第五周周报](../progress/week-05.md)，不能把本导读中的操作设计当作已经执行的结果。

应用启动后可用 `scripts/demo-ticket-credentials.ps1` 接续已有支付演示并查询原票的文本与 PNG。其保存的请求、票码和图片也属于本机秘密材料，按同样规则保留在被忽略的 `.local/`。

## 7. 关键代码与实际运维验证

| 位置 | 处理逻辑 |
| --- | --- |
| [TicketCredentialController](../../src/main/java/com/ticketing/trade/TicketCredentialController.java) | 本人文本/PNG 入口与禁止缓存响应 |
| [TicketCredentialQueryService](../../src/main/java/com/ticketing/trade/TicketCredentialQueryService.java) | 独立只读 RR 快照：订单归属、原票关系、票状态与场次截止 |
| [TicketCredentialService](../../src/main/java/com/ticketing/trade/TicketCredentialService.java) | 只读事务结束后生成文本/图片，编码失败不写业务表 |
| [TicketCredentialCodec](../../src/main/java/com/ticketing/ticketing/TicketCredentialCodec.java) | 严格载荷、HMAC 与受信 kid 选择；验证只返回原票号 |
| [TicketCredentialConfiguration](../../src/main/java/com/ticketing/ticketing/TicketCredentialConfiguration.java) | 固定 properties 字段与启动校验，不生成临时秘密 |
| [ticket-keys.ps1](../../scripts/ticket-keys.ps1) | 本机 Init/Status/Rotate/Revoke/Retire、ACL、独占锁和受控备份 |

自动化业务依据为 TicketCredentialCodecTest、TicketCredentialConfigurationTest、QrCodeRendererTest 和 TicketCredentialIT；完整执行结果以第五周周报为准。

2026-10-15 在 `.local/ticket-key-script-check/third/` 的独立测试密钥执行运维验证，未改默认真实密钥或数据库业务事实。停签核验时间为 `2026-10-15T03:38:39.035885Z`；只读开发库查到 8 张既有票，最大入场截止为 `2026-11-13T06:10:27.007640Z`，普通轮换精确保留该上界。这是全体既有票的保守上界，不声称测试密钥已经给这 8 张票生成过码。

已实际通过：独立生成与重启预检、当前用户独占 ACL、重复 Init 拒绝、独占锁竞争拒绝、K1→K2 双密钥兼容配置、截止前禁止退役、到期退役、K2 紧急撤销并激活 K3、撤销编号不可复用、旧 properties 配新 metadata 拒绝、仍存活的登记 PID 拒绝。到期成功分支仅把隔离 metadata 的保留上界改为 `2000-01-01T00:00:00.000000Z`，没有修改业务时间、数据库或真实场次。

检查中发现受限进程视图会漏掉仍运行的旧演示 PID 6148；提升检查确认其为本项目旧 jar 后才由根任务停止。脚本现要求全机 CIM 检查成功，失败一律拒绝变更，不以沙箱进程视图证明停签。另修正 PowerShell 自动解析 JSON 日期时丢失格式的问题，以 UTC 六位小数归一化保留精度。非秘密操作证据在 `.local/ticket-key-script-check/operations-evidence.log`，受限前后快照位于其隔离目录的 `keys.backups/`。

## 8. 场景讲解补充：读到有效不预留入场权

原订单 O1、原支付 P1 已成功签发 T1。消费者取码在同一个只读快照中检查本人归属、票关联、VALID 和入场截止，随后结束事务再计算文本/图片。因此图片生成失败只需重新查询原 T1，不重新支付出票。

若查询读到 VALID 后，另一请求完成 T1 退款，本次查询仍可能返回原票码。这不需要为了画图锁住票或阻止退款；后续正式核销必须再读当前事实。入场资格没有在查询时被提前保留。

同票重复生成、保存多份截图、普通换钥后同时持有 K1/K2 两份码，都只指向同一 T1；未来原子核销只能给 T1 一次成功。码绑定原票号而非座位，A1 重售签发 T3 后，T1 旧码不能转而获得 T3 的资格。

登录 Bearer 令牌决定谁能查询本人票，其 2 小时期限不作为票码入场期限；票码验证还须分别检查受信密钥、原票当前状态、所选场次与入场窗口。no-store 降低缓存留存，不阻止用户主动截图，完整静态截图被复制后仍可能抢先使用。
