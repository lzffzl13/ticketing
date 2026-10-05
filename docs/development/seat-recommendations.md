# 第三周座位图与连座推荐导读

本轮交付完整场次座位图、确定性的连座候选和可共用的选座规则。推荐只读取数据，响应中的 `reservationMade=false` 表示没有取得座位占用权；后续提交订单仍需重新读取并原子校验。方案与新增决策见[第三周评审方案](../design/week-03-seat-recommendation-review.md)，既有孤座规则见 [ADR-0002](../decisions/0002-forbid-creating-new-orphan-seats.md)。

本文说明脚本、算法和验证方法；实际执行日期、结果及负责人学习验收由[第三周进度记录](../progress/week-03.md)分别记录。脚本存在不代表 HTTP 演示或性能实验已执行。

## 1. 入口与接口

先按 README 启动 MySQL、执行 Java 测试并启动应用。以下命令只访问本机 HTTP：

~~~powershell
.\scripts\run-app.ps1
~~~

在另一个 PowerShell 窗口运行：

~~~powershell
.\scripts\demo-seat-recommendations.ps1
~~~

如应用监听其他本机端口，增加 `-BaseUrl http://127.0.0.1:18081`。默认 `-Namespace W3` 使用独立规则测试厅，不修改 DEMO 星河场馆；可用 `-Namespace W3-OTHER` 构造另一套数据。`-BaseTime` 沿用种子脚本的可注入基准，重复运行使用已保存场次的原时间，不刷新快照或开售时间。

| 接口 | 输入与结果 |
| --- | --- |
| `GET /api/sessions/{id}/seat-map` | 完整区域、排、座号、坐标、过道、票档、价格和库存状态，另有销售状态、票档启用状态与 `selectable` |
| `GET /api/sessions/{id}/recommendations` | `quantity` 必填正整数；可选 `areaCode`、本场次 `priceTierId`；`limit` 默认 5，范围 1–20 |

座位图的 `selectable` 需要同时满足场次 `ON_SALE`、座位 `AVAILABLE` 和票档启用。`DRAFT` 与 `STOPPED` 仍返回完整布局，所有座位的 `selectable=false`。这不会把库存状态改写为 `UNAVAILABLE`。关闭某票档同样不改变其座位的库存或票价快照。

候选返回座位快照、总价、`centerDistanceSquared` 与原因。诊断字段包括当前筛选下可参与推荐的座位数、连续段数、人数不足的段数、因新增孤座排除的窗口数、截断前全部合法候选数。合法条件没有候选时返回 200 和空数组；未知场次 404，非法参数或跨场次筛选条件 400，非售卖场次推荐 409。

`queriedAt` 由 `Clock` 提供，用于说明响应生成时间。它不表示锁定期限，也不是数据库版本标识；客户端应刷新结果，但第四周正确性仍依赖提交时的后端检查。本轮沿用仅监听本机的演示接口，Q-05 登录令牌和个人数据权限仍需在交易鉴权前实施。

## 2. 主流程与规则顺序

catalog 先读取场次、票档开关和全部 `session_seat`，将明确的输入传给 inventory；不直接使用其他业务包的 Mapper。查询只读快照，不读取当前模板，即使来源模板已修改或删除，显示和推荐仍按保存的布局计算。

纯算法输入是完整场次布局、启用票档 ID、可选区域/票档、人数、返回条数与 `forbidNewOrphan`。算法依次执行：

1. 从全部布局确定每个区域的几何中心；不可售、已锁定与已售座位也参与，库存变化不会移动推荐中心。
2. 按区域、排、票档、过道及座号整理完整布局；不能先删除部分座位再把剩余座位拼成一个段。
3. 相邻座号不连续、跨排/区域/票档/过道或出现非可售座位时切开连续可售段，再按票档启用状态与请求条件过滤整段。坐标距离不作为连座依据。
4. 在长度不少于人数的段内枚举人数大小的连续窗口；长度不足的段计入诊断。
5. 启用孤座限制时排除本次制造新单座的窗口；已有孤座可以买，其他段原有孤座不妨碍当前合法选择。
6. 计算总价与中心距离，排序后截取 `limit`；全部合法候选数仍单独保留。

