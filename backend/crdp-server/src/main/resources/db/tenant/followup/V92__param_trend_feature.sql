-- E21-T13 / C-FU-012：登记功能开关 param_trend（参数趋势页签，components.md §1.3
-- 「线框预览控制里有、仓库还没有的开关，新增时走 Flyway」；OQ-06 暂行做法）。
--   * 默认 0（关闭）：前端 useFeature('param_trend') 关闭时患者档案不出现「参数趋势」
--     页签（AGENTS §5.7 直接隐藏）；医院 A / B 不受影响。
--   * 医院 C 打开时机 = 后端趋势接口（GET /patients/{id}/param-trend，docs/api/patient.md
--     §7 前端契约已先行）落地后由配置打开，避免打开后接口 404。
--   * 全新库排序：followup V92 先于种子 V100 执行，二者插入的 feature_code 不重叠
--     （V100 不含 param_trend，脚本只新增不改旧），uk_cfg_feature 不冲突。
INSERT INTO cfg_feature (feature_code, enabled) VALUES ('param_trend', 0);
