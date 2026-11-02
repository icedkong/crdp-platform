-- E11-T08 / A-FLD-012、A-FLD-009、C-FLD-002、C-FLD-009：业务字典别名与变更记录（租户库）。
--   1) cfg_dict_item 加 aliases：租户管理员维护取值时登记别名（历史写法映射与导入匹配，
--      A-FLD-012「新增、停用、排序、别名」；A-WARD-006 同口径）；平台项停用标记行不使用该列。
--   2) cfg_dict_log 字典变更记录（A-FLD-009「记录每次字典调整的前后值、操作人、时间」，与
--      cfg_param_log 同款双写：既写变更记录表又写租户审计）：字典类型的创建 / 维护与字典项的
--      新增 / 维护 / 停用 / 恢复每次一条，before_json / after_json 存调整前后快照；停用不删除
--      （C-FLD-009：停用项不再出现在下拉，历史值保留并照常显示）。

ALTER TABLE cfg_dict_item
  ADD COLUMN aliases VARCHAR(255) NULL COMMENT '别名 CSV（历史写法映射与导入匹配，A-FLD-012；顿号或逗号分隔）' AFTER label;

CREATE TABLE cfg_dict_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dict_code VARCHAR(64) NOT NULL COMMENT '字典编码（平台或本院）',
  item_code VARCHAR(64) NULL COMMENT '字典项编码（字典类型级操作为 NULL）',
  action VARCHAR(32) NOT NULL COMMENT 'DICT_CREATE/DICT_UPDATE/ITEM_ADD/ITEM_UPDATE（停用与恢复为 ITEM_UPDATE，状态在快照内体现）',
  before_json TEXT NULL COMMENT '调整前快照（新增为 NULL）',
  after_json TEXT NULL COMMENT '调整后快照',
  operator_id BIGINT NULL COMMENT '操作人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_cfg_dict_log (dict_code, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典变更记录（A-FLD-009、C-FLD-009；只追加）';
