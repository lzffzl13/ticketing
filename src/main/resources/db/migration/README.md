# 业务迁移目录

V1__create_venue_catalog_inventory.sql 创建已确认的七张业务表。

V2__create_identity_orders_and_holds.sql 新增账号、令牌摘要、订单、订单项、座位锁及锁明细六表，并为场次座位增加当前锁持有者约束。结构与事务见 ADR-0012；登录见 ADR-0013。演示账号密码由启动种子配置提供，不写进迁移。

V3__create_payment_simulator.sql 新增 simulated_payment 与 simulated_notification。模拟器渠道事实与通知快照同事务保存，但与本地订单结果独立提交；无到订单或本地支付表的外键。RESERVED 只用于同一未提交事务内占号，不能独立提交或发送通知。两表结构与独立提交见 ADR-0014。

V4__create_payments_and_tickets.sql 新增 payment_record 与 ticket，并扩展 customer_order 的取消意图、异常恢复信息、60 秒领取 token/截止字段。payment_record 通过 (session_id,order_id) 关联订单，(order_id,request_key) 幂等；occupied_order_id 生成列在 INITIATED/PROCESSING/SUCCEEDED 时唯一占用订单，FAILED/CLOSED 保留历史。ticket 通过复合外键绑定订单项、订单、场次及座位；订单项永久唯一，VALID/REFUNDING/CHECKED_IN 唯一占座，REFUNDED 退出占用。结构与统一结果事务见 ADR-0015。时间字段统一 DATETIME(6)，支付受理须严格早于原截止。

V5__create_refunds.sql 新增 refund_record、simulator_refund、simulator_refund_notification 三表。退票单以消费者/请求键幂等，生成列保护同票至多一个进行中或成功退款；复合外键绑定原消费者订单、原票完整关联与原支付。渠道退款与通知独立持久化，通知保留当时完整快照；业务终态仍可保留通知确认恢复资格。新增必要 UNIQUE 支撑复合外键，不修改 V1–V4。结构、单票原子回流和按退款单恢复见 ADR-0018。

第四周实现 VALID 签发/查询、安全取消和运营主动恢复；第五周已接入支付自动扫描、本人票码、单票退款与退款恢复。CHECKED_IN 枚举及占座约束不表示正式核销和审计已经实现，后者仍在第六周。

后续结构调整新增 V<版本>__<名称>.sql，不修改已经执行的迁移文件。
开发与测试应用启动时由 Flyway 统一执行迁移；演示数据通过业务接口生成，不放在结构迁移中。
