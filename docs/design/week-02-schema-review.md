# 第二周七表与场次快照方案

- 日期：2026-10-03
- 状态：项目负责人于 2026-10-04 确认，决策见 [ADR-0005](../decisions/0005-session-seat-snapshots.md)；实际实现与验证以第二周进度为准
- 范围：场馆、区域、座位模板、演出、场次、票档、场次座位，以及首次生成快照

## 1. 业务目标与验收

这轮解决“把可修改的物理布局复制为独立的场次库存”，不实现锁座、订单、支付或核销。

沿用已确认的不变量：

1. 模板修改、停用或删除，不修改已有场次的布局和价格快照。
2. 创建场次、创建票档、生成全部座位快照整体成功或整体失败。
3. 同一场次不能生成两套座位。
4. 可售座位必须有本场次票档和价格；不可售座位仍保留在座位图中，可以没有票档。
5. 区域、排、座号、过道分段和票档边界必须足以支持第三周连座判断。

第一条可运行路径：维护模板 → 创建演出 → 提交场次及票档配置 → 查询独立座位快照 → 修改或删除模板 → 验证旧场次不变、新场次使用新的布局。

## 2. 备选方案与建议

以下方案已由项目负责人确认；保留备选与取舍，便于回顾。

| 决策 | 备选方案 | 建议与代价 |
| --- | --- | --- |
| 场次布局 | 查询时关联实时模板；复制独立快照 | 复制布局、票档名称和金额；多存一份数据，换取明确的历史隔离 |
| 来源模板关联 | 强外键限制删除；外键删除时置空；普通来源 ID | 保留普通来源 ID，不设来源外键；删除模板后仍保留原始来源号，但不能保证仍能查到来源记录 |
| 其他关联 | 全部由应用校验；使用数据库外键 | 稳定的归属关系使用外键，禁止级联删除；应用仍校验业务合法性 |
| 模板并发修改 | 一致性读；锁住场馆阻塞修改；增加模板版本模型 | 首次创建使用同一事务内的 REPEATABLE READ 普通查询，读取一个一致版本；允许并发修改继续进行，本次可能使用修改前的完整布局 |
| 重复创建 | 只在应用中检查；唯一键；完整请求幂等记录 | 唯一场次代码和唯一快照键兜底，重复代码返回 409；暂不引入请求记录表，也不承诺相同请求自动返回原响应 |
| 快照生成 | 创建后另外生成；异步任务生成；同步创建 | 同步创建、同一本地事务，没有单独的“再生成”接口；接受创建请求随座位数量增加而变慢，暂不宣称性能 |

## 3. 通用字段规则

- 七张表均使用 `id BIGINT` 自增主键，以及 `created_at`、`updated_at DATETIME(6)`；主键不修改、不主动重用。
- 场馆、演出、场次使用稳定的 `code VARCHAR(64)`；区域和票档的 code 在所属父对象内唯一。代码创建后不编辑，种子与演示按代码定位，内部关联按 ID。
- 名称使用 `VARCHAR(128)`，排标识 `VARCHAR(32)`，过道分段 `VARCHAR(32)`；必填字符串不得为空白。
- 金额使用 `DECIMAL(10,2)`，Java 使用 `BigDecimal`；建议首版票价必须大于零。
- 坐标使用 `DECIMAL(10,3)`，允许负坐标；座号使用正整数，例如存储 1，界面可显示 01。
- 时间存储为 UTC，API 接收带时区的时间并统一转换；不把机器所在时区隐含进业务判断。
- 表使用 InnoDB 和 utf8mb4。索引首先服务唯一性、外键和当前查询，后续按实际查询与实验补充。
- 布尔字段限制为 0/1，状态使用可读字符串及 CHECK；不提前增加锁座所有者、锁期限或交易字段。

## 4. 七张表的字段方案

本节列出通用字段之外的字段。除明确标注“可空”者外均不可空。

| 表 | 业务包 | 字段与用途 |
| --- | --- | --- |
| `venue` | venue | `code`、`name`、`address VARCHAR(255)`（可空）、`enabled BOOLEAN`：物理场馆，停用仅阻止新建场次 |
| `area` | venue | `venue_id`、`code`、`name`、`display_order INT`：场馆内分区和展示顺序 |
| `seat_template` | venue | `area_id`、`row_label`、`row_order INT`、`seat_number INT`、`coordinate_x/y`、`aisle_segment`、`enabled BOOLEAN`：物理布局和默认可售性 |
| `event` | catalog | `code`、`name`、`description VARCHAR(2000)`（可空）、`enabled BOOLEAN`：演出本身，停用仅阻止新建场次 |
| `session` | catalog | `code`、`event_id`、`venue_id`、`event_name_snapshot`、`venue_name_snapshot`、`starts_at`、`admission_open_at`、`admission_close_at`、`sales_status VARCHAR(16)`、`first_opened_at`（可空）、`forbid_new_orphan BOOLEAN`：一次演出及独立销售配置 |
| `price_tier` | catalog | `session_id`、`code`、`name`、`price`、`enabled BOOLEAN`：只属于一个场次的票档；停用阻止该票档的新购买，保留历史数据 |
| `session_seat` | inventory | `session_id`、`source_seat_template_id`、`source_area_id`；区域 code/name/order、排 label/order、座号、坐标、过道分段的 `*_snapshot` 字段；`price_tier_id`、`price_tier_name_snapshot`、`price_snapshot`（这三项可空）；`seat_status VARCHAR(16)`：真正参与未来售卖的座位 |

