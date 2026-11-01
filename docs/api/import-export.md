# 导入与导出（import-export）

> 后端：`crdp-followup`（导入导出作业，异步）+ 平台审批。表：`job_import`、`job_import_error`、`job_export`、`job_export_approval`。
> 页面：`/followup/patient/import`（导入向导）、患者列表「导出」、审计中心导出。
> 全部路径 **推断，待确认**。需求：A-PAT-008、A-PAT-009、A-PAT-018（开关 `wide_import`）、A-WARD-006、C-PAT-014、PLT-ORG-003、PLT-ORG-007、PLT-AUD-003、PLT-AUD-007。

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
- 病区按导入文件中的病区列或向导中选择的病区（A-WARD-006）；导入人必须有该病区范围。
- 导入写审计（作业级 + 行级摘要）；上传的原始文件按租户加密存储，保留期限见 OQ-28。
- **测试用例只能用虚构数据**；仓库里不得出现医院提供的样例文件。

## 2. 组织 / 用户导入（PLT-ORG-003、PLT-ORG-007，P1）

**科室导入已实现（E06-T03）**：`GET /api/hospital/system/orgs/import-template` + `POST /api/hospital/system/orgs/import`（`tenant:org:edit`）——**同步一次性模型**（OQ-52），上传即逐行校验并落库、单行失败不影响其他行、返回逐行结果；详见 `system.md` §1。§1 的异步作业模型（预检 / 确认 / 结果文件）保留给患者宽表导入（E15+/E18）。用户导入（PLT-ORG-007）仍为 `POST /api/hospital/system/users/import`（E06-T08），届时用户侧也采用同步模型对齐。

## 3. 导出（A-PAT-009、C-PAT-014、PLT-AUD-007）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/followup/exports` | `followup:patient:export` | `{ scope: PATIENT|BASELINE|FOLLOWUP|IMPLANT, condition（与列表相同的条件树）, valueMode: CODE|LABEL, fields[], reason }` → `{ exportId, status }` |
| GET | `/api/hospital/followup/exports?mine=true` | 登录 | 我的导出 |
| GET | `/api/hospital/system/export-approvals?status=PENDING` | 租户管理员 | 待审批列表 |
| POST | `/api/hospital/system/export-approvals/{id}/approve` · `/reject` | 租户管理员 | `{ comment }` |
| POST | `/api/hospital/followup/exports/{exportId}/download-url` | 导出申请人 | 审批通过且生成完成后的 5 分钟签名链接；写数据访问审计 |

- 审批：租户管理员本人导出无需审批；普通用户由租户管理员审批；平台账号导出由平台管理员审批（PLT-AUD-007）。
- 导出内容按导出人的数据范围与字段策略：`HIDDEN` 字段不导出，`MASKED` 字段导出脱敏值（是否允许导出明文见 OQ-20）。
- 文件加水印（导出人、医院、时间），有效期 7 天（推断）。

## 4. 测试要点

- 同租户第二个导入作业 → 40901；不同租户可并行。
- 预检发现错误时 confirm → 40001。
- 普通用户导出未审批时 download-url → 40901；审批后可下载，审计有记录。
- 越范围患者不进入导出文件。
