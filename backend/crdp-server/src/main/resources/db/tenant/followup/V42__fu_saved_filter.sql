-- E13-T06 / A-PAT-015（保存个人筛选）、A-PAT-014：个人常用筛选（fu_saved_filter，tech-plan §8/9 表名同口径）。
--   * 仅本人可见：查询 / 修改 / 删除都带 user_id 条件（SavedFilterMapper），不提供共享（A-PAT-015
--     「仅本人可见」）；个人配置不含患者数据，不受控表清单（ControlledTables）不加，无 @DataScope。
--   * payload = 检索条件 JSON：{ filters?, condition? }——简单筛选面 + 高级检索条件树
--     （components.md §11.2 ConditionGroup），**不含检索词 keyword**（检索词可能是患者姓名，
--     与「检索词不写 URL / 审计不存明文」同口径，不落库）。
--   * 保存时服务端校验条件树可编译（字段目录白名单 + 参数化，A-NFR-009）；应用保存的筛选仍走
--     /patients/search 正常校验与数据范围（病区条件由拦截器自动追加）。
--   * 每人上限 20 条（SavedFilterService 常量），防止无界增长；名字本院本人内 ≤32 字符。
-- 无种子行（个人配置由用户自建）。

CREATE TABLE fu_saved_filter (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '用户 sys_user.id（本人维护本人筛选，仅本人可见）',
  name VARCHAR(64) NOT NULL COMMENT '筛选名称（本人内唯一由前端引导，服务端 ≤32 字符）',
  payload JSON NOT NULL COMMENT '检索条件 {filters?, condition?}（不含检索词 keyword；condition 为高级检索条件树）',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id（本人）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL COMMENT '最近修改人（本人）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_fu_saved_filter_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个人保存的筛选（A-PAT-015；仅本人可见）';
