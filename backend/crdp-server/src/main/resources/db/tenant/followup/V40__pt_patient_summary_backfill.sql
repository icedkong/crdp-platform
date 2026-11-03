-- E13-T04 / A-PAT-001 患者列表：pt_patient_summary 存量补齐。
-- 列表检索 LEFT JOIN pt_patient_summary（下次随访、完整度列）；该表在 ControlledTables 清单，
-- E07-T02 范围改写对 JOIN 引用追加 EXISTS（经 patient_id 关联患者），行缺失时 EXISTS 为假会把
-- 在册患者从列表里挤掉。E13-T03 起登记即建行（1:1），本脚本只为更早的存量行兜底补齐，
-- 保证「每名在册患者一行摘要」的不变量；INSERT 幂等（主键冲突跳过），不修改已有行。

INSERT IGNORE INTO pt_patient_summary (patient_id)
SELECT id FROM pt_patient WHERE deleted = 0;
