-- E20-T06 / PLT-VIEW-013：平台角色「代为配置资源范围」（platform-spec §8「关键字段 · 平台角色视角权限」）。
--   view_config_resources —— 可代为配置的配置类资源清单（权限码资源段，逗号分隔，如 user,role,dict）；
--   NULL / 空 = 全部配置类资源（已确认）。目录（代码级 CONFIG_RESOURCES，与前端 TENANT_VIEW_CONFIG_RESOURCES
--   同口径）：user 用户账号、role 角色与权限、org 组织架构、dict 数据字典、param 系统参数、brand 个性化、
--   webhook Webhook、session 在线会话、field 表单字段字典、notice 通知模板、abnormal 随访异常规则、
--   dashboard 工作台卡片、scan 报告单与电子化资料配置。
--   预置角色（V104）不设清单（NULL = 全部），与「平台管理员 / 实施人员可代为配置（全部资源）」的预置口径一致。
--   清单变化属视角授权变更：持有者进行中的视角会话按「权限变更」立即结束（PlatformRoleService 既有口径）。

ALTER TABLE plt_role
  ADD COLUMN view_config_resources VARCHAR(512) NULL
  COMMENT '代为配置资源范围（权限码资源段 CSV；NULL=全部配置类资源，PLT-VIEW-013）'
  AFTER view_can_edit_business;
