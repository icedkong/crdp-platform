-- E10-T01 / PLT-CFG-010：文件上传配置（文件大小、类型白名单与数量上限）。
--   平台在 plt_param 定义默认值，租户在允许范围内经租户库 cfg_param 覆盖（PLT-CFG-004，
--   E06-T10 已有覆盖机制），生效值＝本院覆盖值，否则平台默认值。
--   file.upload.allowed.types 白名单是「支持的格式目录」（9 种，代码 FileFormat）中的扩展名 CSV，
--   覆盖值只能从目录里选（未知扩展名在读取时忽略并回落，见 FilePolicyService）；
--   数值类参数的取值范围同时是租户可覆盖的上下限。
-- 默认值口径：A-ATT-004（PDF/JPG/PNG/DOCX/XLSX 单个 20 MB，音频 MP3/M4A/WAV/AMR 50 MB，
--   每份报告单最多 10 个）；医院 C（C-SCAN-006：每份最多 50 个）与各院差异经租户覆盖实现，
--   不写租户分支（AGENTS §5.7）。

INSERT INTO plt_param (param_key, name, description, value_type, platform_value, tenant_overridable, value_range) VALUES
  ('file.upload.allowed.types', '上传文件类型白名单',
   '允许上传的文件扩展名（不区分大小写，逗号分隔），只能从支持的格式目录中选择：pdf,jpg,png,docx,xlsx,mp3,m4a,wav,amr',
   'TEXT', 'pdf,jpg,png,docx,xlsx,mp3,m4a,wav,amr', 1, '{"maxLength":200}'),
  ('file.upload.max.size.mb', '上传文件大小上限（MB）',
   '文档与图片类文件（PDF、JPG、PNG、DOCX、XLSX）单个不超过该值',
   'NUMBER', '20', 1, '{"min":1,"max":200}'),
  ('file.upload.audio.max.size.mb', '上传录音大小上限（MB）',
   '音频文件（MP3、M4A、WAV、AMR）单个不超过该值（A-ATT-004：通话记录以 MP3 为主）',
   'NUMBER', '50', 1, '{"min":1,"max":200}'),
  ('file.upload.max.files.per.report', '每份报告单文件数上限',
   '一份报告单可上传的文件个数上限（A-ATT-004 为 10、C-SCAN-006 为 50，各院经覆盖配置）',
   'NUMBER', '10', 1, '{"min":1,"max":200}');

-- 上传权限码 followup:scan:upload（OQ-12 定论：上传 / 下载 / 删除用 followup:scan:*）。
-- 报告单页签（plt_menu 23）下的按钮项；followup 模块医院端菜单须进授权范围模板
-- （V102 同口径，非敏感项默认可分配；followup:scan:download 在 V18 单独登记为敏感权限）。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (231, 23, 'HOSPITAL', 'BUTTON', 'followup', '上传文件', NULL, NULL, NULL, 'followup:scan:upload', NULL, 10, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 231 AND m.status = 'ENABLED';
