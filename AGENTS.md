# AGENTS.md — 临床科研数据一体化平台（crdp）编码代理说明

> 读者：在本仓库里写代码的 AI 编码代理（以及新加入的开发者）。你**没有参与过**之前的需求讨论，本文件和 `docs/` 就是全部上下文。
> 语言：文档、注释、提交说明、界面文案用简体中文；代码标识符用英文。

## 1. 项目背景（一句话版本）

多租户医院随访 SaaS。平台方开通医院（租户），每家医院一个独立数据库；首批三家：医院 A（重症冠心病长期年度随访，医院 B 是 A 的配置差异版）、医院 C（心脏起搏器植入与程控随访，含大量纸质资料补录）。核心能力：患者档案、按表单模板驱动的基线 / 随访录入、随访计划与任务、报告单归档、通知、统计、审计，以及严格的数据范围与脱敏。

当前状态：M0 工程骨架已完成（能编译、启动、虚构账号登录、动态菜单），**没有随访业务代码**。你的工作是按 `docs/tasks/` 的顺序把 M1–M6 做出来。

## 2. 阅读顺序

开始任何任务前，按这个顺序读（不必一次读完，按任务需要深入）：

1. 本文件。
2. `docs/README.md` — 文档索引与编号规则。
3. `docs/tasks/tasks.md` — 里程碑、执行说明；`docs/tasks/tasks.csv` — 全部 277 个任务（编号、依赖、关联需求、验收）。
4. 当前任务“关联需求”在 `docs/requirements/` 中的条目：
   - `tech-plan.md` 整体技术方案 V0.2（架构、数据模型、非功能 NFR-*）
   - `platform-spec.md` 平台底座需求 V0.3（PLT-*）
   - `hospital-a-spec.md` 医院 A 需求 V0.3（A-*；第 14 章为医院 B 差异）
   - `hospital-c-spec.md` 医院 C 需求 V0.1（C-*）
5. 前端任务：`docs/design/design-system.md` → `components.md` → `pages.md` 对应小节；令牌在 `docs/design/tokens.ts`。**界面必须严格按设计文档实现**，不要自由发挥。
6. 接口：`docs/api/README.md`（通用约定、错误码）→ 对应模块文件。
7. `docs/open-questions.md` — 没定论的事项及暂行做法（OQ-xx）。
8. 仓库已有决定：`docs/adr/`（如 `0001-m0-engineering-skeleton.md`）。

文档冲突时的优先级：**`docs/open-questions.md` 的暂行做法 > `docs/design/`（UI 定稿）> 医院规格 > 平台规格 > 技术方案**；仓库已落地的代码与 ADR 优先于文档中的旧写法。外壳以 ADR 0002 为准：官方 Vben Admin 5（仅 Ant Design Vue），OQ-01 已关闭。

## 3. 仓库结构

```
backend/                     Maven 多模块，JDK 21，Spring Boot 3，MyBatis（infra/mapper）
  crdp-server/               唯一可部署包；Flyway 脚本在 src/main/resources/db/
    db/platform/             平台库 crdp_platform（seed/ 为种子）
    db/tenant/base|followup/ 租户库 t_{租户编码}（seed/ 为种子）
  crdp-framework/            租户路由、认证、权限切面、数据范围、字段策略、审计、加密、存储、通知、outbox、作业等接口
  crdp-platform/             平台库：租户、模块、功能开关、菜单、平台账号
  crdp-system/               租户库：用户、角色、组织、数据范围、字典、参数
  crdp-form/                 表单引擎（模板、校验、规则）
  crdp-followup/             随访业务：patient implant plan task compare contact report notice stats dashboard importer ward
                             每个子域分 api / app / domain / infra 四层
frontend/                    pnpm workspace。官方 Vben Admin 5.7.0，只保留 Ant Design Vue 路径（ADR 0002）
  apps/web-hospital/         医院端（端口 14173），基于 Vben web-antd
  apps/web-platform/         平台端（端口 14174），基于 Vben web-antd
  apps/mobile-upload/        手机拍照上传 H5 占位
  packages/@core、effects、…  Vben 上游包（不含 ele / naive / tdesign / antdv-next）
  internal/、scripts/        Vben 构建与 lint
  packages/api/              与后端约定的类型，以及菜单到 Vben 路由的转换
  packages/tokens/           重新导出 docs/design/tokens.ts
  packages/form-engine/      表单渲染器
  packages/rules/            权限码与前端规则
  packages/viewer/           报告单查看器（水印预览）
deploy/                      本地 MySQL 初始化（演示租户库）
iac/                         华为云资源 IaC 草稿（运维任务产出；真实云操作由持账号运维执行，rds-prod/ 见 E22-T04、cce-prod/ 见 E22-T05）
scripts/                     dev.sh、check_flyway_versions.py、check_rds_baseline.py、check_cce_baseline.py、restore_tenant.sh、check_restore_script.py、pentest_intake.py、check_pentest_docs.py
docs/                        需求、任务、设计、接口、ADR、待确认问题
.github/workflows/ci.yml     CI
```

