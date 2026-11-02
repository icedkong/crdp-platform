-- E09-T07 / A-NTF-006、A-NTF-007、C-NTF-006、C-NTF-007：个人通知偏好与租户通知发送设置。
--   ntf_user_pref —— 逐用户一行：已关闭短信提醒的事件类型 CSV（tech-plan §9 表名同口径）。
--     只影响 SMS（A-NTF-007「用户可关闭非逾期类短信」），站内信不提供个人关闭；
--     逾期类（TASK_OVERDUE）能否出现在该清单由 ntf_setting.overdue_sms_off_allowed 决定，
--     规则引擎投递短信前检查（关闭 → 台账记 SKIPPED，不并入每日汇总）。
--   ntf_setting —— 租户级通知发送设置（单行表 id=1，种子即默认值）：
--     免打扰时段（默认 21:00–08:00，A-NTF-005 / C-NTF-005 医院已确认）与每人每日短信上限
--     （默认 3，建议值）由 E09-T05 汇总作业执行；overdue_sms_off_allowed 本任务即生效
--     （个人通知偏好校验与展示）。医院间差异走本院设置，不写租户分支（AGENTS §5.7）。

CREATE TABLE ntf_user_pref (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '用户（sys_user.id），本人维护本人偏好',
  sms_off VARCHAR(512) NOT NULL DEFAULT '' COMMENT '已关闭短信提醒的事件类型 CSV（NotifyEventType；不含患者信息）',
  updated_by BIGINT NULL COMMENT '最近修改人（sys_user.id，本人）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ntf_user_pref_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个人通知偏好（A-NTF-007 / C-NTF-007）';

CREATE TABLE ntf_setting (
  id BIGINT NOT NULL COMMENT '单行表，固定 1',
  dnd_start TIME NOT NULL DEFAULT '21:00:00' COMMENT '免打扰开始（HH:mm；时段内短信推迟到结束后，E09-T05 汇总作业执行）',
  dnd_end TIME NOT NULL DEFAULT '08:00:00' COMMENT '免打扰结束（HH:mm；可跨零点，如 21:00–08:00）',
  sms_daily_limit INT NOT NULL DEFAULT 3 COMMENT '每人每日短信上限（默认 3 为建议值，超出并入次日汇总，E09-T05 执行）',
  overdue_sms_off_allowed TINYINT NOT NULL DEFAULT 0 COMMENT '逾期类短信是否允许个人关闭（A-NTF-007；默认不允许，保证逾期提醒触达）',
  updated_by BIGINT NULL COMMENT '最近修改人（sys_user.id）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户通知发送设置（A-NTF-005 / A-NTF-007；单行 id=1）';

INSERT INTO ntf_setting (id) VALUES (1);
