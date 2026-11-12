-- E23-T20 / C-FLD-001、C-FLD-002（OQ-69④、OQ-67①、OQ-129②）：fe_field 字典绑定与带入上次值两列载体。
--   dict_code    字段 → 业务字典绑定（cfg_dict 编码，如 pacemaker_type、pacing_mode）：绑定字段的选项
--                不再走 fe_option_set / fe_option，而在 FormSchema 下发时读本院生效字典项展开
--                （含停用项、带停用标记，历史值照常显示，C-FLD-009）；库列与 JSON 存稳定字符串编码
--                （与 pt_implant 库列、cfg_dict 字符串码同向）。一个字段绑一个字典；电极植入部位按腔室
--                三字典（lead_position_ra/rv/lv）不绑本列，选项经 GET /implants/options 按腔室过滤
--                （E21-T04 已落地），字典行 help_text 注明。
--   carry_over   带入上次值标记（A-FU-015 / C-FU-016「程控设置项可带入、测量值不带入」）：前端契约
--                FormSchemaField.carryOver 先行（components.md §10.1），本列落地后随 FormSchema 下发；
--                缺省 0 = 不可带入。C 的带入字段（程控起搏模式、上下限频率、AV 间期）随 V90 标记。
-- 快照列同步（fe_form_version_field 加同名列）：版本快照固化发布时的绑定与标记，旧版本记录按其保存时
--   版本渲染；字典项本身与 fe_option 同口径不进快照（编码不可改、停用不删除，读取时现取，OQ-63）。
-- 只新增列、不改旧脚本；列均带缺省（dict_code NULL / carry_over 0），存量 A / B 字典行为不变
--   （无绑定即走选项集、不可带入）。

ALTER TABLE fe_field
  ADD COLUMN dict_code VARCHAR(64) NULL
    COMMENT '业务字典编码（cfg_dict，OQ-69④）：绑定字段的选项经字典展开（字符串编码），不走 fe_option_set；电极部位按腔室三字典不绑本列'
    AFTER option_set_code,
  ADD COLUMN carry_over TINYINT NOT NULL DEFAULT 0
    COMMENT '带入上次值（C-FU-016 程控设置项）：1 = FormRenderer「带入上次值」可覆盖写入（OQ-67①）；缺省 0'
    AFTER dict_code;

ALTER TABLE fe_form_version_field
  ADD COLUMN dict_code VARCHAR(64) NULL
    COMMENT '业务字典编码快照（发布时的 fe_field.dict_code）'
    AFTER option_set_code,
  ADD COLUMN carry_over TINYINT NOT NULL DEFAULT 0
    COMMENT '带入上次值标记快照（发布时的 fe_field.carry_over）'
    AFTER dict_code;
