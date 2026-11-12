-- E21-T10 / C-PAT-003：随访编号年度流水表 pt_visit_no_seq。
--   「前缀-建档年份-年内 3 位流水号」的计数行（prefix, seq_year 各一行），VisitNoService 用
--   UPDATE ... SET seq = LAST_INSERT_ID(seq + 1) 在行锁内原子自增（并发登记各得不同流水号，
--   不做「读最大值 + 1」的快照竞态）；年度首例 INSERT 建行（LAST_INSERT_ID(1)），并发首插由主键兜底。
--   取号在登记事务内：登记回滚即弃号（允许留空号，不回收，编号不保证连续）。
--   非（患者及其子表等）受控业务表（纯计数器），不登记 ControlledTables；A / B 租户 impl 开关关闭、
--   登记不生成随访编号，本表保持空。
--   软删除患者不占用随访编号（uk_pt_patient_visit_no 与 delete_token 组合，V37），本表计数不回退。

CREATE TABLE pt_visit_no_seq (
  prefix VARCHAR(16) NOT NULL COMMENT '编号前缀（生效配置值，对应租户参数 followup.visitno.prefix 的覆盖或平台默认）',
  seq_year SMALLINT NOT NULL COMMENT '建档年份（业务时区 Asia/Shanghai 的登记年）',
  seq BIGINT NOT NULL COMMENT '年内已发号数（LAST_INSERT_ID 原子自增；下一个编号 = seq 已含本次）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (prefix, seq_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='随访编号年度流水（C-PAT-003，E21-T10）';
