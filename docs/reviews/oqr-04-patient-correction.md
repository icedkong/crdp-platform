# OQR-04 患者就地纠错与登记元数据实施记录

日期：2026-11-18。关联 A-PAT-003 / 005、A-FU-011、C-PAT-011、OQ-124 / 126。

状态：本工作区已实现并通过本地自动化验证，分支 `codex/oqr-04-patient-correction`；未提交 / 推送 CI，未执行生产迁移，尚待人工界面与部署验收。前面整改保留，本文只记录本项。

## 已实现行为

患者 ID、历史记录及关联不变。普通登记信息与敏感身份分开更正，均带 baseVersion 和必填原因；敏感更正还需独立权限和二次确认。患者行锁、基线锁行锁、版本 CAS 保护并发；基线锁定时拒绝。无变化不增加版本或字段历史。

| 范围 | 本次处理 |
|---|---|
| 普通实体字段 | 性别、出生 / 手术 / 入院 / 出院日期、患者类型；模板决定显示、必填及选项，A / B 与 C 最小日期必填差异由 implant 开关选择模板 |
| 敏感身份 | 姓名、病案号、证件号、住址；CryptoService 加密 / 盲索引、全院病案号唯一校验，响应始终脱敏，HIDDEN 字段不可更正 |
| 归属与状态 | 病区、责任医生、出院 / 随访状态只读，继续走专用接口 / 状态机 |
| 日期联动 | 手术 / 出院日期更正同事务调用既有 StartDateRecalculationService；重算失败整体回滚，仍被在组 / 暂停入组引用的基准日期不能清空 |
| 历史任务 | 已有联系记录、有效随访记录的任务不得重排 / 撤回；已完成任务保留，未联系的待办按原日期规则调整 |

四个入口：GET registration、PUT patients/{id}、PUT identity、GET registration/history，完整路径和契约见 [patient.md §2.1](../api/patient.md)。接口资源由启动扫描注册；没有修改安全边界或租户来源。所有患者读取通过 patient 数据范围，范围外返回 40400。日期引用计数是已通过患者范围校验后的内部 IgnoreScope，只返回该患者计数。

## 权限、迁移与隐私

- 平台 V113 新增 `followup:patient:correct`，登记为敏感权限；默认不进入可分配范围、不自动授权任何角色，也不包含明文查看能力。上线后平台需显式放开目标租户范围，再由授权管理员配置角色。
- 租户 followup V141 新增 PT_REG_A / PT_REG_C、PT_IDENTITY、PT_REG_STATUS 的字段字典、模板与版本快照。实体继续保存在 pt_patient 列，不向 fe_record JSON 写身份值。未修改旧迁移，没有新增租户 ID 列。
- 基线详情通过 RegistrationCard 与 FormRenderer 展示登记元数据，普通 / 敏感按钮按各自权限显隐。敏感输入不预填旧明文；空输入保持原值，可选字段明确勾选才能清空。新输入只留内存，60 秒、关闭、切换患者或卸载时清除，不写浏览器或服务器草稿。
- 版本冲突由用户选择加载服务器值或保留输入并重新确认，不能自动合并。锁定和病案号重复使用各自错误，不误进入版本覆盖流程。切换患者后丢弃旧读取 / 保存响应，保存成功刷新页头、基线与时间轴。
- 审计包含请求尝试与实际变更两个事件，原因必填且不得含敏感新旧值；身份字段历史只存旧 / 新密文摘要。专门的历史写入方法拒绝明文和非敏感字段摘要写入。历史界面显示「已记录密文摘要」。
- 租户视角不开放本次纠错入口；过滤器在代理捕获请求载荷前拒绝，服务层再次拒绝，避免新身份值进入平台代理 payload。原专用明文查看流程不变。

## 验证结果

- 定向 54 项全部通过：PatientCorrectionIT 8、StartDateRecalculationIT 4、TenantViewGuardFilterTest 23、FieldHistoryServiceTest 19。覆盖真实 HTTP / 双租户、普通与敏感权限分离、二次确认、脱敏与密文摘要、版本 / 唯一冲突、日期顺序、基线锁定、失败事务回滚、范围外 404、模块关闭、C 必填差异、任务及联系历史保留、代理载荷捕获前拒绝。
- 兼容性真实 MySQL 回归 17 项全部通过：PatientRegistrationIT 9、PlatformModuleRegistryMySqlIT 5、ConfigTemplateTenantInitMySqlIT 3；全新初始化包含新增迁移，模块版本断言同步至 V141。
- 前端新增 5 项测试全部通过，验证 60 秒清除、不写 Storage、确认 / 新值 / 清空语义、切换患者丢弃旧读取；包含于全量 233 文件、1878 项通过的测试结果。
- 后端全量 spotless:check / test 通过：3504 项，其中 2680 项运行通过、824 项按环境门控跳过；上述真实 MySQL 专项另行启用执行，不能把跳过项计为已验收。
- Flyway 版本检查、git diff --check、前端 format:check / lint、医院端 / 平台端 / H5 构建通过。构建仍有既有大包提示，测试仍有上游 mock hoist 提示，未阻断检查。

日志：`/tmp/crdp-oqr04-final-it.log`、`/tmp/crdp-oqr04-compat-it.log`、`/tmp/crdp-oqr04-final-backend.log`、`/tmp/crdp-oqr04-final-frontend-format.log`、`/tmp/crdp-oqr04-frontend-lint.log`、`/tmp/crdp-oqr04-frontend-full.log`、`/tmp/crdp-oqr04-frontend-build.log`。

本地旧测试库启动时仍提示既有审计哈希链异常。本项未修改历史审计，也不据此宣称审计链验收通过；生产审计与云资源证据仍须单独验收。接口 / 组件自动化通过不代替医院 UAT 或人工视觉验收。

下一项：OQR-05A 事实时间；医院 C 补录联动与统计钻取仍按整改清单依赖顺序实施。
