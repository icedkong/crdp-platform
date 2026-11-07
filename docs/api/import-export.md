# 导入与导出（import-export）

> 后端：`crdp-followup`（导入导出作业，异步）+ 平台审批。表：`imp_job`、`imp_row`（E18-T02 起，tech-plan §12 命名）、`job_export`、`job_export_approval`。
> 页面：`/followup/patient/import`（导入向导）、患者列表「导出」、审计中心导出。
> 路径 **推断，待确认**（导入端点随 E18-T06 落地）。需求：A-PAT-008、A-PAT-009、A-PAT-018（开关 `wide_import`）、A-WARD-006、C-PAT-014、PLT-ORG-003、PLT-ORG-007、PLT-AUD-003、PLT-AUD-007。

## 1. 患者导入（A-PAT-008、A-PAT-018；`followup:patient:import`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/followup/imports/template?type=STANDARD|WIDE` | 下载模板（模板内只有表头与示例行，**示例行全部是虚构数据**） |
| POST | `/api/hospital/followup/imports` | multipart 上传 → `{ jobId }`；同一租户同时只允许一个导入作业，已有运行中 → 40901 |
| GET | `/api/hospital/followup/imports/{jobId}` | 状态 `UPLOADED → PRECHECKING → PRECHECKED → IMPORTING → DONE|FAILED|CANCELLED`、进度、统计（错误 / 警告 / 新增 / 更新 / 跳过） |
| GET | `/api/hospital/followup/imports/{jobId}/issues?level=ERROR|WARN&pageNo=` | 预检报告：行号、列字母、表头、原值（敏感列脱敏显示）、原因、级别 |
| POST | `/api/hospital/followup/imports/{jobId}/confirm` | `{ duplicateStrategy: UPDATE|SKIP }`；有 ERROR 时 40001 |
| POST | `/api/hospital/followup/imports/{jobId}/cancel` | 取消 |
| GET | `/api/hospital/followup/imports/{jobId}/result` | 结果文件（签名链接，写审计） |

- 宽表（A-PAT-018）：识别 9 个前置列 + 212 个基线列 + N 组 × 54 个随访列（组数不限）；列定义来自字段字典（`docs/requirements/hospital-a-spec.md` 第 4 章“导入格式说明”与附录 A），**不要在代码里硬编码列号以外的业务含义**。
- **版面适配器已落地（E18-T01，无独立 HTTP 端点）**：`crdp-followup` importer domain 的 `ImportWorkbookParser` 按工作表结构识别版面（「基线」+「随访记录」两张 → 长表；单张 → 宽表），`WideLayoutAdapter` / `LongLayoutAdapter` 都解析到同一模型 `ImportParseResult`（患者记录：病案号、原系统编号、前置列（宽表专属）、基线字段值、按年度的随访值；单元格保留行 / 列坐标与原文，供 T03 批注版校验报告）。列 / 表头不匹配抛 `ImportLayoutException` 整体报错（差异清单带列字母，不做猜测）；数据级问题（重复、随访年度解析不了、随访行找不到患者）留在模型里由校验阶段报告。表头匹配口径见 OQ-100：宽表列 = 字段字典带「原表列名」锚点的字段（212 基线 / 54 随访，停用的 #217 也在内），长表列 = 启用字段的显示名（213 / 53）。
- **流式解析与暂存已落地（E18-T02，`ImportStagingService`，无独立 HTTP 端点；上传端点随 E18-T06）**：框架 `ExcelGateway`（ADR 0007，JDK zip + StAX 流式逐行，不引 POI）→ importer infra `StreamingImportWorkbook`（兑现 OQ-100④ 接入缝，适配器不改动）→ 适配器逐行回调 → `ImportStagingService` 按批（500 行）写 `imp_row` 并累加 `imp_job.staged_rows`——任何时刻内存只有一批（验收「大文件不占满内存」，`StreamingXlsxMemoryTest` 以 `-Xmx64m` 子进程读 130MB+ 内容核对）。状态机 `UPLOADED → PRECHECKING → PRECHECKED | PRECHECK_FAILED`；版面 / 表头整体错误落 `imp_job.layout_problems`（不写行）、预检失败清掉半成品行；建作业时同一租户已有活跃作业 → 40901（tech-plan §12「同一租户同一时间只允许一个导入作业」）。暂存口径见 OQ-101：宽表一行 = 一位患者（`row_type=PATIENT`）；长表基线行 / 随访行分存（`BASELINE` / `FOLLOWUP`），跨行「病案号 + 随访年度」关联与查重由校验阶段按 `imp_row.mrn_blind`（与 `pt_patient.mrn_blind` 同 purpose）做；整行解析结果（含姓名 / 病案号等敏感明文）加密落 `payload_cipher`（`imp-row-payload` purpose，`ImporterEncryptedColumns` 登记），解析阶段已知问题（随访年度解析不了、病案号为空）落行级 `problems`。
- 病区按导入文件中的病区列或向导中选择的病区（A-WARD-006）；导入人必须有该病区范围。
- 导入写审计（作业级 + 行级摘要）；上传的原始文件按租户加密存储，保留期限见 OQ-28。
- **测试用例只能用虚构数据**；仓库里不得出现医院提供的样例文件。