`session` 中三个业务时间及 `first_opened_at` 均为 `DATETIME(6)`。名称快照均为 `VARCHAR(128)`。

`session_seat` 的布局字段准确命名为：`area_code_snapshot`、`area_name_snapshot`、`area_display_order_snapshot`、`row_label_snapshot`、`row_order_snapshot`、`seat_number_snapshot`、`coordinate_x_snapshot`、`coordinate_y_snapshot`、`aisle_segment_snapshot`。类型与源字段一致。

为什么同时有来源 ID 和复制字段：前者只回答“从哪里生成”，后者回答“这场演出实际卖哪一个座位”。查询场次座位时不通过来源 ID 回查模板补展示字段。

同一区域内同一排的 `row_order` 必须一致，不同排的排序值不能冲突；此跨行规则在模板维护服务中校验。区域展示顺序、排排序均为非负整数；座号为正整数。`aisle_segment` 只在同一区域、同一排内比较，不把不同排的 L 段当成连座。

## 5. 唯一约束、外键和检查

### 唯一约束

| 表 | 唯一键 | 防止的问题 |
| --- | --- | --- |
| venue / event / session | 各自 `code` | 业务标识重复 |
| area | `(venue_id, code)` | 同一场馆区域代码重复 |
| seat_template | `(area_id, row_label, seat_number)` | 同一物理位置重复 |
| price_tier | `(session_id, code)` | 同一场次票档代码重复 |
| price_tier | `(session_id, id)` | 为下面的复合外键提供明确的唯一目标 |
| session_seat | `(session_id, source_seat_template_id)` | 同一模板在同一场次复制两次 |
| session_seat | `(session_id, area_code_snapshot, row_label_snapshot, seat_number_snapshot)` | 同一场次中重复的座位位置 |

场次代码是防重复创建的输入标识。仅凭“同一个演出、场馆和开始时间”不自动合并两次创建；本轮不额外引入排期冲突业务规则。

### 归属外键

- `area.venue_id → venue.id`
- `seat_template.area_id → area.id`
- `session.event_id → event.id`，`session.venue_id → venue.id`
- `price_tier.session_id → session.id`
- `session_seat.session_id → session.id`
- `session_seat(session_id, price_tier_id) → price_tier(session_id, id)`，防止误用其他场次的票档。

这些外键均采用 RESTRICT，不通过删除父对象级联删除业务历史。座位模板本身可删除；删除带模板的区域需先明确处理其模板。`source_seat_template_id` 和 `source_area_id` 不设外键，来源删除也不改写快照。保留的是来源标识和快照内容，不包含一套模板变更审计系统。

### 行内检查和应用校验

- `session` 的核销窗口要求 `admission_open_at < admission_close_at <= starts_at`；退票和核销的半开时间边界继续沿用已有规则。
- `price_tier.price > 0`，座号和排序遵守第 4 节规则，布尔及状态字段限制合法值。
- 未配置票档时，`price_tier_id`、名称和金额三项全部为 NULL，且 `seat_status` 必须为 UNAVAILABLE。
- 配置票档时，三项全部非 NULL，名称非空、金额大于零；UNAVAILABLE 也可以保留已有票档。
- CHECK 明确使用 IS NULL / IS NOT NULL 判断成组完整性，不能只靠 `price_snapshot > 0` 判断可空字段。
- 应用校验同场次票档归属、模板/区域归属、映射重复和遗漏；单行 CHECK 不负责跨表价格一致性，也不替代状态迁移规则。

初始查询索引：`session(event_id, starts_at)`、`session(venue_id, starts_at)`、`session_seat(session_id, area_display_order_snapshot, row_order_snapshot, seat_number_snapshot)`。外键所需索引明确命名；已有唯一键可覆盖的查询不重复建相同索引。

## 6. 场次配置的冻结边界

建议 `sales_status` 使用 DRAFT、ON_SALE、STOPPED：

```text
DRAFT → ON_SALE → STOPPED
           ↑        │
           └────────┘
```

创建完成后仍为 DRAFT，运营人员检查后再开放销售。首次开售时写入 `first_opened_at`，之后不清空。停止销售只阻止新购买，不改变已有订单或票。

