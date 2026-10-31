-- 租户到期设置（E05-T02 / PLT-TEN-011）。到期类型与到期日 V7 已落列（当时只支撑列表展示与筛选），
-- 这里补齐按租户配置的其余三项（平台规格第 2 章「到期与停用补偿」，已确认）：
--   提醒节点（默认到期前 30、7、1 天，可按租户调整）、宽限期天数（默认 15）、宽限期是否只读（默认正常使用）。
-- 长期有效租户不提醒、不进入宽限期：remind_days / grace_days 存空，grace_read_only 无意义取 0。
-- 提醒发送与到期自动转宽限、宽限期满自动停用是 E05-T03（PLT-TEN-012）。

ALTER TABLE plt_tenant
  ADD COLUMN remind_days VARCHAR(64) NULL COMMENT '到期提醒节点，天数逗号分隔降序（如 30,7,1；长期有效为空）',
  ADD COLUMN grace_days INT NULL COMMENT '到期后宽限期天数（默认 15；长期有效为空）',
  ADD COLUMN grace_read_only TINYINT NOT NULL DEFAULT 0 COMMENT '宽限期是否只读（0 正常使用并提示续费，1 只读）';
