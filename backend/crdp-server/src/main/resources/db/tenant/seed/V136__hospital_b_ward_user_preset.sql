-- E23-T18（tasks.csv 验收：B 用户按病区分配）：医院 B 病区、用户、角色与数据范围预置。
-- 需求依据：docs/requirements/hospital-a-spec.md 第 14 章「医院 B 配置差异」——用户与角色取值：用户账号不复制、
-- B 约 30 名用户按病区分配；术者、病区 / 中心字典只复制结构、不复制取值，由 B 租户管理员自行维护；
-- 未列出的内容沿用第 1–13 章：§6 病区与数据隔离（A-WARD-001/002/003、A-WARD-007）、
-- §10 建议角色模板（病区范围与主要权限矩阵）、A-FU-009（病区默认随访负责人兜底）。
--
-- 与 V123（医院 A 预置，E23-T04）同口径放种子目录（local / dev / IT；生产不执行）：用户与病区取值是租户自有序
-- 数据（A-FLD-014 配置模板「不复制用户账号」「病区 / 中心字典只复制结构、不复制取值」，OQ-93 ①）；生产由 B 租户
-- 管理员按交接单在病区 / 用户 / 角色页录入或走用户批量导入（E06-T07 / E06-T08，真实身份不入库，AGENTS §5.5），
-- 操作手册见 docs/ops/hospital-b-user-ward-role.md。种子对每个租户库执行同一脚本（V100 / V123 同口径，
-- OQ-93 ② / OQ-128 ③）：各演示租户库都带 A、B 两套预置，命名空间互不相交（a./b. 账号、W-A-/W-B- 病区码、
-- A_/B_ 角色码），互不串库。
-- 全部为虚构身份：姓名「X主任 / X医生 / X护士 / X录入员（B 示例）」、工号 DEMO-B-Uxx、
-- 病区「心外 X 病区（B 示例）」，口令哈希 PENDING_DEMO_HASH 由 DemoPasswordInitializer 在 local / dev 启动时
-- 统一替换（与 V100 / V123 同口径，不记录明文）。
--
-- 结构（对本脚本做逐项核对的是 BPresetWardUserSqlTest（单元、必跑）与 BPresetWardUserVerifyIT（真库，演示双租户中的 B 化身
-- B 化身））：
--   ① 角色：A 规格 §10 建议角色模板复制为自定义角色（CUSTOM，PLT-ORG-005；第 14 章角色模板与权限点随 A 复制，
--      B 自有角色的增删属本任务），租户管理员 TENANT_ADMIN（全部病区，内置）随 V100，不再重复建；
--   ② 菜单授予：按 §10「主要权限」矩阵（与 A 预置同构）；敏感项 211 明文 / 212 导出 / 217 删除不预授，
--      由租户管理员按需分配（§10「敏感权限」列）；
--   ③ 数据范围（A-WARD-003）：病区医生 / 随访员 / 数据录入员 = 本人负责病区（WARD），科主任 = 全部病区（ALL）；
--      「仅本人负责的患者」（RESPONSIBLE_PATIENT）按需在角色页加选；
--   ④ 病区字典（A-WARD-001 关键字段全集，取值为 B 自有虚构值）：3 个病区 + 2 个中心（类型 WARD / CENTER）、
--      别名（历史写法，值映射接口随 A-WARD-006）、排序、状态；
--   ⑤ 用户 30 名（第 14 章「约 30 名用户按病区分配」；矩阵与 A 同构）：16 病区医生 + 8 随访员 +
--      4 科主任 / 项目负责人 + 2 数据录入员；主科室 = 演示心内科（DEPT-CARDIO）；
--   ⑥ 默认随访负责人（A-WARD-001 / A-FU-009：找不到责任医生时落到病区默认随访负责人）；
--   ⑦ 用户 - 角色分配（每人一个业务角色，多角色合并取最宽由平台底座保证）；
--   ⑧ 负责病区分配（A-WARD-002：一个医生可负责多个病区，b.multi.doc01 负责心外一 + 心外二）。

