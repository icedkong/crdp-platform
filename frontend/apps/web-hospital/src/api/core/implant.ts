/**
 * E21-T01 / C-PAT-004、C-PAT-005：植入记录（一对多）与电极记录接口
 * （docs/api/implant.md §1；查询 followup:patient:view，写入 followup:implant:edit）。
 */
import type {
  ImplantListResult,
  ImplantOptions,
  ImplantPayload,
  ImplantRecordView,
  ImplantUpdatePayload,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 全部植入记录与电极（当前器械置顶）。 */
export function fetchPatientImplants(patientId: string) {
  return requestClient.get<ImplantListResult>(
    apiPath(`/followup/patients/${patientId}/implants`),
  );
}

/** 新建植入记录（新器械接替在用时旧记录自动结束）。 */
export function createPatientImplant(
  patientId: string,
  payload: ImplantPayload,
) {
  return requestClient.post<ImplantRecordView>(
    apiPath(`/followup/patients/${patientId}/implants`),
    payload,
  );
}

/** 编辑植入记录与电极（baseVersion 乐观锁，冲突 40900）。 */
export function updatePatientImplant(
  implantId: string,
  payload: ImplantUpdatePayload,
) {
  return requestClient.put<ImplantRecordView>(
    apiPath(`/followup/implants/${implantId}`),
    payload,
  );
}

/** 植入记录选项（暂行预置，业务字典值随 E21-T04）。 */
export function fetchImplantOptions() {
  return requestClient.get<ImplantOptions>(
    apiPath('/followup/implants/options'),
  );
}
