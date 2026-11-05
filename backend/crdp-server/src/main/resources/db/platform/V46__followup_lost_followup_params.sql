-- E15-T05 / A-FU-012、A-FU-024：失访判定与未联系到自动跟进的租户参数（tech-plan §9「规则（租户参数）」）。
--   生效值＝本院 cfg_param 覆盖值，否则平台默认值，再回落代码内置缺省（FollowupPolicyService）。
--   followup.lost.candidate.attempts：失访判定的连续未联系到次数（A-FU-012「次数可配置」，默认 3）；
--   followup.unreached.followup.interval.days：跟进任务的间隔天数（A-FU-024「间隔天数可配置」，默认 1 =
--     次日，hospital-a-spec Q28）；「每个窗口最多 5 次」为医院确认的固定值，不开放配置（代码常量）。

INSERT INTO plt_param (param_key, name, description, value_type, platform_value, tenant_overridable, value_range) VALUES
  ('followup.lost.candidate.attempts', '失访判定连续未联系到次数',
   '窗口内不同日期连续 N 次「未联系到 / 号码错误」且窗口已结束 → 任务标失访候选（A-FU-012，「拒访」不计入）',
   'NUMBER', '3', 1, '{"min":2,"max":10}'),
  ('followup.unreached.followup.interval.days', '未联系到跟进间隔天数',
   '沟通结果为「未联系到 / 号码错误」后，隔 N 天生成跟进任务（A-FU-024；每窗口最多 5 次，窗口结束不再生成）',
   'NUMBER', '1', 1, '{"min":1,"max":30}');