-- ① 角色（A 规格 §10 经第 14 章沿用；复制为自定义角色后已按 B 病区范围配好）
INSERT INTO sys_role (code, name, built_in, status, role_type, module_code, description) VALUES
  ('B_WARD_DOCTOR', '病区医生', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区：患者查看 / 新建 / 编辑、高级检索、批量入组、随访录入、任务认领、报告单（E23-T18 预置，A 规格 §10 经第 14 章沿用）'),
  ('B_FOLLOWUP_NURSE', '随访员', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区：随访任务、沟通记录、随访录入、报告单（E23-T18 预置，A 规格 §10 经第 14 章沿用）'),
  ('B_DIRECTOR', '科主任', 0, 'ENABLED', 'CUSTOM', 'followup', '全部病区：全部查看、转病区、发布随访计划、暂停 / 移出患者（E23-T18 预置，A 规格 §10 经第 14 章沿用）'),
  ('B_DATA_CLERK', '数据录入员', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区：患者新建、编辑、导入（E23-T18 预置，A 规格 §10 经第 14 章沿用；导入入口随 E18 落地后补授）');

-- ② 菜单授予（§10 主要权限矩阵：10 工作台 / 20 随访 / 21 患者档案 / 213 新建 / 214 编辑 / 215 详情 /
--    22 随访任务 / 222、223 随访录入 / 224 批量指定责任医生 / 23 报告单 / 24、25 随访计划与详情 /
--    216 转病区 / 220 发布计划 / 221 计划入组 / 225 批量入组 / 226、227 基线锁定与分节编辑）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'B_WARD_DOCTOR' AS code, 10 AS menu_id
  UNION ALL SELECT 'B_WARD_DOCTOR', 20
  UNION ALL SELECT 'B_WARD_DOCTOR', 21
  UNION ALL SELECT 'B_WARD_DOCTOR', 213
  UNION ALL SELECT 'B_WARD_DOCTOR', 214
  UNION ALL SELECT 'B_WARD_DOCTOR', 215
  UNION ALL SELECT 'B_WARD_DOCTOR', 22
  UNION ALL SELECT 'B_WARD_DOCTOR', 222
  UNION ALL SELECT 'B_WARD_DOCTOR', 224
  UNION ALL SELECT 'B_WARD_DOCTOR', 223
  UNION ALL SELECT 'B_WARD_DOCTOR', 23
  UNION ALL SELECT 'B_WARD_DOCTOR', 221
  UNION ALL SELECT 'B_WARD_DOCTOR', 225
  UNION ALL SELECT 'B_WARD_DOCTOR', 226
  UNION ALL SELECT 'B_WARD_DOCTOR', 227
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 10
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 20
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 21
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 215
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 22
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 222
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 223
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 23
  UNION ALL SELECT 'B_DIRECTOR', 10
  UNION ALL SELECT 'B_DIRECTOR', 20
  UNION ALL SELECT 'B_DIRECTOR', 21
  UNION ALL SELECT 'B_DIRECTOR', 215
  UNION ALL SELECT 'B_DIRECTOR', 22
  UNION ALL SELECT 'B_DIRECTOR', 23
  UNION ALL SELECT 'B_DIRECTOR', 24
  UNION ALL SELECT 'B_DIRECTOR', 25
  UNION ALL SELECT 'B_DIRECTOR', 216
  UNION ALL SELECT 'B_DIRECTOR', 220
  UNION ALL SELECT 'B_DIRECTOR', 221
  UNION ALL SELECT 'B_DIRECTOR', 225
  UNION ALL SELECT 'B_DATA_CLERK', 10
  UNION ALL SELECT 'B_DATA_CLERK', 20
  UNION ALL SELECT 'B_DATA_CLERK', 21
  UNION ALL SELECT 'B_DATA_CLERK', 213
  UNION ALL SELECT 'B_DATA_CLERK', 214
  UNION ALL SELECT 'B_DATA_CLERK', 215
) m ON m.code = r.code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
);

-- ③ 数据范围（A-WARD-003 病区范围 / §10「病区范围」列；WARD = 本人负责病区清单，ALL = 全院）
INSERT INTO sys_role_scope (role_id, scope_type)
SELECT r.id, s.scope_type
FROM sys_role r
JOIN (
  SELECT 'B_WARD_DOCTOR' AS code, 'WARD' AS scope_type
  UNION ALL SELECT 'B_FOLLOWUP_NURSE', 'WARD'
  UNION ALL SELECT 'B_DIRECTOR', 'ALL'
  UNION ALL SELECT 'B_DATA_CLERK', 'WARD'
) s ON s.code = r.code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_scope rs WHERE rs.role_id = r.id AND rs.scope_type = s.scope_type
);

-- ④ 病区字典（A-WARD-001：编码 / 名称 / 类型 / 别名 / 排序 / 状态；默认随访负责人随 ⑥ 回填；取值为 B 自有虚构值）
INSERT INTO ward (code, name, status, ward_type, alias, sort_no) VALUES
  ('W-B-CVS1', '心外一病区（B 示例）', 'ENABLED', 'WARD', '外一病区;心外1', 10),
  ('W-B-CVS2', '心外二病区（B 示例）', 'ENABLED', 'WARD', '外二病区;心外2', 20),
  ('W-B-CVS3', '心外三病区（B 示例）', 'ENABLED', 'WARD', '外三病区;心外3', 30),
  ('W-B-ICU', '心脏重症监护中心（B 示例）', 'ENABLED', 'CENTER', 'ICU;心脏监护室;术后监护', 40),
  ('W-B-FU', '心脏术后随访中心（B 示例）', 'ENABLED', 'CENTER', '随访中心;术后随访', 50);

