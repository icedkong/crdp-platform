-- E11-T06 / A-PAT-005、C-PAT-011：字段级变更历史 fe_field_history（租户库，tech-plan §6「元数据模型」、§7「关键规则」）。
--   「每个字段的修改都记录旧值、新值、修改人、时间、原因」：
--     record_type + record_id   记录类型与 ID（tech-plan §6 列定义；FE_RECORD = 引擎表单记录，
--                               PT_BASELINE_SECTION / FU_RECORD 等业务记录随 E13 / E15 接入同一表）；
--     old_value / new_value     值编码同 fe_record.data（键 = 稳定键；单选存编码整数、多选存编码数组、
--                               数值存数字、日期 YYYY-MM-DD、数值 × 2 存 {length,width}），新填旧值为
--                               NULL、清空新值为 NULL；敏感字段不经表单 JSON（storage=encrypted 走实体
--                               加密列，其历史值加密随 E13 业务侧处理），引擎侧防御性跳过敏感标记键；
--     source                    来源：USER 用户 / SYSTEM_RECALC 系统重算 / IMPORT 导入 / REPORT 报告单
--                               / PLATFORM_SUPPORT 平台支持（tech-plan §6；平台支持的操作人显示名追加
--                               「（平台支持）」，会话号关联随 E20 租户视角落地）；
--     trigger_fields            触发字段（逗号分隔稳定键）：系统重算 = 依赖来源字段（A 规格 §4），隐藏字段
--                               清空 = visibleWhen 引用的条件字段；
--     reason                    原因（锁定后修改必填 A-PAT-005，由业务锁定流程强制；平台支持、导入可带说明）；
--     operator_id/name          操作人 sys_user.id 与显示名快照（显示名快照防人员改名后历史不可读）。
-- 写入口只经 FieldHistoryService（提交 / 保存修改 E15 与 E25、导入更新 E18、报告单映射 E16-T07、派生重算
--   E11-T05 调用，同一事务逐行追加）；草稿不写（tech-plan §6「草稿语义」）。表只追加：没有 UPDATE / DELETE
--   接口（与审计同款不可变口径；归档策略随 E22 运维定）。

CREATE TABLE fe_field_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  record_type VARCHAR(32) NOT NULL COMMENT '记录类型：FE_RECORD 引擎表单记录；PT_BASELINE_SECTION / FU_RECORD 等业务记录随 E13 / E15 接入',
  record_id BIGINT NOT NULL COMMENT '记录 ID（record_type 对应表的主键；每院一库，天然隔离）',
  form_code VARCHAR(64) NULL COMMENT '表单编码 fe_form.code（记录所属表单；历史行不随模板改版失效）',
  template_version INT NULL COMMENT '记录保存时的模板版本（口径回放：旧值按该版本字段集解释）',
  field_key VARCHAR(32) NOT NULL COMMENT '字段稳定键（fe_field.field_key / data JSON 键）',
  old_value JSON NULL COMMENT '旧值（值编码同 fe_record.data；新填为 NULL）',
  new_value JSON NULL COMMENT '新值（值编码同 fe_record.data；清空为 NULL）',
  source VARCHAR(16) NOT NULL COMMENT '来源：USER / SYSTEM_RECALC / IMPORT / REPORT / PLATFORM_SUPPORT',
  trigger_fields VARCHAR(255) NULL COMMENT '触发字段（逗号分隔稳定键；重算 = 依赖来源字段，隐藏清空 = 条件字段）',
  reason VARCHAR(500) NULL COMMENT '修改原因（锁定后修改必填 A-PAT-005；平台支持 / 导入可带说明）',
  operator_id BIGINT NULL COMMENT '操作人 sys_user.id；系统重算无会话时为 NULL',
  operator_name VARCHAR(128) NULL COMMENT '操作人显示名快照（改名后历史仍可读；平台支持追加「（平台支持）」）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变更时间',
  PRIMARY KEY (id),
  KEY idx_fe_field_history_record (record_type, record_id, field_key, id),
  KEY idx_fe_field_history_field (form_code, field_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字段级变更历史（A-PAT-005 / C-PAT-011，每个字段改动可查旧值新值人时间）';
