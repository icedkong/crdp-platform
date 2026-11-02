# 报告单 / 资料、文件、手机上传（report）

> 后端：`crdp-followup`（报告单子域，A-ATT-018 平台通用、按租户配置）+ 文件服务（OSS + 预览代理）。表：`rpt_report`、`rpt_report_link`（位置，见 OQ-02：统一用 `rpt_report_link`）、`rpt_report_file`、`rpt_type`、`rpt_name`、`rpt_indicator_tpl`、`sys_file`。
> 页面：`docs/design/pages.md` §4（报告单页签、左栏附件导航）、§6（上传、H5、批量、全院报告单）；组件：AttachmentNav、LocationPicker、WatermarkPreview、ReportFormModal。
> 路径除 §1 已标注「已实现（E10-T01）」的两个文件接口外，其余 **推断，待确认**。权限：上传 `followup:scan:upload`、下载 `followup:scan:download`、删除 `followup:scan:delete`、配置 `followup:scan:config`；全院报告单菜单种子是 `followup:report:view`，与规格待统一（见 OQ-12）。

## 1. 文件

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/files` | `followup:scan:upload` | multipart（字段 `file`，单文件）；返回 `{ fileId, sha256, size, mime, format, originalName }`（`pages` 随 E10-T03 回填）。后端用 Tika 按内容判断真实类型（不信任扩展名），拒绝可执行文件、脚本、宏 Office、压缩包等不支持格式及扩展名与真实类型不符的文件（A-ATT-008、C-SCAN-018）→ 40001 + 原因；写 `FILE_UPLOAD` 审计。**已实现（E10-T01）** |
| GET | `/api/hospital/files/policy` | 登录即可 | 本院生效的上传策略：`{ formats: [{ format, label, extensions, category, maxBytes }], maxDocumentMb, maxAudioMb, maxFilesPerReport }`（PLT-CFG-010，上传对话框前置提示用）。**已实现（E10-T01）** |
| GET | `/api/hospital/files/{fileId}/preview-url` | 能看到所属报告单 | 预览代理短时链接（≤ 5 分钟），每次写审计；前端不缓存（E10-T04） |
| POST | `/api/hospital/files/{fileId}/download-url` | `followup:scan:download` | 5 分钟签名链接，写审计（谁、何时、哪份报告单的哪个文件，A-ATT-006）（E10-T05） |
| GET | `/api/hospital/files/check-duplicate?sha256=` | `followup:scan:upload` | C 重复文件提示（C-SCAN-017，P1），只返回可见范围内的患者（脱敏） |

格式与大小按租户配置（A：单个 20 MB、每份 10 个，录音 50 MB；C：20 MB、每份 50 个）；超限 40001。上传配置是平台参数（`file.upload.allowed.types` / `file.upload.max.size.mb` / `file.upload.audio.max.size.mb` / `file.upload.max.files.per.report`，平台库迁移 V33 登记），租户经机构设置 → 租户参数覆盖；**每份数量上限在报告单保存时校验（fileIds ≤ maxFilesPerReport，随 E16 报告单创建落地），上传接口本身不感知报告单**。上传台账表 `sys_file`（租户库 followup V25）；E10-T02 起文件体只在对象存储（生产 OBS 业务桶，每次 PUT 强制 SSE-KMS 服务端加密；本机 / CI 为文件系统替身 `crdp.storage.type=local`，目录结构与桶一致），对象键 `t/{租户编码}/files/{年月}/{uuid}.{扩展名}`（租户前缀由存储端口按登录租户强制，一桶按前缀隔离，ADR 0006），台账不再存文件体（followup V26 清 body 列）。multipart 兜底上限 200 MB（`application.yml`）。不做 DICOM（A-ATT-009）与图片增强（C-SCAN-019）。

## 2. 报告单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/report-types` | `followup:patient:view` | 类型与字段集、默认电子化状态（C） |
| GET | `/api/hospital/followup/report-names?typeCode=` | 同上 | 名称字典，含指标模板 |
| POST | `/api/hospital/followup/reports` | `followup:scan:upload` | `{ patientId, typeCode, nameCode?, reportDate, hospital?, fields, indicators[], findings, conclusion, locations[≥1], fileIds[], note }`；位置为空 → 40001（A-ATT-002、C-SCAN-010） |
| GET | `/api/hospital/followup/reports/{id}` | `followup:patient:view` | 详情（含位置、文件列表、电子化状态） |
| PUT | `/api/hospital/followup/reports/{id}` | `followup:scan:upload` | `{ version, ... }`；乐观锁 409 |
| PUT | `/api/hospital/followup/reports/{id}/locations` | `followup:scan:upload` | 修改位置 `{ add[], remove[] }`，写操作日志（A-ATT-020、C-SCAN-020）。允许移除到 0 个 → 进“未指定位置” |
| DELETE | `/api/hospital/followup/reports/{id}` · `/files/{fileId}` | `followup:scan:delete` | 软删除，`{ reason }` 必填，审计（A-ATT-007、C-SCAN-016） |
| GET | `/api/hospital/followup/patients/{id}/reports?scope=baseline|followup|all&view=type|time` | `followup:patient:view` | 患者内报告单；`scope` 给左栏附件导航（AttachmentNavItem，components.md §5），`view` 给双树（A-ATT-010、C-SCAN-013） |
| POST | `/api/hospital/followup/reports/search` | `followup:report:view`（种子） | 全院报告单（A-ATT-016、C-SCAN-014）：患者、类型、名称、报告时间、上传日期、上传人、位置（含 `UNASSIGNED`）、电子化状态（C） |
| POST | `/api/hospital/followup/reports/batch` | `followup:scan:upload` | 批量 `{ ids[], action: CHANGE_TYPE|SET_LOCATIONS|MARK_NO_ENTRY, payload }` |
| POST | `/api/hospital/followup/reports/batch-create` | `followup:scan:upload` | 批量上传（A-ATT-014、C-SCAN-007，P1）：≤ 50 个文件，`mode: ONE_PER_FILE|MERGE` |