-- ⑤ 用户 30 名（第 14 章「约 30 名用户按病区分配」；主科室 = 演示心内科；首登不强制改密，与 V100 演示账号同口径）
INSERT INTO sys_user (username, display_name, password_hash, status, must_change_password, staff_no, main_dept_id)
SELECT v.username, v.display_name, 'PENDING_DEMO_HASH', 'ENABLED', 0, v.staff_no,
       (SELECT d.id FROM sys_dept d WHERE d.code = 'DEPT-CARDIO')
FROM (
  SELECT 'b.cvs1.dir' AS username, '华主任（B 示例）' AS display_name, 'DEMO-B-U01' AS staff_no
  UNION ALL SELECT 'b.cvs1.doc01', '金医生（B 示例）', 'DEMO-B-U02'
  UNION ALL SELECT 'b.cvs1.doc02', '魏医生（B 示例）', 'DEMO-B-U03'
  UNION ALL SELECT 'b.cvs1.doc03', '陶医生（B 示例）', 'DEMO-B-U04'
  UNION ALL SELECT 'b.cvs1.doc04', '姜医生（B 示例）', 'DEMO-B-U05'
  UNION ALL SELECT 'b.cvs1.nur01', '戚护士（B 示例）', 'DEMO-B-U06'
  UNION ALL SELECT 'b.cvs1.nur02', '谢护士（B 示例）', 'DEMO-B-U07'
  UNION ALL SELECT 'b.cvs2.dir', '邹主任（B 示例）', 'DEMO-B-U08'
  UNION ALL SELECT 'b.cvs2.doc01', '喻医生（B 示例）', 'DEMO-B-U09'
  UNION ALL SELECT 'b.cvs2.doc02', '柏医生（B 示例）', 'DEMO-B-U10'
  UNION ALL SELECT 'b.cvs2.doc03', '水医生（B 示例）', 'DEMO-B-U11'
  UNION ALL SELECT 'b.cvs2.doc04', '窦医生（B 示例）', 'DEMO-B-U12'
  UNION ALL SELECT 'b.cvs2.nur01', '章护士（B 示例）', 'DEMO-B-U13'
  UNION ALL SELECT 'b.cvs2.nur02', '云护士（B 示例）', 'DEMO-B-U14'
  UNION ALL SELECT 'b.cvs3.dir', '苏主任（B 示例）', 'DEMO-B-U15'
  UNION ALL SELECT 'b.cvs3.doc01', '潘医生（B 示例）', 'DEMO-B-U16'
  UNION ALL SELECT 'b.cvs3.doc02', '葛医生（B 示例）', 'DEMO-B-U17'
  UNION ALL SELECT 'b.cvs3.doc03', '奚医生（B 示例）', 'DEMO-B-U18'
  UNION ALL SELECT 'b.cvs3.doc04', '范医生（B 示例）', 'DEMO-B-U19'
  UNION ALL SELECT 'b.cvs3.nur01', '彭护士（B 示例）', 'DEMO-B-U20'
  UNION ALL SELECT 'b.icu.doc01', '郎医生（B 示例）', 'DEMO-B-U21'
  UNION ALL SELECT 'b.icu.doc02', '鲁医生（B 示例）', 'DEMO-B-U22'
  UNION ALL SELECT 'b.icu.doc03', '韦医生（B 示例）', 'DEMO-B-U23'
  UNION ALL SELECT 'b.icu.nur01', '昌护士（B 示例）', 'DEMO-B-U24'
  UNION ALL SELECT 'b.fu.nur01', '马护士（B 示例）', 'DEMO-B-U25'
  UNION ALL SELECT 'b.fu.nur02', '苗护士（B 示例）', 'DEMO-B-U26'
  UNION ALL SELECT 'b.fu.clerk01', '凤录入员（B 示例）', 'DEMO-B-U27'
  UNION ALL SELECT 'b.fu.clerk02', '花录入员（B 示例）', 'DEMO-B-U28'
  UNION ALL SELECT 'b.proj.dir', '方主任（B 示例）', 'DEMO-B-U29'
  UNION ALL SELECT 'b.multi.doc01', '俞医生（B 示例）', 'DEMO-B-U30'
) v;

