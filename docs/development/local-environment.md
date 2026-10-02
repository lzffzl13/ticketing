# Windows 本地开发环境

当前提供 Java 21 与 MySQL 环境。Spring Boot 骨架的运行说明见 backend-skeleton.md；业务表尚未创建。

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

首次运行生成随机密码并写入 .env，不覆盖已有文件。MySQL 官方镜像仅在空数据卷首次初始化时创建账号和数据库，之后修改 .env 不会自动更改已有账号密码。若密码失配，检查现有配置，在确认账号状态后使用 SQL 修改，避免反复重启。

start 脚本等待应用账号完成 SELECT 1 健康检查；check 脚本再次执行账号认证和版本、库名、字符集查询。连接错误不能视为健康。

Compose 创建两组独立数据卷。本地已有 MySQL 的默认端口 3306 不与容器的 3307/3308 冲突。Flyway 已初始化迁移历史表；业务结构将在评审后由 Flyway 创建。

## 3. 查看、停止与恢复

~~~powershell
docker compose --profile test ps
docker compose --profile test logs --tail 50 mysql mysql-test
docker compose --profile test stop mysql mysql-test
docker compose --profile test down
~~~

stop 和普通 down 保留数据卷，后续用 start 脚本恢复。日常操作不要加 -v；该参数会删除卷中的数据。

启动失败时根据日志区分引擎不可用、镜像拉取、端口占用、磁盘或密码问题，修复后重复 start。状态为 unhealthy 时先检查日志和账号查询结果，不通过删除数据来掩盖问题。只有明确的测试数据重建场景才考虑删除测试卷。

凭据、JDK 压缩包及本机路径均被 Git 忽略。重建环境依赖仓库中的 Compose、脚本、固定发布信息和迁移文件。