位置对象（与前端 `ReportLocation` 一致）：`{ linkType: baseline|followup_record|implant_record, linkRecordId?, anchorType: category|section|null, anchorKey?, templateVersion }`。模板改版后分组不存在时，后端把位置退回到上一级并置 `adjusted=true`（A-ATT-021、C-SCAN-021）。

通话记录（A-ATT-017）：沟通记录上传的录音自动生成 `typeCode=CALL` 报告单；两边是同一数据，删除任一侧都提示。

## 3. 字段映射与转录（A-ATT-013/019、C-SCAN-005/011/012）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/reports/{id}/mapping/preview` | `followup:scan:upload` | 返回将写入的目标记录、字段、原值、新值；目标必须是报告单位置之一，多条可映射时返回候选让用户选 |
| POST | `/api/hospital/followup/reports/{id}/mapping/confirm` | `followup:scan:upload` + 目标记录写权限 | `{ targetRecordId, targetVersion, fieldKeys[] }`；逐项确认后写入并留字段历史；冲突 409 |
| POST | `/api/hospital/followup/reports/{id}/digitize-status` | `followup:scan:upload` | C：`{ status: PENDING|DONE|NO_ENTRY }`；转录提交后自动变“已电子化” |

## 4. 待归档区（C-SCAN-009，P1；开关 `report_inbox` 推断）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/hospital/followup/report-inbox/search` | 未选患者的文件，同样按科室病区隔离（按上传人所在科室） |
| POST | `/api/hospital/followup/report-inbox/{id}/archive` | `{ patientId, typeCode, locations[≥1], ... }` → 生成报告单 |

## 5. 手机拍照上传（A-ATT-015、C-SCAN-008）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/upload-tokens` | `followup:scan:upload` | `{ patientId, presetLocation? }` → `{ token, qrUrl, expiresAt }`；15 分钟、一次性、绑定当前用户与患者 |
| GET | `/api/h5/upload/{token}` | token（C 还要求手机端已登录本院账号） | 患者脱敏信息、可选类型与位置 |
| POST | `/api/h5/upload/{token}/files` | token | 上传图片（前端已压缩、去 EXIF） |
| POST | `/api/h5/upload/{token}/submit` | token | 提交 → 报告单进入电脑端对话框 / 直接创建；token 作废 |

token 过期或已用：返回 40101，前端显示“二维码已过期，请在电脑上重新生成”（文案推断）。`/api/h5/**` 需在 SecurityConfig 单独放行并限流。

## 6. 报告单设置（`/system/report-config`，`followup:scan:config`）

`GET/POST/PUT /api/hospital/followup/report-config/types`（类型、字段集、默认电子化状态）、`.../names`（名称字典）、`.../indicator-templates`（项目名称、代码、参考范围、单位、顺序）、`.../mappings`（心脏超声 → 超声字段、程控报告单 → 程控字段）、`.../file-policy`（格式与大小）。所有变更写审计。

## 7. 测试要点

- 无位置创建 → 40001；移除最后一个位置 → 进入未指定位置列表。
- 预览 / 下载各写一条审计；无 `scan:download` 时 download-url → 40300。
- 越范围患者的报告单在全院列表不出现，按 id 访问 40400；重复文件提示不泄露范围外患者。
- 伪造扩展名的 exe → 被拒；超大文件 → 40001。
- H5 token：过期、重复使用、换患者均失败。
