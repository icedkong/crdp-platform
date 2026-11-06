-- E20-T01 补充：视角会话台账增加「开始时间（epoch 秒）」列。
-- 动机（OQ-56 ⑥ 同源问题）：本地 / CI 的 MySQL 容器时钟是 UTC 而 JDBC connectionTimeZone=Asia/Shanghai，
-- DATETIME 的写读往返在 JVM 默认时区不同的环境（CI runner 为 UTC）会出现 ±8 小时钟面漂移，
-- 「到时 / 空闲超时」的判定若用应用钟面比对 DATETIME 会误判（CI 上刚进入的会话被当成已到时收尾）。
-- 到时判定改为纯 epoch 计算（started_at_epoch + 7200 与当前 epoch 比较），DATETIME 列只做展示回显；
-- 生产 RDS 时区为北京时间时 DATETIME 钟面天然一致。0 表示历史行（本迁移前无生产数据，按最老处理）。

ALTER TABLE plt_view_session
  ADD COLUMN started_at_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '开始时间（epoch 秒，跨时区到时判定用；0=历史行按最老处理）'
  AFTER note;
