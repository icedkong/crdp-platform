-- E21-T12 / C-FU-025（远程监测记录）：功能开关 remote_monitor_record。
-- 远程监测记录（fu_record.source = REMOTE，同一表单人工录入）是医院 C 起搏器随访的能力：
-- 开关关闭（无行 / 0 = A / B 默认）POST /patients/{id}/records（source=REMOTE）40301（AGENTS §5.7
-- 医院差异走功能开关，不写 if tenant；前端入口随 GET /auth/features 隐藏）；开启（医院 C 随租户
-- 配置写入，E23-T23）放行创建，时间轴 / 记录页按「远程监测」与门诊程控记录区分展示。
-- 与 V92 param_trend、V95 record_sequence_no 同口径：只落关闭行（默认 0），不改旧脚本；
-- 全新库 followup V99 先于种子 V100 执行，feature_code 不重叠，uk_cfg_feature 不冲突。

INSERT INTO cfg_feature (feature_code, enabled) VALUES ('remote_monitor_record', 0);
