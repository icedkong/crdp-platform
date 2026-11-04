-- E14-T08 / A-FU-021：计划变更记录（租户库 followup）。
-- fu_plan_log 每次计划改动（新建 PLAN_CREATE / 编辑 PLAN_UPDATE / 保存节点 PLAN_NODE_SAVE /
-- 发布 PLAN_PUBLISH / 复制出新版本 PLAN_COPY / 暂停 PLAN_PAUSE / 恢复 PLAN_RESUME /
-- 停用 PLAN_DISABLE，含发布新版本时同族旧版本自动停用）写一条：
--   * before_json / after_json 为改动前后的内容快照（基本信息为摘要对象、节点保存为节点数组，
--     与 aud_log 审计同源双写——审计只追加且缺 (object_type, object_id) 索引，变更记录页
--     GET /api/hospital/followup/plans/{id}/logs 从本表出，口径同 fe_field_dict_log，OQ-69③）；
--   * description 与审计 detail 同文（页面直接可读的说明）；
--   * operator_id 操作人（operator_name 查询时联查 sys_user.display_name）。
-- 只追加，不提供修改与删除接口；不含患者数据。

CREATE TABLE fu_plan_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  plan_id BIGINT NOT NULL COMMENT '计划版本 fu_plan.id（改动对象；复制记录落在生成的新版本上）',
  plan_code VARCHAR(64) NOT NULL COMMENT '计划族编码快照（fu_plan.code）',
  action VARCHAR(32) NOT NULL COMMENT 'PLAN_CREATE/PLAN_UPDATE/PLAN_NODE_SAVE/PLAN_PUBLISH/PLAN_COPY/PLAN_PAUSE/PLAN_RESUME/PLAN_DISABLE',
  description VARCHAR(512) NULL COMMENT '说明（与审计 detail 同文）',
  before_json TEXT NULL COMMENT '改动前内容快照',
  after_json TEXT NULL COMMENT '改动后内容快照',
  operator_id BIGINT NULL COMMENT '操作人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_fu_plan_log_plan (plan_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划变更记录（A-FU-021；只追加，不含患者数据）';
