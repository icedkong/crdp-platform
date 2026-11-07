# 导入与导出（import-export）

> 后端：`crdp-followup`（导入导出作业，异步）+ 平台审批。表：`imp_job`、`imp_row`（E18-T02 起，tech-plan §12 命名）、`job_export`、`job_export_approval`。
> 页面：`/followup/patient/import`（导入向导）、患者列表「导出」、审计中心导出。
> 导入端点已随 **E18-T06 定稿落地**（`ImportJobController` / `ImportJobService`，OQ-101⑤ 端点归属）。需求：A-PAT-008、A-PAT-009、A-PAT-018（开关 `wide_import`）、A-WARD-006、C-PAT-014、PLT-ORG-003、PLT-ORG-007、PLT-AUD-003、PLT-AUD-007。

## 1. 患者导入（A-PAT-008、A-PAT-018；`followup:patient:import`，开关 `wide_import` 关闭 → 40301）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/followup/imports/template?type=STANDARD|WIDE` | 下载模板（xlsx 附件；模板内只有表头与示例行，**示例行全部是虚构数据**，导入前删除）。列来自字段字典：STANDARD =「基线」「随访记录」两张（启用字段显示名 + 固定身份列）；WIDE = 单张宽表（前置 9 + 锚点基线列 + 1 组锚点随访列） |
| POST | `/api/hospital/followup/imports` | multipart 上传（字段 `file`）→ `{ jobId }`，预检**异步**开始（OQ-101⑤：原文件按租户前缀写对象存储，`imp_job.storage_key` 回填）；同一租户同时只允许一个导入作业，已有运行中 → 40901；写 IMPORT_UPLOAD 审计 |
| GET | `/api/hospital/followup/imports/{jobId}` | 作业视图（向导轮询一次拿全）：状态机 `UPLOADED → PRECHECKING → PRECHECKED \| PRECHECK_FAILED → IMPORTING → DONE \| PARTIAL_FAILED`（终态 CANCELLED / ROLLED_BACK）、预检进度 `stagedRows`、`errorCount` / `warnCount`、版面 / 整体错误清单、固化选项（`duplicateStrategy` / `defaultWardId`）、`stats`（批次与四类计数 + `failedBatches`）、`batches[]`（按批状态与计数） |
| GET | `/api/hospital/followup/imports/{jobId}/issues?level=ERROR|WARN&pageNo=&pageSize=` | 预检问题分页：工作表、行号、列字母、表头、原因、级别；**不回显单元格原值**（敏感列脱敏口径 OQ-101③），原值与标注看下面的批注版报告 |
| POST | `/api/hospital/followup/imports/{jobId}/confirm` | `{ duplicateStrategy: UPDATE|SKIP, defaultWardId? }`；有 ERROR 时 40001（A-PAT-018 错误行不可导入）；prepare 会话内冻结选项与操作人范围快照后**异步执行**（E18-T05 两段口），进度看作业视图批次 |
| POST | `/api/hospital/followup/imports/{jobId}/cancel` | 取消（只允许确认前：UPLOADED / PRECHECK_FAILED / PRECHECKED → CANCELLED；进行中不可打断，已提交的用回滚）；写 IMPORT_CANCEL 审计 |
| POST | `/api/hospital/followup/imports/{jobId}/retry` | 重试失败批次（PARTIAL_FAILED → IMPORTING，只重跑 FAILED 批，tech-plan §12⑥） |
| POST | `/api/hospital/followup/imports/{jobId}/rollback` | 回滚整个作业（全部已完成批次按写入逆序删，E18-T05；写 IMPORT_ROLLBACK 审计） |
| POST | `/api/hospital/followup/imports/{jobId}/batches/{batchNo}/rollback` | 回滚一批（只允许已完成批次） |
| GET | `/api/hospital/followup/imports/{jobId}/batches` | 批次列表（PENDING / RUNNING / DONE / FAILED / ROLLED_BACK 与四类计数、失败原因） |
| GET | `/api/hospital/followup/imports/{jobId}/result` | **批注版校验报告**下载（E18-T03 `annotatedReport`：错误 / 警告单元格标色加批注 + 首张「校验报告」问题清单，原值敏感列显示「（已脱敏）」）；xlsx 附件直接下载（非签名链接——报告按暂存行重建、按需生成不落对象存储，OQ-102①），预检完成后可用；每次下载写 DATA_ACCESS/DOWNLOAD 审计（带文件哈希） |

