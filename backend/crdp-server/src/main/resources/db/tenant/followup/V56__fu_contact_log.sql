-- E15-T04 / A-FU-006、A-FU-025、C-FU-019：沟通记录与录音附件。
--
-- fu_contact_log 一行 = 一次联系（A 规格「关键字段 · 沟通记录」；C-FU-019 沿用同一模型）：
--   * 联系结果六值（联系结果字典完整，tasks.csv E15-T04 验收）：REACHED 已联系 / NOT_REACHED 未联系到 /
--     WRONG_NUMBER 号码错误 / REFUSED 拒访 / FAMILY 家属代答 / DECEASED 告知已故——平台库 V45 预置
--     contact_result 字典；写入侧按后端枚举校验（结果驱动死亡终止与失访计数，租户新增项无业务语义，OQ-84）。
--   * 沟通方式：PHONE / WECHAT / FACE_TO_FACE / OTHER（A-FU-006 功能清单「电话 / 微信 / 面谈」+ 关键字段表「其他」）。
--   * 所用联系方式 = 引用 pt_contact 版本行（关联联系方式版本，如电话1），不落号码明文（敏感字段规则 AGENTS §5.3）。
--   * task_id 可空：随访工作台 / 任务链路带任务（第一次联系 → 任务进行中，followup.md §4），
--     患者页签直接记录的联系不挂任务。
--   * result = DECEASED 时同事务触发 FollowupTerminationService.terminateOnDeath（A-FU-007 / OQ-79②：
--     HTTP 触发随本任务接入，患者 → 已死亡 + 未完成任务全部终止，幂等）。
--   * 未联系到自动跟进（A-FU-024）与失访判定（A-FU-012）随 E15-T05 按本表计数。
--
-- fu_contact_audio：录音附件关联（A-FU-025，MP3 为主，另接受 M4A / WAV / AMR，单个 ≤ 50 MB 由
-- 文件服务按内容识别与白名单校验，E10-T01）。sys_file 一行只挂一条沟通记录（uk 兜底，防重复关联）；
-- E16-T08（A-ATT-017）把同一 sys_file 再显示为一份「通话记录」报告单——两处同一数据、不重复存储，
-- 删除任一处两处同时删除（本任务删除沟通记录即软删 sys_file）。
--
-- fu_contact_log 登记在 ControlledTables（E07-T01 名单已含）：查询按 @DataScope(resource = "patient")
-- 出行，越范围患者不可见（直接访问 40400，不暴露存在）。

CREATE TABLE fu_contact_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点）',
  task_id BIGINT NULL COMMENT '关联任务 fu_task.id（NULL = 不挂任务的联系，如患者页签直接记录）',
  method VARCHAR(16) NOT NULL COMMENT '沟通方式：PHONE 电话 / WECHAT 微信 / FACE_TO_FACE 面谈 / OTHER 其他（A-FU-006）',
  result VARCHAR(16) NOT NULL COMMENT '联系结果：REACHED / NOT_REACHED / WRONG_NUMBER / REFUSED / FAMILY / DECEASED（六值，A-FU-006；字典见平台库 V45）',
  contact_time DATETIME(3) NOT NULL COMMENT '沟通时间（默认当前时间，A 规格「默认当前时间」）',
  contact_version_id BIGINT NULL COMMENT '所用联系方式 pt_contact.id（引用版本行，不落号码明文）',
  next_contact_time DATETIME(3) NULL COMMENT '下次联系时间（约定回访到时提醒负责人，A 规格 §7）',
  note VARCHAR(2000) NULL COMMENT '情况说明（如「患者诉近期胸闷，建议门诊复查」）',
  lock_version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本（修改带 baseVersion，冲突 409，AGENTS §5.10）',
  created_by BIGINT NOT NULL COMMENT '记录人 sys_user.id（列表「操作人」列）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_fu_contact_log_patient (patient_id, id),
  KEY idx_fu_contact_log_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='沟通记录（A-FU-006 / C-FU-019；每次联系一条，E15-T04）';

CREATE TABLE fu_contact_audio (
  id BIGINT NOT NULL AUTO_INCREMENT,
  contact_log_id BIGINT NOT NULL COMMENT '沟通记录 fu_contact_log.id',
  file_id BIGINT NOT NULL COMMENT '录音 sys_file.id（MP3 / M4A / WAV / AMR，A-FU-025；同一文件只挂一条沟通记录）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '展示顺序（按上传先后）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fu_contact_audio_file (file_id),
  KEY idx_fu_contact_audio_log (contact_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='沟通记录录音附件（A-FU-025；与 E16-T08 通话记录报告单同一数据）';
