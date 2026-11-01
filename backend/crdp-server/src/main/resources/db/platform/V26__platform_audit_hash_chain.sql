-- E08-T02 / PLT-AUD-008、009、011：审计哈希链与日志只读（平台库）。
-- ① aud_chain_head：链头表（同租户库 V17，播种平台三张审计表）；
-- ② 触发器：plt_audit_log / aud_login_log / aud_platform_access_log 的 UPDATE / DELETE
--    数据库层报错（PLT-AUD-011 只追加）；逃生口 @crdp_audit_maintenance 同租户库口径；
-- ③ aud_chain_check：验链结果（append-only），按租户 + 表记每次校验的扫描量与断点，
--    「最近一次哈希链校验结果」查询（audit.md §3 /api/platform/audit/integrity）在 E08-T04 落地。

CREATE TABLE aud_chain_head (
  table_name VARCHAR(64) NOT NULL COMMENT '审计表名',
  last_id BIGINT NULL COMMENT '最后封存行主键',
  last_hash CHAR(64) NULL COMMENT '最后封存行哈希（NULL = 链未开始）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审计哈希链链头（AuditChainWriter 串行化锁与封存水位）';

INSERT INTO aud_chain_head (table_name)
  VALUES ('plt_audit_log'), ('aud_login_log'), ('aud_platform_access_log');

CREATE TABLE aud_chain_check (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NULL COMMENT '被检租户（NULL = 平台库自身）',
  table_name VARCHAR(64) NOT NULL COMMENT '审计表名',
  hashed_rows BIGINT NOT NULL COMMENT '本次扫描的已封存行数',
  break_count INT NOT NULL COMMENT '断链数',
  status VARCHAR(8) NOT NULL COMMENT 'PASS / BROKEN',
  first_break_id BIGINT NULL COMMENT '首个断点行主键',
  first_break_kind VARCHAR(32) NULL COMMENT 'BAD_HASH / BROKEN_LINK / MISSING_HASH',
  detail VARCHAR(512) NULL COMMENT '摘要（不含行内容）',
  checked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_acc_tenant_time (tenant_id, checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审计哈希链验链结果（PLT-AUD-009，验链作业追加写入）';

CREATE TRIGGER plt_audit_ro_upd BEFORE UPDATE ON plt_audit_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'plt_audit_log 审计日志只读，禁止修改（PLT-AUD-011）';
  END IF;
END;

CREATE TRIGGER plt_audit_ro_del BEFORE DELETE ON plt_audit_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'plt_audit_log 审计日志只读，禁止删除（PLT-AUD-011）';
  END IF;
END;

CREATE TRIGGER aud_login_ro_upd BEFORE UPDATE ON aud_login_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_login_log 审计日志只读，禁止修改（PLT-AUD-011）';
  END IF;
END;

CREATE TRIGGER aud_login_ro_del BEFORE DELETE ON aud_login_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_login_log 审计日志只读，禁止删除（PLT-AUD-011）';
  END IF;
END;

CREATE TRIGGER aud_apal_ro_upd BEFORE UPDATE ON aud_platform_access_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_platform_access_log 审计日志只读，禁止修改（PLT-AUD-011）';
  END IF;
END;

CREATE TRIGGER aud_apal_ro_del BEFORE DELETE ON aud_platform_access_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_platform_access_log 审计日志只读，禁止删除（PLT-AUD-011）';
  END IF;
END;
