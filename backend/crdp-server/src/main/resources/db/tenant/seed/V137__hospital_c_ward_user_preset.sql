-- E23-T22（tasks.csv 验收：用户按科室病区分配）：医院 C 科室、病区、用户与角色预置。
-- 需求依据：docs/requirements/hospital-c-spec.md §7 科室病区隔离——C-WARD-001（科室沿用平台科室树
-- PLT-ORG-002；病区 / 中心沿用 A-WARD-001 独立字典，不挂在科室下）、C-WARD-002（患者归属一个科室和
-- 一个病区）、C-WARD-003（医生、随访员、护士可负责多个科室或病区，沿用 A-WARD-002）、C-WARD-004
-- （角色数据范围在 A-WARD-003 基础上增加「本人负责科室」）；§7 业务规则「负责某科室即可见归属该科室的
-- 全部患者，负责某病区即可见归属该病区的全部患者……负责多个科室或病区时取并集」；§10 建议角色模板
-- （医生 / 随访员 / 护士 / 科主任的数据范围与主要权限矩阵、敏感权限列）。
--
-- 与 V123（医院 A，E23-T04）/ V136（医院 B，E23-T18）同口径放种子目录（local / dev / IT；生产不执行）：
-- 用户、科室与病区取值是租户自有序数据（AGENTS §5.5 真实身份不入库），生产由 C 租户管理员按交接单在
-- 科室 / 病区 / 用户 / 角色页录入或走用户批量导入（E06-T07 / E06-T08），操作手册见
-- docs/ops/hospital-c-user-ward-role.md。种子对每个租户库执行同一脚本（V100 / V123 / V136 同口径，
-- OQ-93 ② / OQ-128 ③）：各演示租户库都带 A、B、C 三套预置，命名空间互不相交（a./b./c. 账号、
-- W-A-/W-B-/W-C- 病区码、DEPT-C- 科室码、A_/B_/C_ 角色码），互不串库。
-- 全部为虚构身份：姓名「X主任 / X医生 / X护士 / X随访员（C 示例）」、工号 DEMO-C-Uxx、
-- 科室「心内 / 心外科（C 示例）」、病区「心内 X 病区（C 示例）」，口令哈希 PENDING_DEMO_HASH 由
-- DemoPasswordInitializer 在 local / dev 启动时统一替换（与 V100 / V123 / V136 同口径，不记录明文）。
--
-- 与 A / B 预置的两处结构差异（C 规格 §7 / §10 的 C 自有口径）：
--   * 数据范围：医生 / 随访员 / 护士 = 本人负责病区（WARD）+ 本人负责科室（RESPONSIBLE_DEPT）——
--     C-WARD-003「可负责多个科室或病区」按 §7 规则两类分配都生效、取并集（未分配科室的用户科室分支
--     为空，行为与 §10「本人负责病区」一致）；科主任 = 本人负责科室（RESPONSIBLE_DEPT，C-WARD-004
--     新增类型），不再配全部病区（ALL）；
--   * 负责范围多一张科室分配表（sys_user_dept_scope，⑨）：科主任按科室落位、跨科室主任取并集、
--     c.mix.doc01 同时负责病区与科室（科室病区混合并集）。
--
-- 结构（对本脚本做逐项核对的是 CPresetWardUserSqlTest（单元、必跑）与 CPresetWardUserVerifyIT（真库，
-- 以演示租户库的 C 化身执行））：
--   ⓪ 科室（C-WARD-001：沿用平台科室树结构，取值为 C 自有虚构值；患者与用户的主科室指向它）；
--   ① 角色：C 规格 §10 建议角色模板复制为自定义角色（CUSTOM，PLT-ORG-005），租户管理员 TENANT_ADMIN
--      （全院，内置）随 V100，不再重复建；
--   ② 菜单授予：按 §10「主要权限」矩阵；敏感项 211 明文 / 212 导出 / 217 删除患者 / 229 修改已提交 /
--      230 删除记录 / 232 下载不预授（229 对医生的「默认分配，可调整」须平台先放开 PLT-SCOPE-004，
--      由租户管理员按 runbook 配置）；
--   ③ 数据范围（§10「数据范围」列 / C-WARD-003 / C-WARD-004）：见上；
--   ④ 病区字典（A-WARD-001 关键字段全集，取值为 C 自有虚构值）：3 个病区 + 2 个中心（起搏器程控随访
--      中心是 C 的随访载体）、别名（历史写法，值映射接口随 A-WARD-006）、排序、状态；
--   ⑤ 用户 27 名（14 医生 + 6 随访员 + 4 护士 + 3 科主任，§10 角色矩阵）；主科室 = C 心内科 / 心外科；
--   ⑥ 默认随访负责人（C-WARD-001 默认责任随访人 / A-FU-009 沿用：兜底与逾期抄送按此解析）；
--   ⑦ 用户 - 角色分配（每人一个业务角色，多角色合并取最宽由平台底座保证）；
--   ⑧ 负责病区分配（A-WARD-002：c.multi.doc01 负责心内一 + 心内二）；
--   ⑨ 负责科室分配（C-WARD-003：科主任逐科室落位、c.both.dir 负责心内 + 心外、
--      c.mix.doc01 病区 + 科室混合）。