区域中心为全部座位坐标边界框的中点。候选中心为成员坐标的算术平均，平均值固定保留 12 位小数、`HALF_UP`；距离平方由该平均值与区域中心进行 BigDecimal 运算得到。排序先使用区域展示顺序，区域同序时以区域代码、来源区域 ID 破同分；同一区域按距离平方、排排序、排标签、起始座号及座位 ID 稳定排序。不同区域的坐标系独立，不据此比较两个区域的物理距离。首版没有用户位置偏好，也不自动推荐不连座组合。

共用规则组件对整组选座比较选择前后的可售连续段：只有选择前不存在、选择后出现的单座身份才违反规则。不能仅比较孤座总数，也不能按用户列表顺序逐座模拟。例如原有孤座 9 被买走，同时制造新孤座 1，前后总数相同仍必须拒绝。手动关闭连座时也按完整选择集合一次校验孤座。

设完整布局有 `N` 个座位、截断前有 `K` 个合法候选、每个候选人数为 `q`，本版时间复杂度为 `O(N log N + Kq + K log K)`，空间复杂度为 `O(N + Kq)`。连座窗口的孤座判断直接检查选择后左右剩余长度是否为 1，不逐窗口重扫全厅；完整手动选择则由共用校验比较前后结构。`limit` 在全部合法候选评分、生成和排序后才截断，因此返回 5 组不表示只计算 5 组，较大候选集仍有上述成本。

## 3. 数据变化、事务与异常

座位图与推荐在一个只读 `REPEATABLE READ` 本地事务中读取场次、票档和座位，避免拼接不同版本；不使用 `FOR UPDATE`，不创建订单或座位锁。事务读取一致不代表响应后库存不会变化。第四周需把共用校验放进最终锁座事务，重新检查全部座位，再整体成功或失败；本周的状态变化测试不能称为已完成原子锁座验证。

演示脚本创建 `VENUE-RULES-W3`、种子 DRAFT 场次以及独立 `SESSION-RECOMMEND-W3` 场次。它先核对已有布局、库存和票价，发现差异时停止并保留数据。第一次开售后不能退回 DRAFT，因此新建/已有 DRAFT 演示场次最终保留 `ON_SALE`；原先 `ON_SALE` 或 `STOPPED` 则在 `finally` 恢复原销售状态，并恢复各票档原开关。场次首次开售时间永久保留。

每个管理 API 自己提交数据库事务；跨请求脚本不是一个大事务。正常异常退出会进入 `finally`，若应用断开或脚本进程被终止，恢复请求也可能无法完成。重启后可重复运行同一命名空间，先核对布局再继续演示；运行期间的销售/票档开关属于该命名空间专用夹具。脚本不自动清库，不修复布局或改价，也不把中间状态恢复称为杀进程实验。

## 4. 演示断言与测试依据

规则测试厅共 56 座、54 可售、两区域、五排，每排中央过道切为两个段。默认人数 2 时完整布局有 25 个合法组合，返回条数默认截为 5。人数 7 超过每段长度，诊断显示 10 个段人数不足。脚本进一步验证：

- 完整座位图 ID 不重复，坐标、座号、过道、库存与票价逐项对应已保存快照。
- 候选满足人数、同区域/排/票档/过道、连续座号、启用票档与正确定价；按座位身份比较前后，未制造新孤座。
- 重复查询的候选及顺序、座位图布局不变，查询前后场次、票档、座位持久化字段完全一致。
- 关闭 A 档后池座仍有 35 个 `AVAILABLE` 座位，但不可选且不再被推荐；楼座仍有 8 个合法两座组合。
- 暂停后座位图仍有 56 座但全部不可选，推荐返回 409；恢复阶段没有修改座位快照。

API 与数据库自动化测试另外覆盖布局快照隔离、非法筛选、输入校验、销售状态和最新状态重读；纯规则测试覆盖已有孤座、过道、不可售/已占用位、票档与座号边界。依据见 [SCN-06 至 SCN-08](../testing/invariant-scenarios.md)、[规则测试数据](../data/seed-data-design.md)、`SeatQueryIT`、`SeatSelectionRulesTest` 与 `SeatRecommendationEngineTest`。只有实际测试命令通过后才在周报记录“已执行通过”。

## 5. 可重复算法基线

编译本版 Java 后运行：

