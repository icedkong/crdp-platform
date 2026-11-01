-- E08-T02 / PLT-AUD-008、009、011：审计哈希链与日志只读（租户库）。
-- ① aud_chain_head：链头表（写入串行化锚点 + 封存水位），迁移播种三行，行恒存在；
--    前序哈希以「已封存行按主键倒序第一条」为准，链头只做锁与水位展示。
-- ② 触发器：审计表 UPDATE / DELETE 一律数据库层报错（任何账号，PLT-AUD-011 只追加）。
--    唯一逃生口 @crdp_audit_maintenance：仅供测试自清理与运维清算（连接级会话变量，
--    需数据库凭据才能设置；数据库凭据被攻破的威胁由 E08-T03 的 OBS WORM 归档兜底）。
--    E08-T03 的在线保留清理走分区维护（ALTER / DROP PARTITION），不触发行级触发器。
-- 链定义：每表独立一条链，按主键升序、只计 hash 非空行；首条封存行 prev_hash = 64 个 0。
-- E08-T01 写入的未封存行（hash NULL）是链前缀，验链跳过（V15 注释「只建列不填值」在此收口）。

CREATE TABLE aud_chain_head (
  table_name VARCHAR(64) NOT NULL COMMENT '审计表名',
  last_id BIGINT NULL COMMENT '最后封存行主键',
  last_hash CHAR(64) NULL COMMENT '最后封存行哈希（NULL = 链未开始）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审计哈希链链头（AuditChainWriter 串行化锁与封存水位）';

INSERT INTO aud_chain_head (table_name) VALUES ('aud_log'), ('aud_login_log'), ('aud_data_access_log');

CREATE TRIGGER aud_log_ro_upd BEFORE UPDATE ON aud_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_log 审计日志只读，禁止修改（PLT-AUD-011）';
  END IF;
END;

CREATE TRIGGER aud_log_ro_del BEFORE DELETE ON aud_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_log 审计日志只读，禁止删除（PLT-AUD-011）';
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

CREATE TRIGGER aud_dal_ro_upd BEFORE UPDATE ON aud_data_access_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_data_access_log 审计日志只读，禁止修改（PLT-AUD-011）';
  END IF;
END;

CREATE TRIGGER aud_dal_ro_del BEFORE DELETE ON aud_data_access_log FOR EACH ROW
BEGIN
  IF @crdp_audit_maintenance IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'aud_data_access_log 审计日志只读，禁止删除（PLT-AUD-011）';
  END IF;
END;
