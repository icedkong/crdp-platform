# 报告单 / 资料、文件、手机上传（report）

> 后端：`crdp-followup`（报告单子域，A-ATT-018 平台通用、按租户配置）+ 文件服务（OSS + 预览代理）。表：`rpt_report`、`rpt_report_link`（位置，见 OQ-02：统一用 `rpt_report_link`）、`rpt_report_file`、`rpt_type`、`rpt_name`、`rpt_indicator_tpl`、`rpt_mapping` / `rpt_mapping_item`（字段映射，E23-T03）、`sys_file`。
> 页面：`docs/design/pages.md` §4（报告单页签、左栏附件导航）、§6（上传、H5、批量、全院报告单）；组件：AttachmentNav、LocationPicker、WatermarkPreview、ReportFormModal。
> 路径除各表已标注「已实现」的接口（E10 文件、E16-T01 配置与目录、E16-T02 创建 / 患者内清单 / 位置树、E16-T03 批量上传、E16-T07 映射预览与确认写入）外，其余 **推断，待确认**。权限：上传 `followup:scan:upload`、下载 `followup:scan:download`、删除 `followup:scan:delete`、配置 `followup:scan:config`；全院报告单菜单种子是 `followup:report:view`，与规格待统一（见 OQ-12）。

## 1. 文件

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/files` | `followup:scan:upload` | multipart（字段 `file`，单文件）；返回 `{ fileId, sha256, size, mime, format, originalName, pages }`（`pages` 图片恒为 1，PDF 待回填，OQ-61）。后端用 Tika 按内容判断真实类型（不信任扩展名），拒绝可执行文件、脚本、宏 Office、压缩包等不支持格式及扩展名与真实类型不符的文件（A-ATT-008、C-SCAN-018）→ 40001 + 原因；写 `FILE_UPLOAD` 审计。**已实现（E10-T01；E10-T03 增补 `pages` 与图片缩略图）** |
| GET | `/api/hospital/files/policy` | 登录即可 | 本院生效的上传策略：`{ formats: [{ format, label, extensions, category, maxBytes }], maxDocumentMb, maxAudioMb, maxFilesPerReport }`（PLT-CFG-010，上传对话框前置提示用）。**已实现（E10-T01）** |
| GET | `/api/hospital/files/{fileId}/preview-url` | 登录即可 + `followup` 模块 | 预览代理短时链接（≤ 5 分钟，`crdp.file.preview.ttl-seconds`，配置写大按 5 分钟执行），**每次签发写 `FILE_PREVIEW` 审计**（用户、租户、文件、时间、IP）；返回 `{ url, expiresAt, format, mime, originalName, inlinePlayable }`（AMR 与 XLSX 的 `inlinePlayable=false`，只下载）；文件不存在 / 已删除 → 40400。「能看到所属报告单」的数据范围校验随 E16 报告单子域（sys_file 尚未挂 rpt_report_file，见 OQ-62 ①）；**挂在沟通记录上的录音自 E15-T04 起按患者数据范围校验（越范围 40400，下载口同）**。**已实现（E10-T04；E15-T04 收紧沟通录音）** |
| GET | `/api/hospital/files/preview/{token}` | 公开口（随机令牌，SecurityConfig permitAll + 按 IP 每小时限流） | 预览内容取流（pdf.js、docx-preview、原生 `<audio>` 带不了登录令牌）：凭 preview-url 签发的 256 位随机令牌（Redis 只存 SHA-256 摘要，值 = 租户ID/租户编码/文件ID，TTL ≤ 5 分钟，窗口内可多次取流）恢复租户上下文后从对象存储按流返回；响应 `no-store` 不缓存、inline 展示、nosniff；令牌无效 / 过期 / 跨租户 / 对象缺失一律 40400，超限 42900。**已实现（E10-T04）** |
| POST | `/api/hospital/files/{fileId}/download-url` | `followup:scan:download`（敏感权限，V18 plt_sensitive_perm、V34 按钮 plt_menu 232） | 5 分钟 HMAC 签名链接（`crdp.file.download.ttl-seconds`，配置写大按 5 分钟执行；密钥 `crdp.file.download.signing-key`，生产经 `CRDP_FILE_DOWNLOAD_SIGNING_KEY` 注入，缺失启动即失败），**每次签发写 `DOWNLOAD` 数据访问审计**（PLT-AUD-003：谁、何时、哪个文件 + 文件 SHA-256，A-ATT-006；报告单关联随 E16 rpt_report_file 补进描述）；返回 `{ url, expiresAt, format, mime, originalName, sizeBytes }`；文件不存在 / 已删除 → 40400。「能看到所属报告单」的数据范围校验随 E16 报告单子域（OQ-62 ① 同口径）。**已实现（E10-T05）** |
| GET | `/api/hospital/files/download/{token}` | 公开口（签名链接，SecurityConfig permitAll + 按 IP 每小时限流） | 下载内容取流：凭 download-url 签发的 HMAC-SHA256 签名（负载 `v1|租户ID|租户编码|文件ID|到期Unix秒`，到期进签名、篡改即失效，无状态不依赖 Redis；OQ-62 ③ 口径——预览用随机令牌、下载按技术方案用签名链接）恢复租户上下文后从对象存储读回原文件，**附件（attachment）返回**、`no-store` 不缓存、nosniff；签名无效 / 过期 / 跨租户（对象键前缀不符）/ 文件已软删除 / 对象缺失一律 40400，超限 42900。审计记在签发一次（与预览同口径，OQ-62 ②），窗口内多次取流不重复记。**已实现（E10-T05）** |
| GET | `/api/hospital/files/check-duplicate?sha256=` | `followup:scan:upload` | C 重复文件提示（C-SCAN-017，P1），只返回可见范围内的患者（脱敏） |

格式与大小按租户配置（A：单个 20 MB、每份 10 个，录音 50 MB；C：20 MB、每份 50 个）；超限 40001。上传配置是平台参数（`file.upload.allowed.types` / `file.upload.max.size.mb` / `file.upload.audio.max.size.mb` / `file.upload.max.files.per.report`，平台库迁移 V33 登记），租户经机构设置 → 租户参数覆盖；**每份数量上限在报告单保存时校验（fileIds ≤ maxFilesPerReport，随 E16 报告单创建落地），上传接口本身不感知报告单**。上传台账表 `sys_file`（租户库 followup V25）；E10-T02 起文件体只在对象存储（生产 OBS 业务桶，每次 PUT 强制 SSE-KMS 服务端加密；本机 / CI 为文件系统替身 `crdp.storage.type=local`，目录结构与桶一致），对象键 `t/{租户编码}/files/{年月}/{uuid}.{扩展名}`（租户前缀由存储端口按登录租户强制，一桶按前缀隔离，ADR 0006），台账不再存文件体（followup V26 清 body 列）。multipart 兜底上限 200 MB（`application.yml`）。不做 DICOM（A-ATT-009）与图片增强（C-SCAN-019）。

**图片缩略图（E10-T03，已实现）**：上传图片（jpg / png）通过校验后同步生成缩略图——等比缩到最大边 ≤ 128 px（`crdp.file.thumbnail.max-edge`，设计稿缩略图 64×64 的 2 倍视网膜余量，OQ-61），**保持原格式、不转码、不放大**（各边都不超过最大边的小图直接复用原字节）；缩略图对象键 `t/{租户编码}/thumbnails/{年月}/{uuid}.{扩展名}`（同一业务桶、同一租户前缀隔离），完整键记 `sys_file.thumbnail_key`（followup V27），`pages` 恒为 1。非图片不生成任何派生对象：**录音不转码、不另存副本**（MP3 浏览器原生播放），PDF / DOCX / XLSX 无缩略图（列表显示文件图标；PDF 首页缩略图不在首期范围）。Tika 认魔数但解码不出像素的损坏图片、单边 > 12000 px 或 > 4000 万像素的图片（防解压炸弹）→ 40001 拒绝，且发生在写对象存储之前（不留半成品）。缩略图的查看链路（预览代理、审计）随 E10-T04。

**预览代理与水印（E10-T04，已实现）**：预览**经应用代理按流返回、不缓存**（不暴露对象存储直链）——登录态调 `preview-url` 换短时链接，浏览器子资源凭令牌从 `/files/preview/{token}` 取流（OQ-62 ②：审计记在**签发**一次，取流不重复记，音频 range / pdf.js 分页多次请求不刷审计）。水印在前端（`packages/viewer`）：平铺文本「姓名（账号）· 医院名称 · 预览时间（精确到分钟）」，canvas 平铺、旋转 -20°、透明度约 0.15、`pointer-events: none`，**不可关闭**——节点被删 / 样式被改由 MutationObserver 立即重绘并每 2 秒自检兜底；MP3 / M4A / WAV 原生 `<audio controls controlsList="nodownload">` 播放（不转码、播放器内无下载按钮），AMR 与 XLSX 只下载（下载按钮按 `followup:scan:download` 显示，签名下载已随 E10-T05 落地：`download-url` 签发 + `/files/download/{token}` 附件取流）；PDF 用 pdf.js、DOCX 用 docx-preview 页内渲染（懒加载）。PDF 页数不回填 `sys_file.pages`：预览解析在前端完成，后端仍不引 PDF 解析依赖（OQ-61 ④）。

**签名下载（E10-T05，已实现）**：下载是敏感权限（`followup:scan:download`，plt_sensitive_perm；不进授权范围模板、不预授予内置角色，医院要用先由平台管理员在租户授权范围放开）——登录态调 `download-url` 换 **HMAC-SHA256 签名链接**（`{payload}.{sig}`，负载含租户与到期时间，5 分钟内有效、无状态不依赖 Redis），取流口凭签名恢复租户上下文后从对象存储附件返回原字节；**每次签发写一条 `DOWNLOAD` 数据访问审计**（含文件 SHA-256，`fileHash` 扩展字段），取流不重复记（OQ-62 ② 同口径：签名绑定签发人，5 分钟窗口内取流可归因）。签发后文件被软删除的，窗口内链接同样 40400（台账状态优先于签名）。

## 2. 报告单

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/report-types` | `followup:patient:view` | 类型与字段集、默认电子化状态（C）。**已实现（E16-T01）**：只返回启用类型，行 `{ code, name, nameRequired, defaultDigitizeStatus, fields: [{ key, code, label, type, unit, required, sortNo }] }`（fields 为该类型 fe_form REPORT 表单的启用字段，紧凑视图） |
| GET | `/api/hospital/followup/report-names?typeCode=` | 同上 | 名称字典，含指标模板。**已实现（E16-T01）**：`typeCode` 必填（缺失 / 不存在 40400），只返回启用名称，行 `{ id, typeCode, name, indicatorTplId, items: [...] }`——绑定的模板启用时 `items` 带出项目行（模板停用不再带出，只影响新报告单） |
| POST | `/api/hospital/followup/reports` | `followup:scan:upload` | `{ patientId, typeCode, nameId?, reportDate, fields?, indicators[{itemCode,result,refRange?}]?, findings?, conclusion?, locations[≥1], fileIds?[], note? }`；位置为空 → 40001「请至少选择 1 个位置」（A-ATT-002、C-SCAN-010）。**已实现（E16-T02）**：名称按类型 `nameRequired` 校验（40001，OQ-95③）；位置粒度三种——基线分类（`baseline`+`category`，锚点须在基线表单当前发布版分类内）、随访记录整条（`followup_record`+`anchorType=null`）、随访分组（`section`，锚点按该记录固化版本分类清单校验）；越患者记录 / 越范围患者 40400；`implant_record` 随 E21-T32（当前 40001）；附件去重后 ≤ `maxFilesPerReport`（40001）、逐个挂台账置 ATTACHED（不存在 40400、已挂他处 40001）；指标项目须来自名称绑定的启用模板（40001），参考范围与结果可解析时服务端记 ↑/↓；同一报告单同一位置只一行（服务层去重）；写 `RPT_CREATE` 审计（类型 · 名称 · 位置 / 附件 / 指标计数）。返回 `{ id, typeCode, typeName, name, reportDate, locationCount, fileCount }`。批量上传随 E16-T03（见下 `batch-create`） |
| GET | `/api/hospital/followup/reports/{id}` | `followup:patient:view` | 详情（含位置、文件列表、电子化状态） |
| PUT | `/api/hospital/followup/reports/{id}` | `followup:scan:upload` | `{ version, ... }`；乐观锁 409 |
| PUT | `/api/hospital/followup/reports/{id}/locations` | `followup:scan:upload` | 修改位置 `{ add[], remove[] }`，写操作日志（A-ATT-020、C-SCAN-020）。允许移除到 0 个 → 进“未指定位置” |
| DELETE | `/api/hospital/followup/reports/{id}` · `/files/{fileId}` | `followup:scan:delete` | 软删除，`{ reason }` 必填，审计（A-ATT-007、C-SCAN-016） |
| GET | `/api/hospital/followup/patients/{id}/reports?scope=all|baseline|followup` | `followup:patient:view` | 患者内清单（**已实现（E16-T02）**，报告单页签用）：`{ patientId, scope, items: [{ id, typeCode, typeName, name, reportDate, findings, conclusion, note, digitizeStatus, version, creatorName, createdAt, fileCount, locations: [{ linkType, linkRecordId, recordLabel, recordDate, anchorType, anchorKey, anchorLabel, templateVersion, adjusted }] }] }`，按报告时间倒序；`baseline` / `followup` 只保留含该侧位置的报告单（左栏附件导航 AttachmentNav 随 E16-T14 消费）；越范围患者 40400。`view=type|time` 双树随 E16-T04 |
| GET | `/api/hospital/followup/patients/{id}/report-locations` | `followup:patient:view` | 位置树（components.md §8 LocationPicker options；**已实现（E16-T02）**）：`[{ key, title, selectable, value?, children? }]` 两个分组根——基线资料（子节点 = 基线表单当前发布版分类，`value={baseline,category,分类key,当前发布版}`）与随访记录（子节点 = 该患者全部 fu_record 含 DRAFT，标题「第N年随访 日期 / 历史补录·计划外随访 日期」，`value={followup_record,recordId,anchorType=null,记录固化版本}`，孙节点 = 该记录表单分组 `section`）；越范围患者 40400 |
| POST | `/api/hospital/followup/reports/search` | `followup:report:view`（种子） | 全院报告单（A-ATT-016、C-SCAN-014）：患者、类型、名称、报告时间、上传日期、上传人、位置（含 `UNASSIGNED`）、电子化状态（C） |
| POST | `/api/hospital/followup/reports/batch` | `followup:scan:upload` | 批量 `{ ids[], action: CHANGE_TYPE|SET_LOCATIONS|MARK_NO_ENTRY, payload }` |
| POST | `/api/hospital/followup/reports/batch-create` | `followup:scan:upload` | 批量上传。**已实现（E16-T03 / A-ATT-014、C-SCAN-007）**：`{ patientId, mode: ONE_PER_FILE\|MERGE, typeCode, nameId?, reportDate, locations[≥1], fileIds[1..50], items?[] }`——先统一类型 / 名称 / 报告时间 / 位置，`items`（ONE_PER_FILE）逐份调整 `{ fileId, nameId?, reportDate?, locations? }`（空项沿用公共值，引用清单外文件 40001）；`ONE_PER_FILE` 每个文件一份、`MERGE` 合并为一份多页资料（文件按提交顺序排 sortNo，C-SCAN-006「可调整顺序」）。去重后 1–50 个文件（A-ATT-014「一次最多 50 个」），单份 ≤ `maxFilesPerReport`（40001 超限拦截，A-ATT-004）；不收指标 / 所见 / 结论 / 备注（批后逐份编辑）。**整批一个事务：任一份失败全部回滚**，前端整批重试（文件已上传、fileId 仍有效）。每份照常写 `RPT_CREATE`，另写一条 `RPT_BATCH_CREATE` 汇总（objectId = 首份报告单）。返回 `{ mode, reportCount, fileCount, reports: [CreatedView] }`；无 `scan:upload` 40300、越范围 / 跨租户患者 40400 |