- 宽表（A-PAT-018）：识别 9 个前置列 + 212 个基线列 + N 组 × 54 个随访列（组数不限）；列定义来自字段字典（`docs/requirements/hospital-a-spec.md` 第 4 章“导入格式说明”与附录 A），**不要在代码里硬编码列号以外的业务含义**。
- **版面适配器已落地（E18-T01，无独立 HTTP 端点）**：`crdp-followup` importer domain 的 `ImportWorkbookParser` 按工作表结构识别版面（「基线」+「随访记录」两张 → 长表；单张 → 宽表），`WideLayoutAdapter` / `LongLayoutAdapter` 都解析到同一模型 `ImportParseResult`（患者记录：病案号、原系统编号、前置列（宽表专属）、基线字段值、按年度的随访值；单元格保留行 / 列坐标与原文，供 T03 批注版校验报告）。列 / 表头不匹配抛 `ImportLayoutException` 整体报错（差异清单带列字母，不做猜测）；数据级问题（重复、随访年度解析不了、随访行找不到患者）留在模型里由校验阶段报告。表头匹配口径见 OQ-100：宽表列 = 字段字典带「原表列名」锚点的字段（212 基线 / 54 随访，停用的 #217 也在内），长表列 = 启用字段的显示名（213 / 53）。
- **流式解析与暂存已落地（E18-T02，`ImportStagingService`，无独立 HTTP 端点；上传端点随 E18-T06）**：框架 `ExcelGateway`（ADR 0007，JDK zip + StAX 流式逐行，不引 POI）→ importer infra `StreamingImportWorkbook`（兑现 OQ-100④ 接入缝，适配器不改动）→ 适配器逐行回调 → `ImportStagingService` 按批（500 行）写 `imp_row` 并累加 `imp_job.staged_rows`——任何时刻内存只有一批（验收「大文件不占满内存」，`StreamingXlsxMemoryTest` 以 `-Xmx64m` 子进程读 130MB+ 内容核对）。状态机 `UPLOADED → PRECHECKING → PRECHECKED | PRECHECK_FAILED`；版面 / 表头整体错误落 `imp_job.layout_problems`（不写行）、预检失败清掉半成品行；建作业时同一租户已有活跃作业 → 40901（tech-plan §12「同一租户同一时间只允许一个导入作业」）。暂存口径见 OQ-101：宽表一行 = 一位患者（`row_type=PATIENT`）；长表基线行 / 随访行分存（`BASELINE` / `FOLLOWUP`），跨行「病案号 + 随访年度」关联与查重由校验阶段按 `imp_row.mrn_blind`（与 `pt_patient.mrn_blind` 同 purpose）做；整行解析结果（含姓名 / 病案号等敏感明文）加密落 `payload_cipher`（`imp-row-payload` purpose，`ImporterEncryptedColumns` 登记），解析阶段已知问题（随访年度解析不了、病案号为空）落行级 `problems`。
- **校验与批注版校验报告已落地（E18-T03，`ImportValidationService`，无独立 HTTP 端点；问题 / 报告下载端点随 E18-T06）**：预检流程在暂存完成后随即校验（状态仍 PRECHECKING，任何异常统一落 PRECHECK_FAILED），问题落 `imp_issue`（followup V71：工作表、行号、列号 + 列字母、表头、级别、类型码、原因——原因不回显敏感字段原值，OQ-101③ 同口径），重复执行幂等（先清旧问题）。级别口径：**ERROR** = 类型 / 格式错误、超硬范围、选项不在字典内、病案号为空、随访年度无效、同一病案号 + 随访年度两行、随访行病案号不在基线表（confirm 被拦，E18-T05）；**WARN** = 超提示范围、必填为空（历史导入仅提示）、Q36「0/1」按空值导入、Q37「编码 + 文字」取编码导入、宽表前置列与基线列不一致（以基线列为准）、派生字段与重算结果不一致（以重算结果为准，`FormDerivedEngine`）、文件内病案号重复、病案号已存在于系统（`PatientBlindIndexService` 全院存在性，病区范围在导入确认时校验）。批注版报告 `annotatedReport(jobId)`：从暂存行重建源工作表（单元格原文 + 行列坐标都在 `payload_cipher`），错误 / 警告单元格标色（浅红 FFFFC7CE / 浅黄 FFFFEB9C）加 legacy 批注（`AnnotatedReportXlsx`，沿用 ADR 0007 无 POI 直写），首张「校验报告」表列出全部问题（级别、工作表、行、列字母、表头、原值——敏感列显示「（已脱敏）」、原因）；暂行口径见 OQ-102（报告按暂存行重建而非改写原文件字节、数值 × 2 单元格写法、是否类选项集判定、长表列位合并）。
- **病区值与字典值映射已落地（E18-T04 / A-WARD-006，无独立 HTTP 端点）**：基线字段「病区/中心」（按显示名定位，V38 种子 f007）的取值不走固定选项集，改按**病区字典**解析——匹配键 = 病区编码 / 名称 / **别名**（`ward.alias`，历史写法映射的载体，OQ-74④ 定稿复用该列、不建 ward_alias 表；病区管理页 E13-T08 已可维护别名），精确匹配（取值去首尾空白）。结果口径：命中唯一启用病区 → 通过（类型化值 = 病区 id，E18-T05 提交写 `pt_patient.ward_id` 复用同一映射）；**未映射（`WARD_UNMAPPED`）/ 对应病区已停用（`WARD_DISABLED`，A-WARD-008 停用病区不能进新患者）/ 一值匹配多个病区（`WARD_AMBIGUOUS`，编码 / 名称 / 别名冲突）都报 ERROR**——未映射值导入前必须处理（tasks.csv 验收）：修正原表，或在病区管理维护别名映射后重新预检；病区字典为空时错误提示引导先建病区。空值不是未映射值：仍按「必填字段为空」警告口径（历史导入仅提示，向导兜底病区随 E18-T05/T06 落地）。录入侧同口径已由 E13-T08 落地：登记 / 转病区走病区字典选择且后端校验病区启用，表单保存的 select 值必须落在选项集内（`FormDataValidator`），自由文本进不了病区字段。病区字段不存在于字典（其他租户形态）时不做病区检查。
- **分批提交与历史随访记录生成已落地（E18-T05 / A-PAT-018，`ImportCommitService`；confirm / 进度 / 回滚端点已随 E18-T06 接入）**：确认提交 `confirm(jobId, { duplicateStrategy: UPDATE|SKIP, defaultWardId? })` 两段实现——`prepare`（会话内：非 PRECHECKED 40901；**存在 ERROR 问题 40001**；策略 / 兜底病区非法 40001——兜底病区 = 病区单元格为空的行的落区，E18-T04 遗留接线点；选项固化进 `imp_job.options_json`，操作人数据范围**冻结**进 `imp_job.scope_json`〔followup V73，与 job_export.scope_json 同口径〕——提交可被异步派发，「只能导入本人可见病区」按冻结快照逐行校验，越范围行跳过并计入批次 skipped）+ `execute`（会话无关）。**分批**（tech-plan §12⑥「每批约 200 名患者一个事务」）：患者行按 `imp_row.id` 连续切批（`imp_batch`），每批一个事务（`TransactionTemplate`）、失败批只标 FAILED（fail_reason 非敏感摘要）不影响其他批，`retryFailed` 单独重跑失败批；终态 DONE / PARTIAL_FAILED（状态机「导入中 → 部分失败 → 重试失败批次 → 导入中」）。**单患者写入**：字典 `storage=column` 字段落 `pt_patient` 实体列（性别按选项显示名男 / 女转 M / F、病区走 E18-T04 映射、空病区用兜底病区；姓名 / 性别空或病区无法确定则整行跳过计数——历史导入「错误行跳过」口径）；`storage=json` 字段按分类写 `pt_baseline_section`（正式保存口径 version 1）并重算基线资料完整度（A-PAT-019）；「电话N」加密字段写 `pt_contact` 版本（source=IMPORT，同值不新增版本、格式非法跳过）；派生字段照 A-FLD-005 重算（来源缺失保留导入值并标 data_meta.manual）；Q37 文字进 `data_meta.note`。重复病案号按策略：SKIP 跳过；**UPDATE 只改有变化的字段**（实体列动态 SET、分节合并、年度记录覆盖）并写 `fe_field_history`（ChangeSource=IMPORT）——空单元格不清既有值（OQ-104①）。**入组与年度记录**（tech-plan §12⑦）：加入 auto_enroll 预置计划（`PlanEnrollmentService.enrollForImport`，起算日期 = 出院日期快照，**不建任务**——当前窗口的当年任务由每日调度创建，A-PAT-018 流程 6）；年度规划 `ImportYearPlanner`：**有数据的年度 → `fu_record` source=HIST_IMPORT（直接 SUBMITTED、visit_date=实际随访日期、不受 ±7 天窗口限制，V73 fu_record 新增排期锚点列 enrollment_id / node_id / occurrence / followup_year / plan_date / window_start / window_end，uk 入组 + 节点 + 第 N 次）**；**窗口已结束且无数据的年度 → source=HIST_MISSED（DRAFT 待补录，Q06 不计入逾期 / 通知 / 已随访 / 完整度）**；窗口未结束不生成；起算日期不可解析时记录只按年度（锚点留空）；随访记录「是否死亡」=是 → 患者状态 DEAD（source=SYSTEM），死亡年度之后的已结束窗口不再标历史未随访。**按批回滚**（tasks.csv 验收）：每批写数登记 `imp_batch_write`（实体类型 + id + 患者是否本导入新建），`rollbackBatch(jobId, batchNo)` / `rollbackJob(jobId)` 按写入逆序删除——**新建患者连主档与状态时间线 / 摘要一起删（等于「这批没导过」）**；更新患者保留主档与既有基线值（fe_field_history 已留痕），只删导入新建的记录 / 联系方式 / 分节 / 入组行（OQ-104②）；批次置 ROLLED_BACK、全部批次回滚后作业置 ROLLED_BACK；作业级 IMPORT_COMMIT / IMPORT_ROLLBACK 审计（行本身即业务记录，不逐患者写）。
- **导入向导页与进度已落地（E18-T06 / A-PAT-008）**：医院端 `/followup/patient/import`（`followup/patient/import`，隐藏路由 plt_menu 353，平台库 V58 + followup V74 / 种子 V128 授予 TENANT_ADMIN 与 FOLLOWUP_DOCTOR）；患者列表「导入」按钮按 `followup:patient:import` 显隐。向导三步（tasks.csv 验收「上传、校验、提交三步可见进度」）：上传（模板下载 STANDARD / WIDE + 拖拽上传 → jobId）、校验（轮询 stagedRows 进度 → 错误 / 警告计数 + 分级问题分页 + 批注版报告下载；有 ERROR 拦确认并给 40301 同款提示文案）、提交（重复策略 UPDATE / SKIP + 兜底病区选择 → 确认 → 按批次进度条与批次表 → 完成 / 部分失败重试 / 按批回滚 / 整作业回滚）。作业 id 存 sessionStorage（刷新续接）；同租户已有运行中作业时上传 40901 由统一拦截器提示；`wide_import` 关闭 → 40301、入口按钮按权限隐藏（规则 7 同口径）。
- 病区按导入文件中的病区列或向导中选择的病区（A-WARD-006）；导入人必须有该病区范围（E18-T05 按确认时冻结的 `imp_job.scope_json` 逐行校验，越范围行跳过并计入批次 skipped）。
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
- 向导端点（E18-T06，`ImportTemplateComposerTest` / `ImportJobServiceTest` / `ImportJobControllerTest`）：模板 STANDARD / WIDE 生成后能被版面适配器直接识别与解析（示例行全虚构、带「示例」标记）；上传 → 对象存储 → 建作业 → 异步派发预检（对象键 imports/ 前缀、storage_key 回填、IMPORT_UPLOAD 审计）；`wide_import` 关闭 → 40301；作业视图聚合（stagedRows / 错误警告计数 / 批次进度与 failedBatches）；问题分页级别校验（非 ERROR|WARN → 40001）；取消只允许确认前（PRECHECKING / IMPORTING → 40901）；重试只允许 PARTIAL_FAILED；报告下载带 DATA_ACCESS 审计。
- 病区值映射（E18-T04，`ImportWardDirectoryTest` / `ImportWardMappingTest` / `ImportFieldRuleLoaderTest`）：编码 / 名称 / 别名（历史写法）命中通过且类型化值为病区 id；未映射 / 已停用 / 一值多区 → ERROR（未映射值导入前必须处理）；空值仍走必填为空警告；字典无「病区/中心」字段时不做病区检查。
- 确认提交与年度记录（E18-T05，`ImportCommitServiceTest` / `ImportYearPlannerTest`）：非 PRECHECKED 40901、有 ERROR 40001、策略 / 兜底病区非法 40001；宽表与长表都能生成「第 N 年历史导入（SUBMITTED + 实际随访日期 + 排期锚点）」与「窗口已结束无数据年度历史未随访（DRAFT，Q06）」，窗口未结束不生成；死亡 → 患者状态 DEAD 且死亡年度之后不标历史未随访；每 200 名患者一批、每批一个事务、失败批隔离（PARTIAL_FAILED）可单独重试（DONE）；SKIP 跳过既有患者、UPDATE 只改有变化的字段并留字段历史；回滚按批删写数（新建患者连主档、更新患者保留主档只删导入子行）；越冻结范围病区跳过、空病区用兜底病区 / 无兜底跳过。
- 普通用户导出未审批时 download → 40901；审批后可下载，审计有记录。
- 越范围患者不进入导出文件（E13-T09 已锁：病区受限医生的导出文件不含其他病区患者，按冻结范围快照取数）。
- 患者导出（E13-T09，`PatientExportServiceTest` / `PatientExportDeleteIT`）：租户管理员免审批直接生成；普通用户待审批（无审批权 40300、重复审批 40901、申请人不能自审 40901）；文件含 OQ-22 水印与水印码、姓名 / 病案号脱敏（明文断言不出现）；valueMode CODE / LABEL；scope 非 PATIENT、valueMode、fields 白名单外 40001；未审批 / 生成中 / 过期下载 40901、他人作业 40400；模块关闭 40301；提交（EXPORT）/ 审批（OPERATION 前后值）/ 下载（DOWNLOAD 带文件哈希）全程审计。
