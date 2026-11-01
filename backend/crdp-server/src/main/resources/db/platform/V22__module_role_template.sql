-- E06-T05 / PLT-ROLE-005：业务角色模板（按模块登记，模块开通时下发到租户库，platform-spec §3 开通流程第 4 步）。
--   plt_role_template —— 模板定义（code 全局唯一：下发到租户库 sys_role 后也要满足租户内唯一）。
--   plt_role_template_menu —— 模板的默认菜单授权（菜单 id 引用 plt_menu；敏感按钮不放模板，与授权范围模板 V18 同口径，
--   会话层「角色并集 ∩ 已开通模块 ∩ 可分配范围」仍是最终闸门）。
--   种子 V105 登记随访模块五个模板（随访员、责任医生、研究者、CRC、资料录入员）；
--   生产由平台在模块目录维护（PLT-MOD-001 详情随后续任务展示，见 PR 说明）。
-- 另：医院端「维护角色与授权」按钮权限码 tenant:role:edit（platform-spec §6 权限点表；tenant:role:view 已在菜单 32 上）。
--   角色授予在租户库：老库由 base V11 增量授予、全新 local/dev 库由种子 V106 补授、生产由开通编排 ensureAdmin 授予。

CREATE TABLE plt_role_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  module_code VARCHAR(32) NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  description VARCHAR(200) NULL,
  sort_no INT NOT NULL DEFAULT 10,
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_role_template_code (code),
  KEY idx_plt_role_template_module (module_code, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_role_template_menu (
  id BIGINT NOT NULL AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_role_template_menu (template_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (321, 32, 'HOSPITAL', 'BUTTON', 'base', '维护角色与授权', NULL, NULL, NULL, 'tenant:role:edit', NULL, 10, 1, 'ENABLED');
