-- E06-T05 / PLT-ROLE-004、005（platform-spec §6 关键字段·角色）：角色类型 内置 BUILT_IN / 模板 TEMPLATE / 自定义 CUSTOM，
-- 模板角色记录来源模块 module_code；说明 description 供复制时参考。存量数据回填：built_in=1 → BUILT_IN，其余 → CUSTOM
-- （种子 V100 的 FOLLOWUP_DOCTOR 是演示自定义角色，保持 CUSTOM；模块模板由开通流程 / 种子 V106 下发为 TEMPLATE）。
-- 自定义科室范围（CUSTOM_DEPT）需要角色级科室清单表 sys_role_scope_dept；七种范围类型的生效口径见 E07-T02。

ALTER TABLE sys_role
  ADD COLUMN role_type VARCHAR(16) NOT NULL DEFAULT 'CUSTOM',
  ADD COLUMN module_code VARCHAR(32) NULL,
  ADD COLUMN description VARCHAR(200) NULL;

UPDATE sys_role SET role_type = 'BUILT_IN' WHERE built_in = 1;

CREATE TABLE sys_role_scope_dept (
  role_id BIGINT NOT NULL,
  dept_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 「维护角色与授权」按钮（plt_menu 321，tenant:role:edit，平台库 V22）授予老租户库的 TENANT_ADMIN；
-- 全新 local/dev 库由种子 V106 补授（V100 建角色晚于 base V11，与 V102 / V104 同理），生产由开通编排 ensureAdmin 授予。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 321
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 321
  );
