# 第四周登录与锁座下单演示脚本

本页只说明本地脚本和可重复演示。事务、状态与表结构见[首个闭环评审](../design/week-04-order-hold-review.md)。这一步支持登录、推荐、幂等创建待支付订单、整组锁座和本人订单查询；尚未实现支付、取消或自动超时释放。

## 1. 启动和账号

在项目根目录使用 PowerShell：

```powershell
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\run-app.ps1
```

`start-infra.ps1` 为新环境生成被忽略的 `.env`，其中包括随机 `DEMO_PASSWORD`。已有 `.env` 没有该字段时，`local-settings.ps1` 会追加随机密码，保留已有数据库密码。`run-app.ps1` 将 `DEMO_PASSWORD` 传给应用，应用创建固定账号及 BCrypt 密码摘要。脚本不输出密码或令牌。

| 账号 | 角色 | 本次用途 |
| --- | --- | --- |
| consumer1 | CONSUMER | 下单、重放、查询自己的订单 |
| consumer2 | CONSUMER | 争抢已锁座位、验证订单归属 |
| operator1 | OPERATOR | 原种子和管理演示写接口 |
| checker1 | CHECKER | 固定账号，核销入口尚未实现 |

公共查询继续允许匿名。共用 `week2-api.ps1` 在第一次管理写请求前登录运营账号，缓存本次脚本作用域的令牌；随后请求复用它。消费者演示分别登录两个账号并使用对应 Bearer 头。登录失败或角色错误会让演示失败，不会自动忽略或降为匿名。

## 2. 新下单演示

在另一个 PowerShell 窗口运行：

```powershell
.\scripts\demo-order-holds.ps1
```

默认 `W4` 命名空间包含独立的规则模板、DRAFT 快照种子及 `SESSION-ORDER-W4` 交易场次。模板和种子场次复用已有逻辑，交易不会修改原第二周或第三周演示数据。

脚本按以下顺序校验真实 HTTP 响应：

1. 运营账号创建夹具并开售；消费者账号登录。
2. 匿名查询两个连座候选，确认查询前后库存相同。
3. 保存购买请求，再由 consumer1 使用 `Idempotency-Key: order-W4` 下单；首次返回 201。
4. 同键同请求重放返回 200；订单 ID、锁 ID、订单支付截止及锁期限保持不变。
5. consumer1 能查询原订单；consumer2 使用新键购买同组座位返回 409 `SEAT_NOT_AVAILABLE`。
6. consumer2 查询 consumer1 的订单返回 404。
7. 验证 56 个场次座位中恰有 2 个 HELD、52 个 AVAILABLE、2 个 UNAVAILABLE。

这是按顺序请求的冲突演示；并发争抢由自动化集成测试验证，不能把本脚本的 409 当作并发性能证据。

## 3. 重跑、期限和恢复

脚本将非敏感请求参数和首次成功的订单/期限存入 `.local/demo-order-holds/W4.json`，不保存登录令牌或密码。购买参数在 POST 前保存，所以服务已落单但响应丢失时，重跑仍使用原座位和原幂等键；服务端返回原订单。成功后的重复运行返回 200，并核对原期限。

同一命名空间复用首次场次时间，后续 `-BaseTime` 不会悄悄把场次改为未来。种子场次与交易场次首次使用同一基准时间，开场为基准时间加 30 天；重复下单沿原期限重放。若最初请求未成功且场次已经过了新下单边界，重试会真实失败，需要另建夹具。

需要演示一次新的购买时，使用新的 `W4` 开头命名空间，最多 16 个字符：

```powershell
.\scripts\demo-order-holds.ps1 -Namespace W4-2
# 隔离另一轮，并明确指定首次生成时间：
.\scripts\demo-order-holds.ps1 -Namespace W4-3 -BaseTime ([DateTimeOffset]::UtcNow)
```

保留请求文件及其数据库夹具，不手动删除某一项后假装是全新环境。请求文件与夹具不匹配或既有数据改变时，脚本停止并保留数据；新命名空间用于新的验证。

**15 分钟到达不会自动释放座位。** 本次闭环尚未接入安全取消或超时处理，原订单继续保留 PENDING_PAYMENT 和 ACTIVE 锁，场次座位仍可能是 HELD。相同请求仍可以重放原订单；新购买不能占用原锁座位。脚本不删除开发交易，也不通过 SQL 人工释放来制造演示成功。

## 4. 旧脚本兼容

`seed-week2.ps1`、`demo-snapshot.ps1`、`demo-session-sales.ps1`、`seed-galaxy.ps1`、`verify-galaxy-seed.ps1` 和 `demo-seat-recommendations.ps1` 共用认证 helper，原命令继续使用。启动应用后，管理写请求会自动先取得运营令牌。只读查询不要求登录。

如果已有账号密码与本地 `.env` 不一致，登录应真实失败；检查启动日志和本地环境配置。不要把密码或令牌复制进演示报告，账号种子不应在脚本里静默重置已有账号。
