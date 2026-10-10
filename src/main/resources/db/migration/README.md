# 业务迁移目录

V1__create_venue_catalog_inventory.sql 创建已确认的七张业务表。

V2__create_identity_orders_and_holds.sql 新增账号、令牌摘要、订单、订单项、座位锁及锁明细六表，并为场次座位增加当前锁持有者约束。结构与事务见 ADR-0012；登录见 ADR-0013。演示账号密码由启动种子配置提供，不写进迁移。

V3__create_payment_simulator.sql 新增 simulated_payment 与 simulated_notification。模拟器渠道事实与通知快照同事务保存，但与本地订单结果独立提交；无到订单或本地支付表的外键。RESERVED 只用于同一未提交事务内占号，不能独立提交或发送通知。两表结构与独立提交见 ADR-0014；后续本地支付及票表使用新的迁移版本。

后续结构调整新增 V<版本>__<名称>.sql，不修改已经执行的迁移文件。
开发与测试应用启动时由 Flyway 统一执行迁移；演示数据通过业务接口生成，不放在结构迁移中。
