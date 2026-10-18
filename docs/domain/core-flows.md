# 核心业务流程

## 1. 文档目的

本文档把核心用例、领域关系和状态迁移串成端到端流程，用于评审事务边界、外部交互和异常恢复。流程图只表达已确认的业务规则，不提前决定控制器、服务类或消息中间件的实现。

当前状态：第一周已评审版本。

## 2. 正常购票与核销

```mermaid
sequenceDiagram
    autonumber
    actor Customer as 消费者
    participant App as 票务应用
    participant DB as MySQL
    participant Pay as 支付模拟器
    actor Checker as 核销人员

    Customer->>App: 查询场次座位图 / 请求连座推荐
    App->>DB: 读取场次座位快照和当前状态
    DB-->>App: 返回座位、票档和可售状态
    App-->>Customer: 展示座位或推荐候选

    Customer->>App: 提交座位组合和幂等标识
    App->>DB: 开启本地事务
    Note over App,DB: 校验售卖状态、孤座和全部座位；推荐组合另校验连座
    App->>DB: 全部座位 AVAILABLE → HELD
    App->>DB: 创建 ACTIVE 锁、明细、订单和订单项
    DB-->>App: 提交成功
    App-->>Customer: 返回待支付订单和截止时间

    Customer->>App: 截止前发起支付
    App->>DB: 创建 INITIATED 支付记录
    App->>Pay: 以支付编号幂等发起支付
    Pay-->>App: 受理 / 异步处理
    Pay->>App: 支付成功回调

    App->>DB: 开启支付成功本地事务
    Note over App,DB: 支付、订单、锁、整组座位和全部票原子变化
    App->>DB: 支付 → SUCCEEDED，订单 → PAID
    App->>DB: 锁 → CONSUMED，座位 HELD → SOLD
    App->>DB: 为每个订单项签发 VALID 电子票
    DB-->>App: 提交成功
    App-->>Pay: 幂等确认已处理

    Customer->>App: 查询并展示电子票
    Checker->>App: 在当前场次扫描票码
    App->>DB: 条件更新电子票 VALID → CHECKED_IN
    App->>DB: 追加 SUCCESS 核销记录
    DB-->>App: 同一事务提交
    App-->>Checker: 核销成功
```

### 2.1 必须记住的边界

- 查询和推荐只是快照，不占用座位；提交时必须重新校验。
- 锁座不是先锁一个、再锁下一个，而是在一个事务中整组成功或失败。
- 调用支付模拟器不和 MySQL 事务捆绑；模拟器成功后，本地通过幂等回调或查单完成支付结果应用。
- 支付成功的本地事务必须覆盖订单、整组座位、锁和全部电子票，不能只出票一部分。
- 核销以电子票 `VALID → CHECKED_IN` 的条件更新争夺唯一成功权，核销记录保留每次尝试。

## 3. 支付截止、查单与关单

```mermaid
sequenceDiagram
    autonumber
    participant Job as 超时任务
    participant App as 票务应用
    participant DB as MySQL
    participant Pay as 支付模拟器

    Job->>App: 扫描 payment_expires_at 已到的待支付订单
    App->>DB: 读取订单、锁和进行中支付

    alt 截止前已有支付记录
        App->>Pay: 按支付编号主动查单
        alt 已支付
            Pay-->>App: SUCCEEDED
            App->>DB: 幂等执行支付成功本地事务
            Note over App,DB: 订单 PAID、锁 CONSUMED、座位 SOLD、签发票
        else 明确未支付
            Pay-->>App: 未支付
            App->>Pay: 请求关闭支付交易
            alt 关单成功
                Pay-->>App: CLOSED
                App->>DB: 安全取消本地事务
                Note over App,DB: 订单 CANCELLED、锁 EXPIRED、座位 AVAILABLE
            else 关单失败或状态已变化
                Pay-->>App: 结果不确定
                App->>DB: 保持订单、锁和座位不变
                Note over App,DB: 稍后重试或由对账处理
            end
        else 查单失败或状态未知
            Pay-->>App: UNKNOWN / 暂不可用
            App->>DB: 保持订单、锁和座位不变
            Note over App,DB: 正确性优先，暂时多占座
        end
    else 截止前没有支付记录
        App->>DB: 安全取消并释放整组座位
        Note over App,DB: 从未发起外部支付，无交易需要查单或关单
    end
```

### 3.1 截止时间的三个含义

```text
payment_expires_at 到达
  → 原用户不能再发起新支付
  → 不代表座位已经释放
  → 不代表截止前已受理的支付失败
```

如果成功回调和关单同时发生，支付模拟器只能形成一个最终结果。本地无论通过回调还是主动查单观察到结果，都进入同一个幂等处理入口。

## 4. 单票退票与座位回流