-- ⓪ 科室（C-WARD-001：沿用平台科室树；患者登记 C-WARD-002 与科主任 RESPONSIBLE_DEPT 都指向它）
INSERT INTO sys_dept (org_id, parent_id, code, name, status)
SELECT o.id, 0, v.code, v.name, 'ENABLED'
FROM sys_org o
JOIN (
  SELECT 'DEPT-C-CARDIO' AS code, '心内科（C 示例）' AS name
  UNION ALL SELECT 'DEPT-C-CVS', '心外科（C 示例）'
) v
WHERE o.code = 'ORG-DEMO'
  AND NOT EXISTS (SELECT 1 FROM sys_dept d WHERE d.code = v.code);

-- ① 角色（C 规格 §10；复制为自定义角色后已按 C 科室病区范围配好）
INSERT INTO sys_role (code, name, built_in, status, role_type, module_code, description) VALUES
  ('C_DOCTOR', '医生', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区 / 科室：患者与植入记录、随访录入与补录、任务、纸质资料上传与预览、首页（E23-T22 预置，C 规格 §10）'),
  ('C_FOLLOWUP_STAFF', '随访员', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区 / 科室：任务、沟通记录、随访录入与补录、纸质资料上传与预览、首页（E23-T22 预置，C 规格 §10）'),
  ('C_NURSE', '护士', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责病区 / 科室：患者登记、随访录入与补录、纸质资料上传与预览、首页（E23-T22 预置，C 规格 §10）'),
  ('C_DIRECTOR', '科主任', 0, 'ENABLED', 'CUSTOM', 'followup', '本人负责科室：全部查看、首页、转科室病区、发布随访计划（E23-T22 预置，C 规格 §10）');

-- ② 菜单授予（§10 主要权限矩阵：10 工作台 / 20 随访 / 21 患者档案 / 213 新建 / 214 编辑 / 215 详情 /
--    228 编辑植入记录（患者与植入记录）/ 22 任务 / 224 认领与改派 / 222、223 随访录入与补录 /
--    23 报告单 / 231 上传（纸质资料上传与预览）/ 234 首页统计卡片 / 24、25 随访计划与详情 /
--    216 转科室病区 / 220 发布计划 / 221 计划入组；敏感项不预授）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'C_DOCTOR' AS code, 10 AS menu_id
  UNION ALL SELECT 'C_DOCTOR', 20
  UNION ALL SELECT 'C_DOCTOR', 21
  UNION ALL SELECT 'C_DOCTOR', 213
  UNION ALL SELECT 'C_DOCTOR', 214
  UNION ALL SELECT 'C_DOCTOR', 215
  UNION ALL SELECT 'C_DOCTOR', 228
  UNION ALL SELECT 'C_DOCTOR', 22
  UNION ALL SELECT 'C_DOCTOR', 224
  UNION ALL SELECT 'C_DOCTOR', 222
  UNION ALL SELECT 'C_DOCTOR', 223
  UNION ALL SELECT 'C_DOCTOR', 23
  UNION ALL SELECT 'C_DOCTOR', 231
  UNION ALL SELECT 'C_DOCTOR', 234
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 10
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 20
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 21
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 215
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 22
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 222
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 223
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 23
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 231
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 234
  UNION ALL SELECT 'C_NURSE', 10
  UNION ALL SELECT 'C_NURSE', 20
  UNION ALL SELECT 'C_NURSE', 21
  UNION ALL SELECT 'C_NURSE', 213
  UNION ALL SELECT 'C_NURSE', 215
  UNION ALL SELECT 'C_NURSE', 222
  UNION ALL SELECT 'C_NURSE', 223
  UNION ALL SELECT 'C_NURSE', 23
  UNION ALL SELECT 'C_NURSE', 231
  UNION ALL SELECT 'C_NURSE', 234
  UNION ALL SELECT 'C_DIRECTOR', 10
  UNION ALL SELECT 'C_DIRECTOR', 20
  UNION ALL SELECT 'C_DIRECTOR', 21
  UNION ALL SELECT 'C_DIRECTOR', 215
  UNION ALL SELECT 'C_DIRECTOR', 22
  UNION ALL SELECT 'C_DIRECTOR', 23
  UNION ALL SELECT 'C_DIRECTOR', 234
  UNION ALL SELECT 'C_DIRECTOR', 24
  UNION ALL SELECT 'C_DIRECTOR', 25
  UNION ALL SELECT 'C_DIRECTOR', 216
  UNION ALL SELECT 'C_DIRECTOR', 220
  UNION ALL SELECT 'C_DIRECTOR', 221
) m ON m.code = r.code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
);

-- ③ 数据范围（§10「数据范围」列 / C-WARD-003 / C-WARD-004）：临床三角色 = 本人负责病区 + 本人负责科室
--    （C-WARD-003「可负责多个科室或病区」两类分配都生效、取并集）；科主任 = 本人负责科室；
--    「全院」（ALL）留给内置租户管理员（C-WARD-007 沿用 A-WARD-007），「仅本人负责的患者」按需加选
INSERT INTO sys_role_scope (role_id, scope_type)
SELECT r.id, s.scope_type
FROM sys_role r
JOIN (
  SELECT 'C_DOCTOR' AS code, 'WARD' AS scope_type
  UNION ALL SELECT 'C_DOCTOR', 'RESPONSIBLE_DEPT'
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 'WARD'
  UNION ALL SELECT 'C_FOLLOWUP_STAFF', 'RESPONSIBLE_DEPT'
  UNION ALL SELECT 'C_NURSE', 'WARD'
  UNION ALL SELECT 'C_NURSE', 'RESPONSIBLE_DEPT'
  UNION ALL SELECT 'C_DIRECTOR', 'RESPONSIBLE_DEPT'
) s ON s.code = r.code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_scope rs WHERE rs.role_id = r.id AND rs.scope_type = s.scope_type
);

