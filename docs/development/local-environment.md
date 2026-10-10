# Windows 本地开发环境

当前提供 Java 21、MySQL 与 Spring Boot 应用。业务结构包含 V1 场馆/场次七表、V2 身份/订单/锁座六表及 V3 支付模拟器交易/通知两表，由 Flyway 在应用启动时迁移。工程运行说明见[后端骨架](backend-skeleton.md)，第四周登录与下单见[导读](order-holds.md)，模拟器内部服务见[导读](payment-simulator.md)。

## 1. 准备 Java 21

已有 JDK 21 时：

~~~powershell
.\scripts\use-java21.ps1 -JdkHome '你的 JDK 21 安装目录'
~~~

也可以使用项目内的固定 Temurin 版本：

~~~powershell
.\scripts\setup-java21.ps1
.\scripts\use-java21.ps1
~~~

setup 脚本从 Adoptium 官方发布下载压缩包，核对固定 SHA-256 后解压到被忽略的 .local/tools。它不会安装系统服务。use 脚本验证 JDK 主版本后，为当前 PowerShell 会话设置 JAVA_HOME/PATH 并输出 javac、Maven 版本。新终端需重新运行；IDE 的 Project SDK 和 Maven Runner 也需选择该 JDK。

下载失败时检查网络后重试；校验失败时检查压缩包和官方校验值，不能跳过 SHA-256。两个脚本都可重复执行。

## 2. 启动与验证数据库

先启动 Docker Desktop，使用 Linux containers。

~~~powershell
.\scripts\start-infra.ps1
.\scripts\check-infra.ps1
~~~

同时验证测试实例：

~~~powershell
.\scripts\start-infra.ps1 -IncludeTest
.\scripts\check-infra.ps1 -IncludeTest
~~~

| 用途 | 地址 | 数据库 / 应用账号 | 密码来源 |
| --- | --- | --- | --- |
| 开发 | 127.0.0.1:3307 | ticketing | .env 中 MYSQL_PASSWORD |
| 测试 | 127.0.0.1:3308 | ticketing_test | .env 中 MYSQL_TEST_PASSWORD |

首次运行生成随机密码并写入被忽略的 .env，保留已有文件及数据库密码。MySQL 官方镜像仅在空数据卷首次初始化时创建账号和数据库，之后修改 .env 不会自动更改已有数据库账号密码。若密码失配，检查现有配置，在确认账号状态后使用 SQL 修改，避免反复重启。

start 脚本等待应用账号完成 SELECT 1 健康检查；check 脚本再次执行账号认证和版本、库名、字符集查询。连接错误不能视为健康。

Compose 创建两组独立数据卷。本地已有 MySQL 的默认端口 3306 不与容器的 3307/3308 冲突。数据库容器健康只证明连接可用；应用启动后才由 Flyway 校验和执行 V1/V2/V3，不能把容器健康视为业务迁移已完成。

## 3. 启动应用与演示登录

```powershell
.\scripts\run-app.ps1
```

run-app 读取 .env 并传入 DB_URL、DB_USER、DB_PASSWORD 和 DEMO_PASSWORD。新环境生成随机 DEMO_PASSWORD；已有 .env 缺少该字段时，local-settings.ps1 追加随机值，不旋转已有数据库密码。密码与令牌不会由脚本打印。

应用启动时使用该密码创建 consumer1、consumer2、operator1 和 checker1 的 BCrypt 摘要，重复启动核对并复用账号，不静默重置密码。登录调用 POST /api/auth/login，后续以 Authorization: Bearer 携带返回令牌；管理写入口需运营账号，订单入口需消费者账号。公共只读查询、健康检查和 Swagger 允许匿名。已有种子/演示脚本会先登录并缓存运营令牌。

账号密码与本地配置不一致时应检查启动和认证日志，保留数据库事实。直接用 IDE 启动需配置上述环境变量；DEMO_PASSWORD 为空会跳过启动账号种子。演示命令、独立 W4 数据和重跑策略见[锁座演示](order-holds-demo.md)。

## 4. 查看、停止与恢复

~~~powershell
docker compose --profile test ps
docker compose --profile test logs --tail 50 mysql mysql-test
docker compose --profile test stop mysql mysql-test
docker compose --profile test down
~~~

stop 和普通 down 保留数据卷，后续用 start 脚本恢复。日常操作不要加 -v；该参数会删除卷中的数据。

启动失败时根据日志区分引擎不可用、镜像拉取、端口占用、磁盘或密码问题，修复后重复 start。状态为 unhealthy 时先检查日志和账号查询结果，不通过删除数据来掩盖问题。只有明确的测试数据重建场景才考虑删除测试卷。

凭据、JDK 压缩包及本机路径均被 Git 忽略。重建环境依赖仓库中的 Compose、脚本、固定发布信息和迁移文件。