```mermaid
sequenceDiagram
    autonumber
    actor Customer as 消费者
    participant App as 票务应用
    participant DB as MySQL
    participant Refund as 退款模拟器

    Customer->>App: 在 admission_open_at 前申请退一张票
    App->>DB: 条件更新票 VALID → REFUNDING
    App->>DB: 创建 REQUESTED 退票单
    DB-->>App: 同一事务提交

    App->>Refund: 以退票编号幂等发起退款
    Refund-->>App: 受理 / 异步处理

    alt 退款成功
        Refund->>App: 成功回调
        App->>DB: 开启退款成功本地事务
        App->>DB: 退票单 → SUCCEEDED
        App->>DB: 电子票 → REFUNDED
        App->>DB: 对应座位 SOLD → AVAILABLE
        App->>DB: 更新订单为部分或全部退款
        DB-->>App: 提交成功
    else 退款失败
        Refund->>App: 失败结果
        App->>DB: 退票单 → FAILED，票 REFUNDING → VALID
        DB-->>App: 同一事务提交
    end
```

退票截止只限制发起新申请。开放前已受理的退票即使在 `admission_open_at` 之后收到结果，也必须继续处理。若退款失败后已经进入入场时间，票恢复 `VALID` 并可在时间窗口内核销，但不能再次申请退票。

## 5. 退票申请与核销竞争

```mermaid
flowchart TD
    A[电子票状态为 VALID] --> B{两个请求并发读取}
    B --> C[退票申请尝试<br/>VALID → REFUNDING]
    B --> D[核销尝试<br/>VALID → CHECKED_IN]
    C --> E{谁先完成条件更新}
    D --> E
    E -->|退票申请成功| F[票为 REFUNDING<br/>核销失败并记录 REFUNDING]
    E -->|核销成功| G[票为 CHECKED_IN<br/>退票申请被拒绝]
```

第六周真实实现统一先取得场次写锁，再锁订单及所需座位/票，锁后重新取时间。新退票要求 `now < open`，核销要求 `open <= now < close`，两者的合法受理窗口互斥；跨开放边界的锁等待测试检查各自实际获得锁时的资格。开放前已受理的退款继续处理，核销则看到 REFUNDING / REFUNDED 并追加拒绝审计。不要把图中的并发读取理解为同一业务时刻两个操作都具备成功资格。

胜负不由“哪个接口先查询”决定，而由谁先成功执行带当前状态条件的更新决定。最终不能同时存在成功退票和成功核销，但核销失败尝试仍需要留痕。

## 6. 退款回流后的重新销售

```mermaid
flowchart LR
    A[旧票 VALID] -->|申请退款| B[旧票 REFUNDING]
    B -->|退款成功| C[旧票 REFUNDED]
    C --> D[座位 AVAILABLE]
    D -->|新订单锁座支付| E[座位 SOLD]
    E --> F[签发新电子票 VALID]
    C -.永久保持失效.-> G[扫描旧票返回 REFUNDED]
```

场次座位可以被重新出售，电子票不复活。历史上同一个场次座位可以对应多张电子票，但同一时刻最多一张有效票。

## 7. 候补匹配（条件增强）

```mermaid
flowchart TD
    A[座位因取消、超时或退款回流] --> B{存在可匹配候补?}
    B -->|否| C[座位公开保持 AVAILABLE]
    B -->|是| D[按登记顺序选择候补请求]
    D --> E[确定性推荐满足票档、人数、连座和孤座规则的组合]
    E --> F{原子锁座是否成功?}
    F -->|否| G[刷新状态并继续匹配]
    F -->|是| H[候补 OFFERED<br/>创建普通待支付订单]
    H --> I{限时支付结果}
    I -->|成功| J[候补 FULFILLED 并出票]
    I -->|取消或安全超时| K[候补结束并释放座位]
    K --> D
```

候补复用普通订单和支付流程。具体采用回流事件触发还是定时扫描，属于实现前仍需比较的方案，不由本图决定。

## 8. 流程与文档追踪

| 流程 | 主要状态 | 主要测试场景 |
| --- | --- | --- |
| 正常锁座支付 | `AVAILABLE → HELD → SOLD`、`PENDING_PAYMENT → PAID` | SCN-04 至 SCN-15 |
| 支付超时 | `ACTIVE → EXPIRED`、`PENDING_PAYMENT → CANCELLED` | SCN-11 至 SCN-16、SCN-27 |
| 单票退票 | `VALID → REFUNDING → REFUNDED/VALID` | SCN-17 至 SCN-22 |
| 在线核销 | `VALID → CHECKED_IN` | SCN-19、SCN-23 至 SCN-25 |
| 候补匹配 | `WAITING → OFFERED → FULFILLED/EXPIRED` | SCN-28 至 SCN-31 |

## 9. 下一步

使用这些流程和测试场景完成第一周剩余产出：整理一份状态迁移与不变量评审摘要、初始化 Git 仓库，并定义固定虚拟场馆的种子数据草图。数据库表结构和 Spring Boot 工程骨架在这些内容完成后进入第二周。
