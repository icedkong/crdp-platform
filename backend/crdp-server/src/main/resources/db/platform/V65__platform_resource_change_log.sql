-- 资源变更记录（E05-T10 / PLT-PERM-012，P2）：菜单、接口资源的变更历史与导出。
--   plt_resource_change_log 只追加（INSERT），与审计（plt_audit_log，安全追溯）互补：
--   审计面向「谁在什么时候做了什么」（哈希链防篡改），本表面向资源维度的可查变更流水，
--   记录资源类型 / 资源标识 / 动作 / 渠道 / 摘要 / 操作人，供平台端「资源变更记录」查询与导出。
--   写入点：菜单维护（新建 / 维护 / 删除）、菜单清单同步（新增 / 变更 / 下线）、
--   接口标记（平台端维护）、接口登记同步（首次登记 / 登记变化 / 消失清除）。
--   字段资源（FIELD）随 PLT-PERM-009 的平台端管理接口（E08+）落地时接入，类型值先行预留。

CREATE TABLE plt_resource_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  resource_type VARCHAR(16) NOT NULL,
  resource_id BIGINT NULL,
  resource_name VARCHAR(128) NOT NULL,
  resource_ident VARCHAR(128) NULL,
  action VARCHAR(16) NOT NULL,
  channel VARCHAR(8) NOT NULL,
  detail VARCHAR(512) NOT NULL,
  operator_id BIGINT NULL,
  operator_name VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_rcl_type (resource_type, id),
  KEY idx_plt_rcl_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