依赖方向：`crdp-followup → crdp-form → crdp-framework`；`crdp-platform`、`crdp-system` 只依赖 `crdp-framework`。不要反向依赖。

## 4. 运行、构建、测试

需要 Docker、JDK 21、Maven、Node.js 20+（CI 用 22）、pnpm 10。

```bash
./scripts/dev.sh                     # 一键：MySQL + Redis、打包并启动后端、启动两个前端
docker compose up -d                 # 只起依赖
mvn -f backend/pom.xml -pl crdp-server -am -DskipTests package
java -jar backend/crdp-server/target/crdp-server.jar --spring.profiles.active=local
cd frontend && pnpm install && pnpm dev
```

地址：后端 http://127.0.0.1:18080，OpenAPI `/swagger-ui.html`，健康检查 `/actuator/health`；医院端 14173；平台端 14174。演示账号与本地口令见仓库根 `README.md`（只用于本机，**不要复制到文档、代码注释或日志**）。

提交前必须本地通过（与 CI 一致）：

```bash
python3 scripts/check_flyway_versions.py
mvn -B -f backend/pom.xml spotless:check test        # 格式不过就先 mvn -f backend/pom.xml spotless:apply
cd frontend && pnpm format:check && pnpm lint && pnpm test && pnpm build
```

前端格式和静态检查用 Vben 的工具链（oxfmt、oxlint、ESLint、Stylelint），`pnpm lint` 就是 `vsh lint`。测试是仓库根上的 `vitest run`，会收集 `frontend` 里的 `*.spec.ts` / `*.test.ts`。

## 5. 不可违反的规则

违反任何一条的代码都不算完成，评审直接退回。