-- ⑥ 默认随访负责人（A-WARD-001；各病区 / 中心的随访员负责人，A-FU-009 兜底与逾期抄送按此解析）
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'b.cvs1.nur01') WHERE code = 'W-B-CVS1' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'b.cvs2.nur01') WHERE code = 'W-B-CVS2' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'b.cvs3.nur01') WHERE code = 'W-B-CVS3' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'b.icu.nur01') WHERE code = 'W-B-ICU' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'b.fu.nur01') WHERE code = 'W-B-FU' AND default_owner_id IS NULL;

-- ⑦ 用户 - 角色分配（每人一个业务角色；科主任 / 项目负责人走 B_DIRECTOR 全部病区，不另配病区清单）
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM (
  SELECT 'b.cvs1.dir' AS username, 'B_DIRECTOR' AS role_code
  UNION ALL SELECT 'b.cvs1.doc01', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs1.doc02', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs1.doc03', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs1.doc04', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs1.nur01', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.cvs1.nur02', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.cvs2.dir', 'B_DIRECTOR'
  UNION ALL SELECT 'b.cvs2.doc01', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs2.doc02', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs2.doc03', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs2.doc04', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs2.nur01', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.cvs2.nur02', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.cvs3.dir', 'B_DIRECTOR'
  UNION ALL SELECT 'b.cvs3.doc01', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs3.doc02', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs3.doc03', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs3.doc04', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.cvs3.nur01', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.icu.doc01', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.icu.doc02', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.icu.doc03', 'B_WARD_DOCTOR'
  UNION ALL SELECT 'b.icu.nur01', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.fu.nur01', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.fu.nur02', 'B_FOLLOWUP_NURSE'
  UNION ALL SELECT 'b.fu.clerk01', 'B_DATA_CLERK'
  UNION ALL SELECT 'b.fu.clerk02', 'B_DATA_CLERK'
  UNION ALL SELECT 'b.proj.dir', 'B_DIRECTOR'
  UNION ALL SELECT 'b.multi.doc01', 'B_WARD_DOCTOR'
) t
JOIN sys_user u ON u.username = t.username
JOIN sys_role r ON r.code = t.role_code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
);

-- ⑧ 负责病区分配（A-WARD-002；B_DIRECTOR 全部病区不配清单——A-WARD-007 同理适用于全院范围角色）
INSERT INTO sys_user_ward (user_id, ward_id)
SELECT u.id, w.id
FROM (
  SELECT 'b.cvs1.doc01' AS username, 'W-B-CVS1' AS ward_code
  UNION ALL SELECT 'b.cvs1.doc02', 'W-B-CVS1'
  UNION ALL SELECT 'b.cvs1.doc03', 'W-B-CVS1'
  UNION ALL SELECT 'b.cvs1.doc04', 'W-B-CVS1'
  UNION ALL SELECT 'b.cvs1.nur01', 'W-B-CVS1'
  UNION ALL SELECT 'b.cvs1.nur02', 'W-B-CVS1'
  UNION ALL SELECT 'b.cvs2.doc01', 'W-B-CVS2'
  UNION ALL SELECT 'b.cvs2.doc02', 'W-B-CVS2'
  UNION ALL SELECT 'b.cvs2.doc03', 'W-B-CVS2'
  UNION ALL SELECT 'b.cvs2.doc04', 'W-B-CVS2'
  UNION ALL SELECT 'b.cvs2.nur01', 'W-B-CVS2'
  UNION ALL SELECT 'b.cvs2.nur02', 'W-B-CVS2'
  UNION ALL SELECT 'b.cvs3.doc01', 'W-B-CVS3'
  UNION ALL SELECT 'b.cvs3.doc02', 'W-B-CVS3'
  UNION ALL SELECT 'b.cvs3.doc03', 'W-B-CVS3'
  UNION ALL SELECT 'b.cvs3.doc04', 'W-B-CVS3'
  UNION ALL SELECT 'b.cvs3.nur01', 'W-B-CVS3'
  UNION ALL SELECT 'b.icu.doc01', 'W-B-ICU'
  UNION ALL SELECT 'b.icu.doc02', 'W-B-ICU'
  UNION ALL SELECT 'b.icu.doc03', 'W-B-ICU'
  UNION ALL SELECT 'b.icu.nur01', 'W-B-ICU'
  UNION ALL SELECT 'b.fu.nur01', 'W-B-FU'
  UNION ALL SELECT 'b.fu.nur02', 'W-B-FU'
  UNION ALL SELECT 'b.fu.clerk01', 'W-B-FU'
  UNION ALL SELECT 'b.fu.clerk02', 'W-B-FU'
  UNION ALL SELECT 'b.multi.doc01', 'W-B-CVS1'
  UNION ALL SELECT 'b.multi.doc01', 'W-B-CVS2'
) t
JOIN sys_user u ON u.username = t.username
JOIN ward w ON w.code = t.ward_code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_ward uw WHERE uw.user_id = u.id AND uw.ward_id = w.id
);