-- ④ 病区字典（A-WARD-001：编码 / 名称 / 类型 / 别名 / 排序 / 状态；默认随访负责人随 ⑥ 回填；取值为 C 自有虚构值）
INSERT INTO ward (code, name, status, ward_type, alias, sort_no) VALUES
  ('W-C-CARD1', '心内一病区（C 示例）', 'ENABLED', 'WARD', '心内1;内一病区;起搏器一区', 10),
  ('W-C-CARD2', '心内二病区（C 示例）', 'ENABLED', 'WARD', '心内2;内二病区;起搏器二区', 20),
  ('W-C-CARD3', '心内三病区（C 示例）', 'ENABLED', 'WARD', '心内3;内三病区', 30),
  ('W-C-CCU', '心脏重症监护中心（C 示例）', 'ENABLED', 'CENTER', 'CCU;心内监护;监护室', 40),
  ('W-C-PMFU', '起搏器程控随访中心（C 示例）', 'ENABLED', 'CENTER', '程控随访;起搏器随访;随访中心', 50);

-- ⑤ 用户 27 名（§10 角色矩阵：14 医生 + 6 随访员 + 4 护士 + 3 科主任；主科室 = C 心内科 / 心外科；
--    首登不强制改密，与 V100 演示账号同口径）
INSERT INTO sys_user (username, display_name, password_hash, status, must_change_password, staff_no, main_dept_id)
SELECT v.username, v.display_name, 'PENDING_DEMO_HASH', 'ENABLED', 0, v.staff_no,
       (SELECT d.id FROM sys_dept d WHERE d.code = v.main_dept)