1. **租户数据库隔离**。平台库 `crdp_platform` 与每院一个租户库 `t_{租户编码}`；租户库的表**不带 `tenant_id`**。租户只来自登录令牌（不信任请求参数或请求头里的租户），`TenantRoutingDataSource` 据此选连接池；没有租户上下文不允许访问数据库（`MissingTenantContextException`）。异步任务、定时作业必须显式设置并清理租户上下文（`TenantContextTaskDecorator`），按租户逐个执行。医院账号不能调平台接口，反之亦然。
2. **数据范围默认拒绝**。受控表（患者及其子表、任务、记录、报告单等，登记在 `ControlledTables`）的查询必须带 `@DataScope`，或在调度入口用 `@IgnoreScope` 并写明原因；没声明 → 40302（这是后端缺陷，不是用户错误）。范围为空 → 追加 `AND (1 = 0)`，返回空结果而不是报错。越范围访问单个对象返回 **40400**（不暴露对象存在）。统计、导出、通知跳转、重复提示全部按当前用户范围计算。
3. **脱敏与明文查看**。敏感字段（姓名、手机号、证件号、病案号 / 病历号、住址等）默认按字段策略返回脱敏值；列表、表格**不提供逐行明文**。明文只能通过专用接口逐字段获取：需权限 `followup:patient:plain` + 二次确认 + 写数据访问审计；前端只在内存中保存明文，**60 秒后自动恢复脱敏**，不写 localStorage / IndexedDB / 日志 / URL。加解密只走 `CryptoService`，业务代码不直接调用算法库。
4. **审计日志**。增删改、配置 / 权限 / 状态变更、登录事件、查看明文、导出、打印、下载、批量查询、平台租户视角访问、被拒绝的操作，全部写审计（`AuditLogger`）。审计只追加（INSERT），没有修改与删除接口；敏感值只存摘要。详见 `docs/api/audit.md`。
5. **种子与测试数据不得含真实数据**。种子、测试、示例、截图、文档中的姓名一律写成“张医生（示例）”这类带“示例”标记的虚构值；不得出现真实的患者姓名、手机号、身份证号、病案号、患者编号。医院提供的样例 Excel 和竞品截图**不得**进入仓库。日志和异常里不写口令、令牌、患者明文。
6. **所有数据库变更走 Flyway**。脚本只新增、不修改已有脚本；平台库与租户库各自版本号不重复（`check_flyway_versions.py`）；改菜单、权限码、开关、字典也通过新迁移完成。不要手工改库，不要用 JPA 自动建表。
7. **按租户的功能开关**。医院之间的差异用功能开关（`@RequireModule` / 功能码，如 `wide_import`、`implant`、`eri_cycle`、`compare_highlight`）和租户配置实现，**不要写 `if (tenant == "A")`**。开关关闭：后端返回 40301，前端隐藏入口。
8. **表单模板驱动字段**。基线、随访、植入等表单的字段、分组、校验、显示条件、默认值来自字段字典与表单模板（`crdp-form`、`packages/form-engine`）；不要在页面里硬编码业务字段。新增字段 = 改字典 / 模板配置（迁移），不是改 Vue 组件。
9. **权限码**格式 `{模块}:{资源}:{动作}`，创建后不改名（见 OQ-11）。后端用 `@RequiresPermission`（缺权限 40300），前端用 `packages/rules` 判断按钮显示——前端隐藏不能代替后端校验。
10. **乐观锁与自动保存**。可编辑对象带 `version`；保存带 `baseVersion`，冲突返回 409 并进入冲突流程（保留我的并覆盖 / 加载服务器版本，**不做字段级合并**）。前端每 30 秒及失焦写 IndexedDB 草稿；有改动时每 2 分钟保存服务器草稿。

## 6. 后端约定

- 统一响应 `{ code, message, data, traceId }`，成功码 0，响应头 `X-Trace-Id`；错误用 `ApiException` + 错误码（表见 `docs/api/README.md`，新码先登记再用）。
- 接口前缀：医院端 `/api/hospital/**`，平台端 `/api/platform/**`，手机 H5 `/api/h5/**`（只用一次性令牌）。公开接口只有两个登录接口、actuator health / info、swagger；新增公开接口必须在 SecurityConfig 明确列出并限流。
- 分页：`pageNo` 从 1 开始，`pageSize` 默认 20；复杂查询用 `POST .../search` + 条件树；响应 `{ items, total, pageNo, pageSize }`。
- 分层：`api`（Controller、DTO）→ `app`（用例、事务、权限、审计）→ `domain`（实体、规则）→ `infra`（Mapper、外部适配）。Controller 不写业务逻辑；跨子域调用走 `app` 接口或 outbox 事件。
- `crdp-framework` 里的 `Unsupported*` 是占位实现（存储、通知、作业、Excel、outbox），实现真实适配器时替换它们，接口签名需要改时先写 ADR。
- 时间：接口用 ISO-8601 带时区（如 `+08:00`）；业务日期（出院日期、随访日期、窗口）按北京时间（Asia/Shanghai）用 `LocalDate` 计算。超过 2^53 的 ID 序列化为字符串。
- 测试：每个接口至少覆盖 正常 / 无权限（40300）/ 越范围（40400）/ 开关关闭（40301）/ 校验失败；租户隔离相关改动必须有双租户用例（参考 `TenantRoutingDataSourceTest`）；数据范围改动参考 `DataScopeGuardTest`。集成测试用虚构数据。

## 7. 前端约定