## 2. 组织 / 用户导入（PLT-ORG-003、PLT-ORG-007，P1）

**科室导入已实现（E06-T03）**：`GET /api/hospital/system/orgs/import-template` + `POST /api/hospital/system/orgs/import`（`tenant:org:edit`）——**同步一次性模型**（OQ-52），上传即逐行校验并落库、单行失败不影响其他行、返回逐行结果；详见 `system.md` §1。§1 的异步作业模型（预检 / 确认 / 结果文件）保留给患者宽表导入（E15+/E18）。

**用户导入已实现（E06-T08）**：`GET /api/hospital/system/users/import-template` + `POST /api/hospital/system/users/import`（`tenant:user:import`）——与科室导入同一同步模型对齐（OQ-52），**只新建不更新既有用户**（账号已存在该行失败，改配走用户管理编辑），逐行校验口径与手工新建一致（账号 / 手机号本院唯一、角色与主科室必填、模板角色先复制），**初始密码不随导入返回**（随机密码只落哈希、首登强制改密，用户首次短信登录后设置）；详见 `system.md` §2。

## 3. 导出（A-PAT-009、C-PAT-014、PLT-AUD-007；E13-T09 已实现 scope=PATIENT）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/exports` | `followup:patient:export`（敏感）+ 二次认证 | **E13-T09 已实现**（scope=PATIENT）。`{ scope, valueMode: CODE\|LABEL, fields[], condition: { filters?, condition? }, reason? }` → `{ exportId, status }`；condition 与列表检索同构（**不含检索词 keyword**，与保存的筛选同口径）；租户管理员本人导出免审批直接生成，普通用户待租户管理员审批 |
| GET | `/api/hospital/followup/exports` | `followup:patient:export` | **E13-T09 已实现**。我的导出（biz_type=PATIENT） |
| GET | `/api/hospital/followup/export-approvals` | `followup:patient:export:approve`（租户管理员） | **E13-T09 已实现**。待审批列表（患者导出专用端点，与审计导出审批 `/api/hospital/system/export-approvals` 分离、权限独立） |
| POST | `/api/hospital/followup/export-approvals/{id}/approve` · `/reject` | `followup:patient:export:approve` | **E13-T09 已实现**。`{ comment }`；申请人不能审批自己的申请（40901）；审批通过按**固化条件**异步生成 |
| GET | `/api/hospital/followup/exports/{exportId}/download` | `followup:patient:export`（敏感）+ 二次认证 | **E13-T09 已实现**。直接下载文件（仅申请人，READY 且生成后 **24 小时**内有效——与审计导出同一张 job_export 状态机与时限口径，OQ-56）；每次下载写数据访问审计 |

- 审批：租户管理员本人导出无需审批；普通用户由租户管理员审批（平台账号导出由平台管理员审批，PLT-AUD-007）。
- 导出内容按**导出人**的数据范围与字段策略：提交时冻结申请人数据范围快照（job_export.scope_json，base V20），异步生成按冻结范围重建会话取数（审批人不放大范围）；`HIDDEN` 字段不导出，`MASKED` 字段导出脱敏值（是否允许导出明文见 OQ-20）——姓名 / 病案号在文件中为脱敏值。
- 水印与水印码：每行首列 `姓名（账号）· 医院名称 · 时间（到分钟）· 水印码 XXXXXXXXXXXX`（OQ-22 + tech-plan「导出水印码」暂行口径，见 OQ-75）；水印码为绑定「导出作业 + 申请人 + 行数」的 SHA-256 前 12 位，泄露文件可凭码对账定位作业。
- 条件提交时校验并固化（非法筛选 / 高级检索目录外字段 40001），审批通过后按固化条件重放生成；单次导出行数上限 50 000（超出文件尾附截断提示）。
- scope：PATIENT = 患者列表列（14 列白名单，`fields[]` 可选取子集）；BASELINE / FOLLOWUP / IMPLANT 随 E13-T02 / E15 / E21 数据落地在同一框架扩展（当前请求返回 40001）。

## 4. 测试要点

- 同租户第二个导入作业 → 40901；不同租户可并行。
- 预检发现错误时 confirm → 40001。
- 普通用户导出未审批时 download → 40901；审批后可下载，审计有记录。
- 越范围患者不进入导出文件（E13-T09 已锁：病区受限医生的导出文件不含其他病区患者，按冻结范围快照取数）。
- 患者导出（E13-T09，`PatientExportServiceTest` / `PatientExportDeleteIT`）：租户管理员免审批直接生成；普通用户待审批（无审批权 40300、重复审批 40901、申请人不能自审 40901）；文件含 OQ-22 水印与水印码、姓名 / 病案号脱敏（明文断言不出现）；valueMode CODE / LABEL；scope 非 PATIENT、valueMode、fields 白名单外 40001；未审批 / 生成中 / 过期下载 40901、他人作业 40400；模块关闭 40301；提交（EXPORT）/ 审批（OPERATION 前后值）/ 下载（DOWNLOAD 带文件哈希）全程审计。
