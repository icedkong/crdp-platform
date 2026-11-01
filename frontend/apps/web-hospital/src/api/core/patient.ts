/**
 * E07-T05 / A-PAT-010、C-PAT-012：患者敏感字段明文查看（docs/api/patient.md §5）。
 * 权限 followup:patient:plain（按钮入口由 usePermission 隐藏，后端仍校验）；
 * 每次调用写数据访问审计（后端含字段码与场景，不记明文）；明文只保存在组件内存，
 * 60 秒自动恢复脱敏——不写 localStorage / IndexedDB / URL / 日志。
 */
import type { PatientPlaintextResult } from '@crdp/api';

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
