-- E23-T04 / A-WARD-001（A-WARD-002 / A-WARD-003、A-WARD-007、A-NFR-001、A 规格 §10 建议角色模板）：
-- 医院 A 病区、用户、角色与数据范围预置（tasks.csv 验收：约 30 名用户按病区分配）。
--
-- 与 V100 同口径放种子目录（local / dev / IT；生产不执行）：用户与病区取值是租户自有序数据，
-- A-FLD-014 配置模板「只复制配置，不复制用户账号」「病区 / 中心字典只复制结构、不复制取值」；
-- 生产由租户管理员在用户 / 病区 / 角色页录入或走用户批量导入（E06-T07 / E06-T08，真实数据不入库，
-- AGENTS §5.5）。A 的字段字典（V38）与预置计划（V61）是模块配置走 followup 目录，两者不冲突。
-- 全部为虚构身份：姓名「X医生 / X护士 / X主任 / X录入员（示例）」、工号 DEMO-A-Uxx、
-- 病区「心外 X 病区（示例）」（A 规格 §6 / §9 示意名），口令哈希 PENDING_DEMO_HASH 由
-- DemoPasswordInitializer 在 local / dev 启动时统一替换（与 V100 同口径，不记录明文）。
--
-- 结构（对本脚本做逐项核对的是 APresetWardUserSqlTest（单元、必跑）与 APresetWardUserVerifyIT（真库））：
--   ① 角色：A 规格 §10 建议角色模板复制为自定义角色（CUSTOM，PLT-ORG-005「从模板复制后调整」），
--      租户管理员 TENANT_ADMIN（全部病区，内置）随 V100，不再重复建；
--   ② 菜单授予：按 §10「主要权限」矩阵；敏感项 211 明文 / 212 导出 / 217 删除不预授，
--      由租户管理员按需分配（§10「敏感权限」列）；
--   ③ 数据范围（A-WARD-003）：病区医生 / 随访员 / 数据录入员 = 本人负责病区（WARD），
--      科主任 = 全部病区（ALL）；「仅本人负责的患者」（RESPONSIBLE_PATIENT）按需在角色页加选；
--   ④ 病区字典（A-WARD-001 关键字段全集）：3 个病区 + 2 个中心（类型 WARD / CENTER）、
--      别名（历史写法，值映射接口随 A-WARD-006）、排序、状态；
--   ⑤ 用户 30 名（A-NFR-001「全院约 30 名用户」医院已确认）：16 病区医生 + 8 随访员 + 4 科主任 /
--      项目负责人 + 2 数据录入员；主科室 = 演示心内科（DEPT-CARDIO）；
--   ⑥ 默认随访负责人（A-WARD-001 / A-FU-009：找不到责任医生时落到病区默认随访负责人）；
--   ⑦ 用户 - 角色分配（每人一个业务角色，多角色合并取最宽由平台底座保证）；
--   ⑧ 负责病区分配（A-WARD-002：一个医生可负责多个病区，a.multi.doc01 负责心外一 + 心外二）。