FROM (
  SELECT 'c.card1.doc01' AS username, '贝医生（C 示例）' AS display_name, 'DEMO-C-U01' AS staff_no, 'DEPT-C-CARDIO' AS main_dept
  UNION ALL SELECT 'c.card1.doc02', '池医生（C 示例）', 'DEMO-C-U02', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card1.doc03', '明医生（C 示例）', 'DEMO-C-U03', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card1.doc04', '段医生（C 示例）', 'DEMO-C-U04', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card2.doc01', '费医生（C 示例）', 'DEMO-C-U05', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card2.doc02', '洪医生（C 示例）', 'DEMO-C-U06', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card2.doc03', '倪医生（C 示例）', 'DEMO-C-U07', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card2.doc04', '邵医生（C 示例）', 'DEMO-C-U08', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card3.doc01', '乐医生（C 示例）', 'DEMO-C-U09', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card3.doc02', '于医生（C 示例）', 'DEMO-C-U10', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.ccu.doc01', '常医生（C 示例）', 'DEMO-C-U11', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.ccu.doc02', '顾医生（C 示例）', 'DEMO-C-U12', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.multi.doc01', '盛医生（C 示例）', 'DEMO-C-U13', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.mix.doc01', '齐医生（C 示例）', 'DEMO-C-U14', 'DEPT-C-CVS'
  UNION ALL SELECT 'c.card1.fu01', '柯随访员（C 示例）', 'DEMO-C-U15', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card2.fu01', '管随访员（C 示例）', 'DEMO-C-U16', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card3.fu01', '蒲随访员（C 示例）', 'DEMO-C-U17', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.ccu.fu01', '桂随访员（C 示例）', 'DEMO-C-U18', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.pmfu.fu01', '骆随访员（C 示例）', 'DEMO-C-U19', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.pmfu.fu02', '樊随访员（C 示例）', 'DEMO-C-U20', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card1.nur01', '丁护士（C 示例）', 'DEMO-C-U21', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card2.nur01', '白护士（C 示例）', 'DEMO-C-U22', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.card3.nur01', '左护士（C 示例）', 'DEMO-C-U23', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.ccu.nur01', '石护士（C 示例）', 'DEMO-C-U24', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.cardio.dir', '崔主任（C 示例）', 'DEMO-C-U25', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.cvs.dir', '吉主任（C 示例）', 'DEMO-C-U26', 'DEPT-C-CVS'
  UNION ALL SELECT 'c.both.dir', '邱主任（C 示例）', 'DEMO-C-U27', 'DEPT-C-CARDIO'
) v;

-- ⑥ 默认随访负责人（C-WARD-001 默认责任随访人 / A-FU-009 沿用：兜底与逾期抄送按此解析）
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'c.card1.fu01') WHERE code = 'W-C-CARD1' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'c.card2.fu01') WHERE code = 'W-C-CARD2' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'c.card3.fu01') WHERE code = 'W-C-CARD3' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'c.ccu.fu01') WHERE code = 'W-C-CCU' AND default_owner_id IS NULL;
UPDATE ward SET default_owner_id = (SELECT u.id FROM sys_user u WHERE u.username = 'c.pmfu.fu01') WHERE code = 'W-C-PMFU' AND default_owner_id IS NULL;

-- ⑦ 用户 - 角色分配（每人一个业务角色；科主任走 C_DIRECTOR 本人负责科室，不配病区清单）
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM (
  SELECT 'c.card1.doc01' AS username, 'C_DOCTOR' AS role_code
  UNION ALL SELECT 'c.card1.doc02', 'C_DOCTOR'
  UNION ALL SELECT 'c.card1.doc03', 'C_DOCTOR'
  UNION ALL SELECT 'c.card1.doc04', 'C_DOCTOR'
  UNION ALL SELECT 'c.card2.doc01', 'C_DOCTOR'
  UNION ALL SELECT 'c.card2.doc02', 'C_DOCTOR'
  UNION ALL SELECT 'c.card2.doc03', 'C_DOCTOR'
  UNION ALL SELECT 'c.card2.doc04', 'C_DOCTOR'
  UNION ALL SELECT 'c.card3.doc01', 'C_DOCTOR'
  UNION ALL SELECT 'c.card3.doc02', 'C_DOCTOR'
  UNION ALL SELECT 'c.ccu.doc01', 'C_DOCTOR'
  UNION ALL SELECT 'c.ccu.doc02', 'C_DOCTOR'
  UNION ALL SELECT 'c.multi.doc01', 'C_DOCTOR'
  UNION ALL SELECT 'c.mix.doc01', 'C_DOCTOR'
  UNION ALL SELECT 'c.card1.fu01', 'C_FOLLOWUP_STAFF'
  UNION ALL SELECT 'c.card2.fu01', 'C_FOLLOWUP_STAFF'
  UNION ALL SELECT 'c.card3.fu01', 'C_FOLLOWUP_STAFF'
  UNION ALL SELECT 'c.ccu.fu01', 'C_FOLLOWUP_STAFF'
  UNION ALL SELECT 'c.pmfu.fu01', 'C_FOLLOWUP_STAFF'
  UNION ALL SELECT 'c.pmfu.fu02', 'C_FOLLOWUP_STAFF'
  UNION ALL SELECT 'c.card1.nur01', 'C_NURSE'
  UNION ALL SELECT 'c.card2.nur01', 'C_NURSE'
  UNION ALL SELECT 'c.card3.nur01', 'C_NURSE'
  UNION ALL SELECT 'c.ccu.nur01', 'C_NURSE'
  UNION ALL SELECT 'c.cardio.dir', 'C_DIRECTOR'
  UNION ALL SELECT 'c.cvs.dir', 'C_DIRECTOR'
  UNION ALL SELECT 'c.both.dir', 'C_DIRECTOR'
) t
JOIN sys_user u ON u.username = t.username
JOIN sys_role r ON r.code = t.role_code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
);

