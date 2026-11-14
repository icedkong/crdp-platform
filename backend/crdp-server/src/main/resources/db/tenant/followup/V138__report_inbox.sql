-- E21-T16 / C-SCAN-009（待归档区，P1）：先扫描后标注——未选患者的文件进入待归档区，
-- 录入人员逐个指定患者、类型与位置（至少一个）后归档；待归档区同样按科室病区隔离
-- （C-WARD-005；docs/api/report.md §4 暂行口径：按上传人所在科室 / 病区）。
--
-- 数据模型：rpt_inbox_file 一行 = 一个待分拣文件（sys_file 保持在 PENDING、未挂任何记录）；
-- 归档 = 用既有 POST /reports 的创建链路生成报告单（rpt_report / rpt_report_link / rpt_report_file
-- 全套校验与审计复用），本表行置 ARCHIVED 并记 report_id 作溯源（行保留、不删除；
-- file_id 唯一键防止同一文件重复进区）。
--
-- 隔离的实现位置：rpt_inbox_file 不进 ControlledTables——受控表改写按「子表.patient_id →
-- pt_patient」EXISTS 锚定，而待归档行没有患者（这正是待归档的定义）。科室病区隔离在
-- ReportInboxMapper 语句内按「上传人负责科室 / 病区（sys_user_dept_scope / sys_user_ward）
-- ∩ 操作者范围快照」实现（docs/api/report.md §4 推断口径，PR 注明）；ALL 范围全见，
-- 上传人本人恒可见自己的待分拣文件（避免「仅本人负责的患者」范围用户上传后无人能分拣）。
--
-- 功能开关：report_inbox 默认关闭（与 V92 param_trend、V99 remote_monitor_record 同口径：
-- 只落关闭行，A / B 不打开；医院 C 随租户配置开启）。关闭时三个接口 40301（AGENTS §5.7），
-- 前端按 GET /auth/features 隐藏入口。
--
-- 菜单授予：平台库 V74 新增「待归档区」菜单（plt_menu 354，权限码 followup:inbox:view）授予内置
-- TENANT_ADMIN（V65 的 233 同款幂等写法；V138 > 种子 V137，全新库也在 V100 建 TENANT_ADMIN 之后
-- 执行、命中非 0 行；生产无种子，由模块开通 ensureAdmin 补授全部模块菜单）。

INSERT INTO cfg_feature (feature_code, enabled) VALUES ('report_inbox', 0);

CREATE TABLE rpt_inbox_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_id BIGINT NOT NULL COMMENT '文件 sys_file.id（进区时须为 PENDING 未挂台账；归档后随报告单置 ATTACHED）',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待分拣 / ARCHIVED 已归档（行保留作溯源，archived_report_id 指向生成的报告单）',
  uploaded_by BIGINT NOT NULL COMMENT '放入待归档区的操作人 sys_user.id（隔离锚点：按其负责科室 / 病区，C-WARD-005）',
  archived_report_id BIGINT NULL COMMENT '归档生成的报告单 rpt_report.id（ARCHIVED 时非空）',
  archived_by BIGINT NULL COMMENT '归档操作人 sys_user.id',
  archived_at DATETIME(3) NULL COMMENT '归档时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_inbox_file (file_id),
  KEY idx_rpt_inbox_file_status (status, created_at),
  KEY idx_rpt_inbox_file_uploader (uploaded_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='待归档区（C-SCAN-009 先扫描后标注；归档生成 rpt_report，本表只作暂存与溯源）';

-- 「待归档区」菜单（plt_menu 354，followup:report:view）授予内置 TENANT_ADMIN。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 354
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 354
  );
