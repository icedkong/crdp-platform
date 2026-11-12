-- E21-T10 / C-PAT-003、Q10：随访编号前缀的租户参数（hospital-c-spec §4「随访编号自动生成」）。
--   规则＝「前缀-建档年份-年内 3 位流水号」（如 105-2026-001），前缀由租户配置（Q10）；出厂默认按规格示例
--   预置「105」（OQ-131① 暂行：医院未说明「105」含义，UAT 时请医院确认，租户参数可改）。
--   生效值＝本院 cfg_param 覆盖值，否则平台默认值，再回落代码内置缺省（VisitNoService，与 V46 失访参数同链路）；
--   取值范围 maxLength=16（前缀过长会把编号撑出 pt_patient.visit_no 列宽）；不含连字符等约束由服务端读取时
--   校验（连字符是规则分隔符），非法配置回落缺省不停摆登记。
--   租户经既有「系统参数」页覆盖（tenant:param:edit，cfg_param + cfg_param_log 留痕，PLT-CFG-004/011）。

INSERT INTO plt_param (param_key, name, description, value_type, platform_value, tenant_overridable, value_range) VALUES
  ('followup.visitno.prefix', '随访编号前缀',
   '医院 C 患者随访编号「前缀-建档年份-年内流水号」的前缀（C-PAT-003 / Q10；不含连字符与空白，历史患者手填纸质原编号不受此前缀约束）',
   'TEXT', '105', 1, '{"maxLength":16}');
