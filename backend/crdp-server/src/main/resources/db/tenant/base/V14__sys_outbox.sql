-- E06-T03 / PLT-ORG-013（platform-spec §6 业务规则「离职或停用用户立即下线；其名下待办由业务模块订阅事件后转交」）：
-- 租户库事务外发箱。组织与用户变更（离职、停用、调岗、角色变化、科室 / 院区停用）在同一事务内
-- 写入 sys_outbox（事件不丢：业务提交则事件提交，业务回滚则事件回滚）；分发（PENDING → SENT、
-- 投递给订阅方）属 E09 通知任务，本任务只落表不消费。租户库表不带 tenant_id（AGENTS 第 5 节第 1 条）。
-- 病区停用事件随病区管理任务（crdp-followup ward）落地时同表发布。

CREATE TABLE sys_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_type VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sys_outbox_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
