-- E11-T08 / A-FLD-009、C-FLD-009：字段字典变更记录（租户库）。
-- fe_field_dict_log 每次「维护字段」（PUT /api/hospital/form/fields/{key}，含字段启停与选项
-- 显示名 / 排序 / 停用 / 新增）写一条：before_json / after_json 为调整前后的字段视图快照，
-- operator 为操作人（form.md §3 GET /fields/{key}/logs 的数据来源，与 FIELD_DICT_UPDATE
-- 审计双写，口径同 cfg_param_log）。只追加，不提供修改与删除。

CREATE TABLE fe_field_dict_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_code VARCHAR(64) NOT NULL COMMENT '表单编码（fe_form.code）',
  field_key VARCHAR(32) NOT NULL COMMENT '字段稳定键（fe_field.field_key）',
  action VARCHAR(32) NOT NULL COMMENT 'FIELD_UPDATE=维护字段（含启停与选项调整，变更内容在快照内体现）',
  before_json TEXT NULL COMMENT '调整前字段视图快照',
  after_json TEXT NULL COMMENT '调整后字段视图快照',
  operator_id BIGINT NULL COMMENT '操作人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_fe_field_dict_log (form_code, field_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字段字典变更记录（A-FLD-009、C-FLD-009；只追加）';