-- ⑧ 负责病区分配（A-WARD-002 经 C-WARD-003 沿用；c.multi.doc01 负责心内一 + 心内二）
INSERT INTO sys_user_ward (user_id, ward_id)
SELECT u.id, w.id
FROM (
  SELECT 'c.card1.doc01' AS username, 'W-C-CARD1' AS ward_code
  UNION ALL SELECT 'c.card1.doc02', 'W-C-CARD1'
  UNION ALL SELECT 'c.card1.doc03', 'W-C-CARD1'
  UNION ALL SELECT 'c.card1.doc04', 'W-C-CARD1'
  UNION ALL SELECT 'c.card2.doc01', 'W-C-CARD2'
  UNION ALL SELECT 'c.card2.doc02', 'W-C-CARD2'
  UNION ALL SELECT 'c.card2.doc03', 'W-C-CARD2'
  UNION ALL SELECT 'c.card2.doc04', 'W-C-CARD2'
  UNION ALL SELECT 'c.card3.doc01', 'W-C-CARD3'
  UNION ALL SELECT 'c.card3.doc02', 'W-C-CARD3'
  UNION ALL SELECT 'c.ccu.doc01', 'W-C-CCU'
  UNION ALL SELECT 'c.ccu.doc02', 'W-C-CCU'
  UNION ALL SELECT 'c.multi.doc01', 'W-C-CARD1'
  UNION ALL SELECT 'c.multi.doc01', 'W-C-CARD2'
  UNION ALL SELECT 'c.mix.doc01', 'W-C-CARD3'
  UNION ALL SELECT 'c.card1.fu01', 'W-C-CARD1'
  UNION ALL SELECT 'c.card2.fu01', 'W-C-CARD2'
  UNION ALL SELECT 'c.card3.fu01', 'W-C-CARD3'
  UNION ALL SELECT 'c.ccu.fu01', 'W-C-CCU'
  UNION ALL SELECT 'c.pmfu.fu01', 'W-C-PMFU'
  UNION ALL SELECT 'c.pmfu.fu02', 'W-C-PMFU'
  UNION ALL SELECT 'c.card1.nur01', 'W-C-CARD1'
  UNION ALL SELECT 'c.card2.nur01', 'W-C-CARD2'
  UNION ALL SELECT 'c.card3.nur01', 'W-C-CARD3'
  UNION ALL SELECT 'c.ccu.nur01', 'W-C-CCU'
) t
JOIN sys_user u ON u.username = t.username
JOIN ward w ON w.code = t.ward_code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_ward uw WHERE uw.user_id = u.id AND uw.ward_id = w.id
);

-- ⑨ 负责科室分配（C-WARD-003「可负责多个科室或病区」的科室分支 / C-WARD-004 科主任本人负责科室；
--    c.both.dir 负责心内 + 心外取并集、c.mix.doc01 病区 + 科室混合并集）
INSERT INTO sys_user_dept_scope (user_id, dept_id)
SELECT u.id, d.id
FROM (
  SELECT 'c.cardio.dir' AS username, 'DEPT-C-CARDIO' AS dept_code
  UNION ALL SELECT 'c.cvs.dir', 'DEPT-C-CVS'
  UNION ALL SELECT 'c.both.dir', 'DEPT-C-CARDIO'
  UNION ALL SELECT 'c.both.dir', 'DEPT-C-CVS'
  UNION ALL SELECT 'c.mix.doc01', 'DEPT-C-CVS'
) t
JOIN sys_user u ON u.username = t.username
JOIN sys_dept d ON d.code = t.dept_code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_user_dept_scope ds WHERE ds.user_id = u.id AND ds.dept_id = d.id
);
