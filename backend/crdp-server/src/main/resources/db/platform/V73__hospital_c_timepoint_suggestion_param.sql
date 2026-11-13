-- E21-T14 / C-FU-014：补录时建议随访时间点的天数区间参数（hospital-c-spec §5「业务规则与边界情况」）。
--   「补录时建议随访时间点：0–7 天"术后72小时/出院前"，25–45 天"术后1个月"，75–110 天"术后3个月"，
--   150–215 天"术后6个月"，330–400 天"术后1年"，其后"每6-12个月"……（建议值，可配置）」
--   ——区间与「其后」编码经本参数配置；生效值＝本院 cfg_param 覆盖 → 平台默认 → 代码内置缺省
--   （TimepointSuggestionService，与 V46 失访参数、V71 编号前缀同链路）；非法配置（JSON 解析失败 /
--   区间上下界颠倒）逐级回落缺省并记日志，绝不停摆补录。选项编码对应字典 c_visit_timepoint
--   （followup V90）：1 术后72小时/出院前、2 术后1个月、3 术后3个月、4 术后6个月、5 术后1年、
--   6 每6-12个月；「ERI期随访」（7）是状态口径（在组 mode=ERI 且随访日期 ≥ eri_since），不走区间，
--   不在本参数内。租户经既有「系统参数」页覆盖（tenant:param:edit，cfg_param + cfg_param_log 留痕）。

INSERT INTO plt_param (param_key, name, description, value_type, platform_value, tenant_overridable, value_range) VALUES
  ('followup.timepoint.ranges', '补录建议随访时间点天数区间',
   '医院 C 补录随访记录时按「随访日期距植入日期天数」建议随访时间点的区间表（C-FU-014；建议值可改选）：JSON {"ranges":[{"code":选项编码,"min":天数下界,"max":天数上界},…],"beyond":其后选项编码}，选项编码对应字典 c_visit_timepoint；空档区间不建议、由用户自选',
   'TEXT', '{"ranges":[{"code":1,"min":0,"max":7},{"code":2,"min":25,"max":45},{"code":3,"min":75,"max":110},{"code":4,"min":150,"max":215},{"code":5,"min":330,"max":400}],"beyond":6}', 1, '{"maxLength":1024}');