~~~powershell
.\scripts\benchmark-seat-recommendations.ps1
~~~

PowerShell 负责选定 Java 21、记录 Windows/CPU/内存、Java/Maven 版本及生成参数；`SeatRecommendationBenchmark.java` 通过 `target/classes` 调用当前纯 engine，完全不访问数据库或 HTTP。本版的实时扫描算法是以后优化的对照基线，本轮不声称加速倍数。

默认参数为两区域、每排 20 座、每排两个等长过道段、三个按整排循环分配的票档、5% 不可售概率、固定种子 `20260928`、人数 2、返回 5、禁止新孤座。1,000 / 5,000 / 10,000 座对应每区 25 / 125 / 250 排。每个规模使用 `seed + 座位总数` 作为有效种子，`java.util.Random` 按固定顺序生成不可售状态，再以有效种子 XOR `0x5DEECE66D` 固定打乱输入；结果记录实际可售数和布局 SHA-256。不可售比例是概率，实际数量可能不同。

每个规模单线程预热 50 次，正式采样 100 次。计时范围是单次完整 `recommend` 调用：包括整理输入、分段、窗口/孤座过滤、BigDecimal 评分、候选排序和条数截取；排除数据生成、预热、Java 源码编译、消费结果与文件写入。使用 `System.nanoTime` 保留原始纳秒，汇总为微秒的均值、中位数、95 分位、最小和最大值；分位数采用 nearest rank。使用默认 JVM GC，不主动触发回收，运行期间暂停计入样本。

可用 `-Warmup 100 -Samples 200` 增加采样；`-SeatCounts`、`-AreaCount`、`-SeatsPerRow`、`-SegmentsPerRow`、`-TierCount`、`-UnavailableRatio`、`-Seed`、`-Quantity`、`-Limit` 可改变输入。座位总数必须能整除 `AreaCount * SeatsPerRow`，每排座数必须能被过道段数整除。同参数、同种子生成同输入。

输出为被 Git 忽略的 `.local/seat-recommendations-<UTC时间>.environment.json`、`.summary.csv` 和 `.samples.csv`。环境文件先记 `STARTED`，完成记 `COMPLETED`，失败记 `FAILED`；实际执行成功仍需核对 CSV 规模、样本数和非空结果。源码和生成参数保留在仓库，机器日志不提交。这些指标仅表示本机单线程纯算法耗时，不能外推为 API 延迟、数据库吞吐、并发锁座或生产容量。

## 6. 核心代码位置与学习检查

| 位置 | 需要能解释的内容 |
| --- | --- |
| `src/main/java/com/ticketing/catalog/SeatQueryController.java` | 路由、请求参数与统一响应 |
| `src/main/java/com/ticketing/catalog/SeatQueryService.java` | 只读一致性事务、销售与筛选前置校验、座位图编排 |
| `src/main/java/com/ticketing/catalog/SeatQueryModels.java` | 分层座位图、selectable 派生字段、查询时间与未锁定标记 |
| `src/main/java/com/ticketing/inventory/SeatSelectionRules.java` | 连续关系、整组校验及新孤座身份比较 |
| `src/main/java/com/ticketing/inventory/SeatRecommendationEngine.java` | 分段、窗口、孤座过滤、中心评分、稳定排序 |
| `src/main/java/com/ticketing/inventory/SeatRecommendationModels.java` | 候选与诊断的含义 |
| `src/test/java/com/ticketing/catalog/SeatQueryIT.java` | 真实 MySQL/HTTP、只读与快照隔离、开关、最新状态重读的证据 |
| `scripts/demo-seat-recommendations.ps1` | API 断言、独立命名空间、只读证据与 finally 恢复 |
| `scripts/SeatRecommendationBenchmark.java` | 固定布局、预热、计时范围、采样与指标定义 |
| `scripts/benchmark-seat-recommendations.ps1` | JDK、环境、参数与失败记录 |

可用两个业务例子检查理解：同段 1–4 可售，购买 2–4 为什么被拒绝；原有孤座被买走，同时留下另一新孤座，为什么总数不变仍被拒绝。再解释“重复 GET 不改库存”与“两个用户不能同时锁同座”分别需要什么证据。负责人学习验收与编码、自动化通过分开记录。
