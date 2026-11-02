-- E10-T05 / tech-plan §10「下载」、A-ATT-006：签名下载按钮权限码 followup:scan:download。
--   权限本身已在 V18 登记为敏感权限（plt_sensitive_perm：原件与附件下载），本脚本只补报告单页签
--   （plt_menu 23）下的按钮项，供平台端把它按租户放开后、医院端角色管理勾选授权。
--   敏感权限不进授权范围模板（V18 口径：模板只收非敏感项，plt_sensitive_perm 中的码一律排除），
--   也不预授予任何内置角色——医院要用，先由平台管理员在「租户授权范围」放开（PLT-PERM 系列）。
--   接口侧由 ApiResourceScanner 启动扫描登记（POST /api/hospital/files/{fileId}/download-url
--   绑定本权限码），无需种子 plt_api_resource。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (232, 23, 'HOSPITAL', 'BUTTON', 'followup', '下载文件', NULL, NULL, NULL, 'followup:scan:download', NULL, 20, 1, 'ENABLED');
