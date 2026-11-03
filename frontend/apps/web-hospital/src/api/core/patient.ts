/**
 * E07-T05 / A-PAT-010、C-PAT-012：患者敏感字段明文查看（docs/api/patient.md §5）。
 * 权限 followup:patient:plain（按钮入口由 usePermission 隐藏，后端仍校验）；
 * 每次调用写数据访问审计（后端含字段码与场景，不记明文）；明文只保存在组件内存，
 * 60 秒自动恢复脱敏——不写 localStorage / IndexedDB / URL / 日志。
 *
 * E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002：出院登记与病案号查重
 * （docs/api/patient.md §2，权限 followup:patient:create）。查重纯查询、登记写
 * PATIENT_CREATE 审计；重复返回 40902，页面按 duplicateNoticeOf 组装提示并阻止提交。
 */
import type {
  PatientCreatedResult,
  PatientDuplicateCheckResult,
  PatientPlaintextResult,
  PatientRegistrationOptions,
  PatientRegistrationPayload,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

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