位置对象（与前端 `ReportLocation` 一致）：`{ linkType: baseline|followup_record|implant_record, linkRecordId?, anchorType: category|section|null, anchorKey?, templateVersion }`。模板改版后分组不存在时，后端把位置退回到上一级并置 `adjusted=true`（A-ATT-021、C-SCAN-021）。

通话记录（A-ATT-017）：沟通记录上传的录音自动生成 `typeCode=CALL` 报告单；两边是同一数据，删除任一侧都提示。

## 3. 字段映射与转录（A-ATT-013/019、C-SCAN-005/011/012）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/reports/{id}/mapping/preview` | `followup:scan:upload` | **已实现（E16-T07）**：返回将写入的目标记录、字段、原值、新值；目标必须是报告单位置之一，多条可映射时返回候选让用户选。响应 `{ reportId, typeCode, typeName, name, reportDate, mappings: [{ mappingId, code, name, phase, targetType, targetFormCode, targetFormName, blockedReason?, candidates: [{ linkRecordId, recordLabel, recordStatus, targetRecordId, targetVersion, categoryKey?, categoryLabel?, writes: [{ source: ITEM\|EXTRA, itemCode?, result?, fieldKey, fieldLabel, oldValue, newValue, conflict, error? }], conflictCount }] }] }`。映射取 `rpt_mapping`（V64，按报告单类型、只取 ENABLED）：基线映射（BASELINE + phase）候选 = 目标分类（按映射字段所属分类推导，A 为「超声检查」）的 `pt_baseline_section`（未建分节 `targetRecordId=null`、`targetVersion=0`，术前 / 出院前两条映射并列由用户选时相）；随访映射候选 = 位置中该映射表单的 `fu_record`（整条或分组都算，多条逐一列出选一条）。`itemCode=REPORT_DATE` 的行 newValue = 报告时间；值按目标字段类型转换（数值 / 日期 / 选项名→编码 /「长×宽」pair），转换失败该行带 `error`（确认该键 40001）；附带写入（映射级「复查超声=是」、项目级「有无…返流=有」）以 `source=EXTRA` 一并列出。`conflict=true` = 原值非空且与新值不同（冲突提示，须逐项确认覆盖）。`blockedReason`：报告时间晚于手术 / 出院日期（时相边界，V64 remark 由写入侧校验）、基线已锁定（A-PAT-006）、映射配置问题。位置中无映射目标 → mappings 为空（前端隐藏映射步骤）；越范围 / 不存在 / 已删报告单 40400 |
| POST | `/api/hospital/followup/reports/{id}/mapping/confirm` | `followup:scan:upload` | **已实现（E16-T07）**：`{ mappingId, linkRecordId?, targetVersion, fieldKeys[] }`——`fieldKeys` 为确认写入的键（**原值不同的须逐项确认覆盖**：预览 `conflict=true` 的键须用户勾选后进 fieldKeys，未勾选的跳过并在响应 `skippedConflicts` 回告，不写、保留原值）；只写 fieldKeys 内的键，其余目标记录值不动（参数只存映射目标记录，报告单不另存一份，A-ATT-013 / C-SCAN-005）。同一事务写 `fe_field_history`（`source=REPORT`，原因「报告单映射写入：类型 · 名称（报告单 id）」）与 `RPT_MAPPING_WRITE` 审计；目标记录 version + 1（随访记录状态不变——录入即写入）。响应 `{ mappingId, targetType, targetRecordId, targetVersion, writtenFields, skippedConflicts, fieldChanges }`。目标记录版本过期 40900（重新预览，AGENTS §5.10 乐观锁）；基线锁定 40901（需先解锁）；时相越界 / fieldKeys 为空或不在写入清单 / 值转换失败 / 映射停用或不属于该类型 / 目标不在位置中 40001；越范围报告单 40400 |
| POST | `/api/hospital/followup/reports/{id}/digitize-status` | `followup:scan:upload` | C：`{ status: PENDING|DONE|NO_ENTRY }`；转录提交后自动变“已电子化” |

