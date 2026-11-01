-- E07-T06 / PLT-PERM-009：角色字段策略存储（租户库）。
-- 每个角色对敏感字段目录（crdp-framework SensitiveFieldCatalog，当前为患者身份五位字段）
-- 的显式覆盖：VISIBLE 可见 / MASKED 脱敏 / HIDDEN 隐藏；不落行 = 跟随平台默认（MASKED）。
-- 多角色合并（同字段取最宽松）与脱敏序列化由 E07-T04 / T05 在该存储之上实现；
-- 「不能宽于平台上限」（PLT-SCOPE-007）为 P2 后续规划，暂不设上限列。

CREATE TABLE sys_role_field_policy (
  id BIGINT NOT NULL AUTO_INCREMENT,
  role_id BIGINT NOT NULL COMMENT '角色 id（sys_role.id，仅自定义角色可配置）',
  resource VARCHAR(64) NOT NULL COMMENT '资源对象（如 patient）',
  field VARCHAR(64) NOT NULL COMMENT '字段编码（如 name / idCard / phone）',
  policy VARCHAR(16) NOT NULL COMMENT 'VISIBLE / MASKED / HIDDEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_role_field_policy (role_id, resource, field)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