- 从未开售（`first_opened_at IS NULL`）：允许通过明确的场次配置操作调整票档、价格和座位可售性；相关票档和座位快照同一事务更新，不从物理模板自动刷新。
- 曾经开售：布局、票档金额、场次时间和入场窗口冻结，不能因 STOPPED 而重新获得修改资格；场馆模板仍可维护，影响以后创建的场次。
- 票档启停仍是新购买的控制开关，不修改已经复制的名称和金额，也不取消已有订单。

首个实现小闭环只完成创建 DRAFT 和查询快照。配置修改、启停及每条销售状态迁移在后续第二周任务实现并测试，不能因本轮字段存在就报告这些功能已完成。

## 7. 创建场次主流程与事务

入口是 catalog 的场次创建服务。它在一个本地事务内完成：

1. 在 REPEATABLE READ 下以普通 SELECT 读取演出、场馆、区域和模板，不混用锁定读；拒绝不存在或已停用的演出/场馆，拒绝没有模板座位的场馆。
2. 校验时间、票档代码和正数金额。请求给出区域默认票档及可选的单座覆盖；单座覆盖优先。未知区域/座位/票档或重复映射拒绝；每个默认可售模板座位必须得到票档。
3. 插入唯一代码的 DRAFT 场次及其名称快照，创建本场次票档。
4. 调用 inventory 的公开服务，把刚读取的布局值和新票档数据写入场次座位。默认可售且已配置票档的座位为 AVAILABLE；禁用座位为 UNAVAILABLE，仍保留布局。
5. 校验生成数量等于读取的全部模板数量；提交后返回场次 ID、代码及座位数。

venue 提供只读模板数据接口，inventory 提供快照写入接口；catalog 不调用其他模块的 Mapper。跨模块仍使用同一数据源、同一线程事务，下层不得 REQUIRES_NEW。数据先读为内存值再 INSERT VALUES，不使用 INSERT ... SELECT 重新读取来源。

REPEATABLE READ 的一致性读使用本事务第一次一致性读建立的版本。并发修改若在该版本之后提交，本次允许生成修改前的完整布局；后续新事务读取新布局。一次需要整体生效的模板批量维护本身也必须在一个事务内提交。这里的源数据读取语义不是“提交创建时强制追上最新布局”。依据见 [MySQL 一致性读说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-consistent-read.html)。

参数错误或不存在返回明确 4xx；重复场次代码返回 409，并提示按代码查询现有场次。唯一键、外键或批量写入失败，以及中途注入异常，必须让整个事务回滚。正常失败允许使用原代码修正后重试；若提交成功但响应丢失，再次提交得到 409，按代码查询，不静默创建第二个场次。

## 8. 实现与验收场景

本轮已记录 ADR，执行 `V1__create_venue_catalog_inventory.sql`，完成第一条路径及相应接口。种子脚本经 API 调用同一套业务服务，不把虚拟数据塞进结构迁移；规则测试厅按已评审设计生成 56 个模板座位（54 个默认可售）。重跑按稳定代码核对和复用，发现差异保留数据并拒绝覆盖，详见[运行导读](../development/session-snapshots.md)。

以下是本方案要求的验收场景；实际测试类、数量及执行证据见[第二周进度](../progress/week-02.md)：

1. 正常创建：区域、座位、票档和金额完整；过道与两个不可售位都出现在快照中。
2. 修改区域名称、模板排号、坐标、分区或可用性后，旧场次全部快照值不变，新场次采用修改后值。
3. 删除一个模板后，旧场次仍能查询该座位；新场次不再包含它。
4. 缺少可售座位票档、未知映射或非法时间，创建失败，三个目标表均不留下本次数据。
5. 在已写入场次和票档后注入异常，验证场次、票档、全部座位整体回滚。
6. 相同场次代码顺序及并发提交，最多一个场次及一套座位，失败方为明确冲突。
7. 数据库拒绝重复物理座位、重复快照、跨场次票档和不完整价格配置。
8. 两个事务协调执行模板批量变更和场次创建，验证本次读取同一版本，下一次创建读到新版本；不用真实睡眠碰运气。

七表迁移、上述测试及真实 API 演示已执行；入口、SQL、异常、事务和测试依据整理在运行导读中，负责人代码理解验收仍待进行。

## 9. 技术核对来源

- [MySQL 8.4 一致性读](https://dev.mysql.com/doc/refman/8.4/en/innodb-consistent-read.html)：核对 REPEATABLE READ 的读版本，以及 INSERT ... SELECT 与普通查询的区别。
- [MySQL 8.4 外键](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)：核对 RESTRICT 和复合外键的索引要求。
- [MySQL 8.4 CHECK](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)：NULL 可能使检查结果为 UNKNOWN，成组字段必须显式校验完整性。