- 视觉与交互以 `docs/design/` 为准：主色 #3A75FF、成功 #1DC29E、警告 #FA8C16、错误 #FF4D4F；圆角 8 / 6 / 4；栅格间距 16；控件高 32（手机 36、字号 16）；断点 768 / 1280。数值全部来自 `tokens.ts`，不要在组件里写魔法数。
- 外壳是官方 Vben Admin 5（Ant Design Vue）。侧栏、顶栏、多标签页保持 Vben 默认样式，只通过 preferences 和 Ant Design Vue theme 套用 `tokens.ts`。不要引入 Element Plus、Naive UI、TDesign、antdv-next，不要恢复 `@crdp/admin-shell`。
- 组件优先用 Ant Design Vue；业务组件按 `components.md` 的名称、props、事件实现。样板在 `apps/web-hospital/src/components/followup/`，说明见 `docs/design/reference-pages.md`。表单引擎仍在 `form-engine`，查看器仍在 `viewer`。
- 表格每页 20 条；整页表单在新标签页打开；Enter 不提交，Ctrl/⌘+S 暂存，Ctrl/⌘+Enter 提交。
- 只有 `pages.md` 标明的 5 个页面做平板 / 手机适配，其余页面在手机上显示 `MobileBlocker`。
- 水印：`姓名（账号）· 医院名称 · 预览时间（精确到分钟）`，所有报告单预览都要有。
- 菜单与按钮由后端下发（`/menus`、`me.permissions`）；收到 40300 或 `permissionVersion` 变化时刷新菜单与权限。
- 接口类型写在 `packages/api`，与 `docs/api/` 一致；不要在页面里拼 URL 字符串散落各处。
- 文案用简体中文，按 `design-system.md` §15–16（状态横幅与提示文案照抄）；空状态、错误、加载态都要做；演示数据按 §17。

## 8. 完成定义（Definition of Done）

一个任务只有同时满足以下各项才算完成：

1. 实现覆盖该任务在 `tasks.csv` 中“关联需求”的全部需求编号，并满足这些需求在 `docs/requirements/` 中的**验收标准**；延后（P2 / 首期不做）的编号不实现。
2. 后端：单元 / 集成测试覆盖第 6 节列出的情形；前端：关键逻辑（权限、规则、表单校验、自动保存、脱敏计时）有单测。
3. 本地第 4 节全部命令通过；推送后 CI 全绿。
4. 数据库变更有 Flyway 脚本；新权限码、菜单、开关有迁移；没有修改旧脚本。
5. 遵守第 5 节全部规则；新增审计点、脱敏字段、数据范围声明在 PR 描述中列出。
6. 界面与 `pages.md` / `components.md` 一致（布局、文案、状态、权限显隐、响应式范围）。
7. 文档同步：接口与 `docs/api/` 不一致时更新文档（把“推断，待确认”改为实际定义）；遇到新疑问追加到 `docs/open-questions.md`。
8. 提交说明格式：`feat(followup): E15-T01 任务状态流转 [A-FU-003 A-FU-010]`——写任务编号和需求编号。

## 9. 选择下一个任务

1. 按里程碑顺序：M0 → M1 → M2 → M3 → M4 → M5 → M6。
2. 同一里程碑内按 `tasks.csv` 行序取第一个“依赖全部完成”的任务。
3. 只做类型为“后端 / 前端 / 全栈”的任务；“测试”任务可以写自动化用例；“运维”任务涉及华为云账号或真实云资源（CCE、RDS、OBS、ELB、WAF、等保）的部分**不要执行**，只产出脚本 / IaC / 文档草稿；“产品”任务（配置、UAT、医院沟通）交给人。
4. 关键路径 E02 → E07 → E11 → E14 → E15 → E19 优先。
5. 一个任务一个分支 / PR，不要把多个任务混在一起。

## 10. 拿不准时

**问人，不要发明需求。** 具体做法：

- 规格没写、文档标“推断，待确认”的：按文档建议实现，并在 PR 中注明。
- `open-questions.md` 已列出的：按“暂行做法”实现，注明 OQ 编号。
- 两份文档矛盾且没有 OQ 覆盖、需要新增业务规则、需要改权限模型 / 数据模型 / 安全边界、需要真实数据或云账号：**停下，写清问题与选项，交给人决定**，同时追加到 `open-questions.md`。
- 不要为了“完整”而实现规格里明确“首期不做”的功能（如预警 A-ALR、DICOM、生存分析、通知患者、图片增强）。