-- ① 角色（A 规格 §10；复制为自定义角色后已按 A 病区范围配好）
INSERT INTO sys_role (code, name, built_in, status, role_type, module_code, description) VALUES
  ('A_WARD_DOCTOR', '病区医生', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区：患者查看 / 新建 / 编辑、高级检索、批量入组、随访录入、任务认领、报告单（E23-T04 预置，A 规格 §10）'),
  ('A_FOLLOWUP_NURSE', '随访员', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区：随访任务、沟通记录、随访录入、报告单（E23-T04 预置，A 规格 §10）'),
  ('A_DIRECTOR', '科主任', 0, 'ENABLED', 'CUSTOM', 'followup', '全部病区：全部查看、转病区、发布随访计划、暂停 / 移出患者（E23-T04 预置，A 规格 §10）'),
  ('A_DATA_CLERK', '数据录入员', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区：患者新建、编辑、导入（E23-T04 预置，A 规格 §10；导入入口随 E18 落地后补授）');

-- ② 菜单授予（§10 主要权限矩阵：10 工作台 / 20 随访 / 21 患者档案 / 213 新建 / 214 编辑 / 215 详情 /
--    22 随访任务 / 222、223 随访录入 / 224 批量指定责任医生 / 23 报告单 / 24、25 随访计划与详情 /
--    216 转病区 / 220 发布计划 / 221 计划入组 / 225 批量入组 / 226、227 基线锁定与分节编辑）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'A_WARD_DOCTOR' AS code, 10 AS menu_id
  UNION ALL SELECT 'A_WARD_DOCTOR', 20
  UNION ALL SELECT 'A_WARD_DOCTOR', 21
  UNION ALL SELECT 'A_WARD_DOCTOR', 213
  UNION ALL SELECT 'A_WARD_DOCTOR', 214
  UNION ALL SELECT 'A_WARD_DOCTOR', 215
  UNION ALL SELECT 'A_WARD_DOCTOR', 22
  UNION ALL SELECT 'A_WARD_DOCTOR', 222
  UNION ALL SELECT 'A_WARD_DOCTOR', 223
  UNION ALL SELECT 'A_WARD_DOCTOR', 224
  UNION ALL SELECT 'A_WARD_DOCTOR', 23
  UNION ALL SELECT 'A_WARD_DOCTOR', 221
  UNION ALL SELECT 'A_WARD_DOCTOR', 225
  UNION ALL SELECT 'A_WARD_DOCTOR', 226
  UNION ALL SELECT 'A_WARD_DOCTOR', 227
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 10
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 20
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 21
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 215
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 22
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 222
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 223
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 23
  UNION ALL SELECT 'A_DIRECTOR', 10
  UNION ALL SELECT 'A_DIRECTOR', 20
  UNION ALL SELECT 'A_DIRECTOR', 21
  UNION ALL SELECT 'A_DIRECTOR', 215
  UNION ALL SELECT 'A_DIRECTOR', 22
  UNION ALL SELECT 'A_DIRECTOR', 23
  UNION ALL SELECT 'A_DIRECTOR', 24
  UNION ALL SELECT 'A_DIRECTOR', 25
  UNION ALL SELECT 'A_DIRECTOR', 216
  UNION ALL SELECT 'A_DIRECTOR', 220
  UNION ALL SELECT 'A_DIRECTOR', 221
  UNION ALL SELECT 'A_DIRECTOR', 225
  UNION ALL SELECT 'A_DATA_CLERK', 10
  UNION ALL SELECT 'A_DATA_CLERK', 20
  UNION ALL SELECT 'A_DATA_CLERK', 21
  UNION ALL SELECT 'A_DATA_CLERK', 213
  UNION ALL SELECT 'A_DATA_CLERK', 214
  UNION ALL SELECT 'A_DATA_CLERK', 215
) m ON m.code = r.code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
);

-- ③ 数据范围（A-WARD-003 病区范围 / §10「病区范围」列；WARD = 本人负责病区清单，ALL = 全院）
INSERT INTO sys_role_scope (role_id, scope_type)
SELECT r.id, s.scope_type
FROM sys_role r
JOIN (
  SELECT 'A_WARD_DOCTOR' AS code, 'WARD' AS scope_type
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 'WARD'
  UNION ALL SELECT 'A_DIRECTOR', 'ALL'
  UNION ALL SELECT 'A_DATA_CLERK', 'WARD'
) s ON s.code = r.code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_scope rs WHERE rs.role_id = r.id AND rs.scope_type = s.scope_type
);

-- ④ 病区字典（A-WARD-001：编码 / 名称 / 类型 / 别名 / 排序 / 状态；默认随访负责人随 ⑥ 回填）
INSERT INTO ward (code, name, status, ward_type, alias, sort_no) VALUES
  ('W-A-CVS1', '心外一病区（示例）', 'ENABLED', 'WARD', '外一病区;心外1', 10),
  ('W-A-CVS2', '心外二病区（示例）', 'ENABLED', 'WARD', '外二病区;心外2', 20),
  ('W-A-CVS3', '心外三病区（示例）', 'ENABLED', 'WARD', '外三病区;心外3', 30),
  ('W-A-CCU', '心脏重症监护中心（示例）', 'ENABLED', 'CENTER', 'CCU;心脏监护室;监护中心', 40),
  ('W-A-FU', '冠心病随访中心（示例）', 'ENABLED', 'CENTER', '随访中心;冠随中心', 50);

-- ⑤ 用户 30 名（A-NFR-001；主科室 = 演示心内科；首登不强制改密，与 V100 演示账号同口径）
INSERT INTO sys_user (username, display_name, password_hash, status, must_change_password, staff_no, main_dept_id)
SELECT v.username, v.display_name, 'PENDING_DEMO_HASH', 'ENABLED', 0, v.staff_no,
       (SELECT d.id FROM sys_dept d WHERE d.code = 'DEPT-CARDIO')
