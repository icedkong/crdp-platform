/**
 * E07-T05 / A-PAT-010、C-PAT-012：患者敏感字段明文查看（docs/api/patient.md §5）。
 * 权限 followup:patient:plain（按钮入口由 usePermission 隐藏，后端仍校验）；
 * 每次调用写数据访问审计（后端含字段码与场景，不记明文）；明文只保存在组件内存，
 * 60 秒自动恢复脱敏——不写 localStorage / IndexedDB / URL / 日志。
 *
 * E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002：出院登记与病案号查重
 * （docs/api/patient.md §2，权限 followup:patient:create）。查重纯查询、登记写
 * PATIENT_CREATE 审计；重复返回 40902，页面按 duplicateNoticeOf 组装提示并阻止提交。
 *
 * E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009：患者列表检索与筛选（docs/api/patient.md §1，
 * 权限 followup:patient:view）。病区隔离由后端数据范围承担；姓名 / 病案号为盲索引精确匹配；
 * 检索词只放页面状态，不写 URL（components.md §11.1）；批量查询由后端写数据访问审计。
 *
 * E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010：档案概览与患者时间轴（docs/api/patient.md
 * §2 / §7，权限 followup:patient:view）。姓名 / 编号只含脱敏值；越范围患者 40400，
 * 页面按「患者不存在或无权查看」处理；联系方式与变更记录（A-PAT-004「从概览进入」）
 * 走 E13-T03 的 contact-info / status-history。
 */
import type {
  PatientContactInfo,
  PatientCreatedResult,
  PatientDuplicateCheckResult,
  PatientFilterOptions,
  PatientOverview,
  PatientPlaintextResult,
  PatientRegistrationOptions,
  PatientRegistrationPayload,
  PatientSearchPage,
  PatientSearchPayload,
  PatientStatusHistoryRow,
  PatientTimeline,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 检索请求体（body 由 buildPatientSearchPayload 组装：分页 + 检索词 + 简单筛选）。 */
export function searchPatients(payload: PatientSearchPayload) {
  return requestClient.post<PatientSearchPage>(
    apiPath('/followup/patients/search'),
    payload,
  );
}

/** 筛选候选（病区下拉，启用项）。 */
export function fetchPatientFilterOptions() {
  return requestClient.get<PatientFilterOptions>(
    apiPath('/followup/patients/filter-options'),
  );
}

/** 查看一名患者的敏感字段明文（body 由 buildPlaintextRevealRequest 组装）。 */
export function revealPatientPlaintext(
  patientId: number | string,
  fields: string[],
  scene: string,
) {
  return requestClient.post<PatientPlaintextResult>(
    apiPath(`/followup/patients/${patientId}/plaintext`),
    { fields, scene },
  );
}

/** 病案号查重（新建页实时提示；body 由 buildDuplicateCheckPayload 组装）。 */
export function checkPatientDuplicate(payload: {
  birthDate?: string;
  medicalRecordNo: string;
  name?: string;
}) {
  return requestClient.post<PatientDuplicateCheckResult>(
    apiPath('/followup/patients/check-duplicate'),
    payload,
  );
}

/** 出院登记（A-PAT-003）/ 患者登记（C-PAT-001）；重复 40902。 */
export function createPatientRegistration(payload: PatientRegistrationPayload) {
  return requestClient.post<PatientCreatedResult>(
    apiPath('/followup/patients'),
    payload,
  );
}

/** 登记候选（病区 / 科室 / 性别 / 出院状态）。 */
export function fetchPatientRegistrationOptions() {
  return requestClient.get<PatientRegistrationOptions>(
    apiPath('/followup/patients/registration-options'),
  );
}

/** 患者概览（PatientHeader 数据）：越范围 / 不存在 40400。 */
export function fetchPatientSummary(patientId: number | string) {
  return requestClient.get<PatientOverview>(
    apiPath(`/followup/patients/${patientId}/summary`),
  );
}

/** 患者时间轴（A-PAT-013）：出院节点 + 状态变更事件，按时间顺序。 */
export function fetchPatientTimeline(patientId: number | string) {
  return requestClient.get<PatientTimeline>(
    apiPath(`/followup/patients/${patientId}/followup-timeline`),
  );
}

/** 联系方式版本（E13-T03：换号保留历史，电话脱敏，明文走 plaintext）。 */
export function fetchPatientContactInfo(patientId: number | string) {
  return requestClient.get<PatientContactInfo>(
    apiPath(`/followup/patients/${patientId}/contact-info`),
  );
}

/** 状态变更历史（E13-T03：只追加，登记初始行 ACTIVE）。 */
export function fetchPatientStatusHistory(patientId: number | string) {
  return requestClient.get<PatientStatusHistoryRow[]>(
    apiPath(`/followup/patients/${patientId}/status-history`),
  );
}