映射写入后录入侧的对比标红（A-FU-027，E17）按目标记录现值计算，天然生效；基线写入经 `BaselineSectionService.applyMappingWrite`（锁定校验 + 写入闸门 + 完整度重算 A-PAT-019），随访写入经 `FollowupRecordService.applyMappingWrite`（写入闸门 + 乐观锁）。 |

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

表：`rpt_type` / `rpt_name` / `rpt_indicator_tpl` / `rpt_indicator_item`（followup V62，A 医院预置 10 类随包）；`rpt_mapping` / `rpt_mapping_item`（followup V64，E23-T03 / A-ATT-013：报告单 → 结构化字段映射，A 预置心脏超声三条）六张配置表已进配置模板注册表（E12 导出 / 导入，tpl_id / indicator_tpl_id / mapping_id 数值引用导入时重映射）。**已实现（E16-T01）**：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/followup/report-config/types` | 类型清单（含停用，按 sort_no）：`{ id, code, name, formCode, formName, nameRequired, defaultDigitizeStatus, sortNo, status, remark, nameCount }`；编码本院唯一、创建后不可改（A-ATT-003「可增减、停用、排序」） |
| POST | 同上 `/types` | 新建：`{ code, name, formCode, nameRequired?, defaultDigitizeStatus?, sortNo?, remark? }`；重复编码 40902、`formCode` 必须是 `fe_form.usage_type = REPORT` 的表单否则 40001；默认电子化状态只收 `PENDING` / `NO_ENTRY`（A 为 null，A-ATT-018） |
| PUT | `/types/{id}` | 编辑（编码不可改）：`{ name, formCode, nameRequired?, defaultDigitizeStatus?, sortNo, status, remark? }`；停用（DISABLED）后添加报告单不再可选，不删除 |
| DELETE | `/types/{id}` | 类型下仍有名称（含停用）→ 40901「先删除名称」；已被报告单引用 → 40901「可停用不可删」**（已实现，E16-T02 / OQ-95②）** |
| GET | `.../names?typeCode=` | 名称清单（typeCode 可空 = 全部，含停用）：`{ id, typeCode, typeName, name, indicatorTplId, indicatorTplName, digitizeStatusOverride, sortNo, status }` |
| POST | 同上 `/names` | 新建：`{ typeCode, name, indicatorTplId?, digitizeStatusOverride?, sortNo? }`；同类型重名 40902（同名报告单可多次添加、按报告时间区分——字典只管取值，A-ATT-011）；类型不存在 40400、模板不存在 40001 |
| PUT | `/names/{id}` | 编辑（所属类型不可改）：`{ name, indicatorTplId?, digitizeStatusOverride?, sortNo, status }` |
| DELETE | `/names/{id}` | 删除名称；已被报告单引用 → 40901「可停用不可删」（**已实现，E16-T02 / OQ-95②**；已存报告单带名称快照显示，不随字典联动） |
| GET | `.../indicator-templates` | 模板清单（含停用，带全部项目与 `boundNameCount`）：`{ id, name, status, remark, items: [{ itemName, itemCode, unit, refRange, refRangeMale, refRangeFemale, sortNo }] }`（参考范围可按性别区分，A-ATT-012 / C-SCAN-004） |
| POST | 同上 `/indicator-templates` | 新建：`{ name, remark?, items[≥1] }`；名称本院唯一 40902、项目代码模板内唯一 40001 |
| PUT | `/indicator-templates/{id}` | 编辑：`{ name, remark?, status, items }`——项目**全量替换**（先清后插）；修改只影响之后新建的报告单，已保存指标行不变 |
| DELETE | `/indicator-templates/{id}` | 仍被名称绑定（含停用名称）→ 40901「先解绑」 |

增删改全部写审计（module=followup，objectType=`REPORT_TYPE` / `REPORT_NAME` / `REPORT_INDICATOR_TPL`，action=`RPT_TYPE_CREATE` 等 9 个；被拦截的删除写 DENIED）。

**字段映射（E23-T03 / A-ATT-013 已实现读取）**：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/followup/report-config/mappings` | 映射清单（含停用，按来源类型与排序号）：`{ id, code, name, typeCode, typeName, targetType: BASELINE\|FOLLOWUP_RECORD\|IMPLANT_RECORD, targetFormCode, targetFormName, phase: PREOP_LAST\|PREDISCHARGE_LAST\|null, indicatorTplId, indicatorTplName, extraWrites: [{fieldKey, value}], sortNo, status, remark, items: [{ itemCode, targetFieldKey, targetFieldLabel, extraWrites, sortNo }] }`；`itemCode` 为指标模板项目代码或固定 `REPORT_DATE`（报告时间，如随访目标「超声检查时间 = 报告时间」）；A 预置 `ECHO_PREOP_LAST` / `ECHO_PREDISCHARGE_LAST` / `ECHO_FOLLOWUP` 三条（迁移 V64），V64 同时把心脏超声名称绑定「心脏超声指标」模板（经 `report-names` 带出 18 个项目）；C 程控报告单映射同一机制（E23-T21） |