FROM (
  SELECT 'a.cvs1.dir' AS username, '钱主任（示例）' AS display_name, 'DEMO-A-U01' AS staff_no
  UNION ALL SELECT 'a.cvs1.doc01', '王医生（示例）', 'DEMO-A-U02'
  UNION ALL SELECT 'a.cvs1.doc02', '李医生（示例）', 'DEMO-A-U03'
  UNION ALL SELECT 'a.cvs1.doc03', '张医生（示例）', 'DEMO-A-U04'
  UNION ALL SELECT 'a.cvs1.doc04', '刘医生（示例）', 'DEMO-A-U05'
  UNION ALL SELECT 'a.cvs1.nur01', '杨护士（示例）', 'DEMO-A-U06'
  UNION ALL SELECT 'a.cvs1.nur02', '赵护士（示例）', 'DEMO-A-U07'
  UNION ALL SELECT 'a.cvs2.dir', '孙主任（示例）', 'DEMO-A-U08'
  UNION ALL SELECT 'a.cvs2.doc01', '周医生（示例）', 'DEMO-A-U09'
  UNION ALL SELECT 'a.cvs2.doc02', '吴医生（示例）', 'DEMO-A-U10'
  UNION ALL SELECT 'a.cvs2.doc03', '郑医生（示例）', 'DEMO-A-U11'
  UNION ALL SELECT 'a.cvs2.doc04', '冯医生（示例）', 'DEMO-A-U12'
  UNION ALL SELECT 'a.cvs2.nur01', '陈护士（示例）', 'DEMO-A-U13'
  UNION ALL SELECT 'a.cvs2.nur02', '褚护士（示例）', 'DEMO-A-U14'
  UNION ALL SELECT 'a.cvs3.dir', '卫主任（示例）', 'DEMO-A-U15'
  UNION ALL SELECT 'a.cvs3.doc01', '蒋医生（示例）', 'DEMO-A-U16'
  UNION ALL SELECT 'a.cvs3.doc02', '沈医生（示例）', 'DEMO-A-U17'
  UNION ALL SELECT 'a.cvs3.doc03', '韩医生（示例）', 'DEMO-A-U18'
  UNION ALL SELECT 'a.cvs3.doc04', '杨医生（示例）', 'DEMO-A-U19'
  UNION ALL SELECT 'a.cvs3.nur01', '朱护士（示例）', 'DEMO-A-U20'
  UNION ALL SELECT 'a.ccu.doc01', '秦医生（示例）', 'DEMO-A-U21'
  UNION ALL SELECT 'a.ccu.doc02', '尤医生（示例）', 'DEMO-A-U22'
  UNION ALL SELECT 'a.ccu.doc03', '许医生（示例）', 'DEMO-A-U23'
  UNION ALL SELECT 'a.ccu.nur01', '何护士（示例）', 'DEMO-A-U24'
  UNION ALL SELECT 'a.fu.nur01', '吕护士（示例）', 'DEMO-A-U25'
  UNION ALL SELECT 'a.fu.nur02', '施护士（示例）', 'DEMO-A-U26'
  UNION ALL SELECT 'a.fu.clerk01', '张录入员（示例）', 'DEMO-A-U27'
  UNION ALL SELECT 'a.fu.clerk02', '孔录入员（示例）', 'DEMO-A-U28'
  UNION ALL SELECT 'a.proj.dir', '曹主任（示例）', 'DEMO-A-U29'
  UNION ALL SELECT 'a.multi.doc01', '严医生（示例）', 'DEMO-A-U30'
) v;

-- ⑥ 默认随访负责人（A-WARD-001；各病区 / 中心的随访员负责人，A-FU-009 兜底与逾期抄送按此解析）
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'a.cvs1.nur01') WHERE code = 'W-A-CVS1' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'a.cvs2.nur01') WHERE code = 'W-A-CVS2' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'a.cvs3.nur01') WHERE code = 'W-A-CVS3' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'a.ccu.nur01') WHERE code = 'W-A-CCU' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'a.fu.nur01') WHERE code = 'W-A-FU' AND default_owner_id IS NULL;

