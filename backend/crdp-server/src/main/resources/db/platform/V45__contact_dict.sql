-- E15-T04 / A-FU-006：联系结果与沟通方式平台预置字典（业务字典机制见 V36 / E11-T08）。
--
-- 联系结果字典完整（tasks.csv E15-T04 验收）：A-FU-006 六值全量预置，components.md §2 contact 域
-- 同一枚举（REACHED / NOT_REACHED / WRONG_NUMBER / REFUSED / FAMILY / DECEASED）。租户可经机构设置
-- 「参数 / 字典」停用展示或新增本院项；写入侧按后端 ContactResult 枚举校验——新增结果没有失访计数 /
-- 死亡终止语义，需产品定归属后再放开（OQ-84）。
-- 沟通方式：A-FU-006 功能清单「电话 / 微信 / 面谈」+ 关键字段表「其他」；pages.md §4.8 的「门诊」
-- 属本院自有项，可自行新增（OQ-84）。幂等（WHERE NOT EXISTS）以便重放。

INSERT INTO plt_dict (code, name, description, status)
SELECT d.code, d.name, d.description, 'ENABLED'
FROM (
  SELECT 'contact_result' AS code, '联系结果' AS name,
         '联系结果字典（A-FU-006）：已联系 / 未联系到 / 号码错误 / 拒访 / 家属代答 / 告知已故；平台项可停用展示，写入按后端枚举校验' AS description
  UNION ALL
  SELECT 'contact_method', '沟通方式',
         '沟通方式字典（A-FU-006）：电话 / 微信 / 面谈 / 其他；本院可新增门诊等自有项或调整排序'
) d
WHERE NOT EXISTS (SELECT 1 FROM plt_dict x WHERE x.code = d.code);

INSERT INTO plt_dict_item (dict_id, item_code, label, sort_no, status)
SELECT d.id, i.item_code, i.label, i.sort_no, 'ENABLED'
FROM plt_dict d
JOIN (
  SELECT 'contact_result' AS dict_code, 'REACHED' AS item_code, '已联系' AS label, 10 AS sort_no
  UNION ALL SELECT 'contact_result', 'NOT_REACHED', '未联系到', 20
  UNION ALL SELECT 'contact_result', 'WRONG_NUMBER', '号码错误', 30
  UNION ALL SELECT 'contact_result', 'REFUSED', '拒访', 40
  UNION ALL SELECT 'contact_result', 'FAMILY', '家属代答', 50
  UNION ALL SELECT 'contact_result', 'DECEASED', '告知已故', 60
  UNION ALL SELECT 'contact_method', 'PHONE', '电话', 10
  UNION ALL SELECT 'contact_method', 'WECHAT', '微信', 20
  UNION ALL SELECT 'contact_method', 'FACE_TO_FACE', '面谈', 30
  UNION ALL SELECT 'contact_method', 'OTHER', '其他', 40
) i ON i.dict_code = d.code
WHERE NOT EXISTS (
  SELECT 1 FROM plt_dict_item x WHERE x.dict_id = d.id AND x.item_code = i.item_code
);
