## 改动说明

<!-- 一两段说清改了什么、为什么；关联任务 / 需求编号（如 E16-T13 [A-ATT-002]）或审查条目（如 G7）写进标题或此处 -->

## 测试

<!-- 新增 / 修改的测试与本地验证（后端 mvn spotless:check test、前端 pnpm format:check && pnpm lint && pnpm test && pnpm build、迁移检查脚本）；标注覆盖的边界（无权限 40300 / 越范围 40400 / 开关关闭 40301 / 校验失败 / 双租户隔离等） -->

- [ ] 后端：`python3 scripts/check_flyway_versions.py`、`mvn -B -f backend/pom.xml spotless:check test`
- [ ] 前端：`pnpm format:check && pnpm lint && pnpm test && pnpm build`

## 是否触及

- [ ] **数据库迁移**（新增 Flyway 脚本；未修改任何已执行脚本）
- [ ] **权限码**（新码先登记 docs/api/README.md；创建后不改名）
- [ ] 新增公开接口（SecurityConfig 明确列出并限流）/ 新增审计点 / 脱敏字段 / 数据范围声明
- [ ] 功能开关或菜单（走迁移，不写死租户分支）