-- ⑦ 用户 - 角色分配（每人一个业务角色；科主任 / 项目负责人走 A_DIRECTOR 全部病区，不另配病区清单）
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM (
  SELECT 'a.cvs1.dir' AS username, 'A_DIRECTOR' AS role_code
  UNION ALL SELECT 'a.cvs1.doc01', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs1.doc02', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs1.doc03', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs1.doc04', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs1.nur01', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.cvs1.nur02', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.cvs2.dir', 'A_DIRECTOR'
  UNION ALL SELECT 'a.cvs2.doc01', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs2.doc02', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs2.doc03', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs2.doc04', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs2.nur01', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.cvs2.nur02', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.cvs3.dir', 'A_DIRECTOR'
  UNION ALL SELECT 'a.cvs3.doc01', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs3.doc02', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs3.doc03', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs3.doc04', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.cvs3.nur01', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.ccu.doc01', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.ccu.doc02', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.ccu.doc03', 'A_WARD_DOCTOR'
  UNION ALL SELECT 'a.ccu.nur01', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.fu.nur01', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.fu.nur02', 'A_FOLLOWUP_NURSE'
  UNION ALL SELECT 'a.fu.clerk01', 'A_DATA_CLERK'
  UNION ALL SELECT 'a.fu.clerk02', 'A_DATA_CLERK'
  UNION ALL SELECT 'a.proj.dir', 'A_DIRECTOR'
  UNION ALL SELECT 'a.multi.doc01', 'A_WARD_DOCTOR'
) t
JOIN sys_user u ON u.username = t.username
JOIN sys_role r ON r.code = t.role_code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
);

-- ⑧ 负责病区分配（A-WARD-002；A_DIRECTOR 全部病区不配清单——A-WARD-007 同理适用于全院范围角色）
INSERT INTO sys_user_ward (user_id, ward_id)
SELECT u.id, w.id
FROM (
  SELECT 'a.cvs1.doc01' AS username, 'W-A-CVS1' AS ward_code
  UNION ALL SELECT 'a.cvs1.doc02', 'W-A-CVS1'
  UNION ALL SELECT 'a.cvs1.doc03', 'W-A-CVS1'
  UNION ALL SELECT 'a.cvs1.doc04', 'W-A-CVS1'
  UNION ALL SELECT 'a.cvs1.nur01', 'W-A-CVS1'
  UNION ALL SELECT 'a.cvs1.nur02', 'W-A-CVS1'
  UNION ALL SELECT 'a.cvs2.doc01', 'W-A-CVS2'
  UNION ALL SELECT 'a.cvs2.doc02', 'W-A-CVS2'
  UNION ALL SELECT 'a.cvs2.doc03', 'W-A-CVS2'
  UNION ALL SELECT 'a.cvs2.doc04', 'W-A-CVS2'
  UNION ALL SELECT 'a.cvs2.nur01', 'W-A-CVS2'
  UNION ALL SELECT 'a.cvs2.nur02', 'W-A-CVS2'
  UNION ALL SELECT 'a.cvs3.doc01', 'W-A-CVS3'
  UNION ALL SELECT 'a.cvs3.doc02', 'W-A-CVS3'
  UNION ALL SELECT 'a.cvs3.doc03', 'W-A-CVS3'
  UNION ALL SELECT 'a.cvs3.doc04', 'W-A-CVS3'
  UNION ALL SELECT 'a.cvs3.nur01', 'W-A-CVS3'
  UNION ALL SELECT 'a.ccu.doc01', 'W-A-CCU'
  UNION ALL SELECT 'a.ccu.doc02', 'W-A-CCU'
  UNION ALL SELECT 'a.ccu.doc03', 'W-A-CCU'
  UNION ALL SELECT 'a.ccu.nur01', 'W-A-CCU'
  UNION ALL SELECT 'a.fu.nur01', 'W-A-FU'
  UNION ALL SELECT 'a.fu.nur02', 'W-A-FU'
  UNION ALL SELECT 'a.fu.clerk01', 'W-A-FU'
  UNION ALL SELECT 'a.fu.clerk02', 'W-A-FU'
  UNION ALL SELECT 'a.multi.doc01', 'W-A-CVS1'
  UNION ALL SELECT 'a.multi.doc01', 'W-A-CVS2'
) t
JOIN sys_user u ON u.username = t.username
JOIN ward w ON w.code = t.ward_code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_ward uw WHERE uw.user_id = u.id AND uw.ward_id = w.id
);