映射写入（`POST /reports/{id}/mapping/preview|confirm`）已随 E16-T07 实现（见 §3）；映射维护（增删改，审计 `RPT_MAPPING_*`）暂未排期——A 预置三条随 V64 发布，改动经数据库变更（配置表在配置模板注册表内，E12 导出 / 导入已支持）。`.../file-policy` 不单独设口——格式与大小是平台参数 `file.upload.*`（平台库 V33）+ 租户覆盖，机构设置 → 租户参数维护（E10-T01 已实现 `GET /api/hospital/files/policy` 读取生效值）。

## 7. 测试要点

- 无位置创建 → 40001（「请至少选择 1 个位置」）；移除最后一个位置 → 进入未指定位置列表（E16-T13）。
- 三种粒度位置各落一行；勾分组不自动勾整条（前端 check-strictly，后端按四元组去重）。
- 预览 / 下载各写一条审计；无 `scan:download` 时 download-url → 40300。
- 越范围患者的报告单在全院列表不出现，按 id 访问 40400；重复文件提示不泄露范围外患者。
- 伪造扩展名的 exe → 被拒；超大文件 → 40001。
- H5 token：过期、重复使用、换患者均失败。
- 批量上传（E16-T03）：MERGE 超过每份上限（demoa 10 个）→ 40001「附件数量超过上限（每份报告单最多 10 个）」且不落库；ONE_PER_FILE n 个文件 → n 份报告单、逐份调整名称 / 报告时间生效；> 50 个文件 40001；空 fileIds / 未知 mode 40001；items 引用清单外文件 40001；每份 RPT_CREATE + 一条 RPT_BATCH_CREATE；无 `scan:upload` 40300。
- 映射写入（E16-T07）：预览 conflict 字段未进 fieldKeys → 跳过并回告 `skippedConflicts`、原值保留；确认后 `fe_field_history` 有 `source=REPORT` 行（旧值 → 新值）且 `aud_log` 有 `RPT_MAPPING_WRITE`；目标版本过期 40900、基线锁定 40901、报告时间晚于手术 / 出院日期 40001；位置中无映射目标 → mappings 空；无映射类型（检验）→ mappings 空；无 `scan:upload` 40300、跨租户 40400。
