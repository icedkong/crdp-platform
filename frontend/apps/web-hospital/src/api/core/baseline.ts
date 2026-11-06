/**
 * E13-T02 / A-PAT-003、A-PAT-005、A-PAT-006：基线分节、基线锁定与解锁
 * （docs/api/patient.md §4，权限：查看 followup:patient:view、保存 / 暂存
 * followup:patient:edit、锁定 / 解锁 followup:patient:lock）。
 *
 * - 基线全量视图（分节 + 进度 + 锁定态）越范围 / 不存在 40400，页面按
 *   「患者不存在或无权查看」处理；
 * - 暂存与保存带 baseVersion（乐观锁）：40900 经 form-engine
 *   asDraftConflictDetail 转 ConflictBanner 二选一（保留我的并覆盖 / 载入服务器版本）；
 * - 锁定后保存 / 暂存 40901（锁定后只读）；解锁必须填写原因（ReasonModal）。
 */
import type {
  BaselineDraftPayload,
  BaselineDraftSavedView,
  BaselineFieldHistoryRow,
  BaselineLockInfo,
  BaselineLockPayload,
  BaselineSectionSavePayload,
  BaselineSectionSaveResult,
  PatientBaseline,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 基线全量视图（分节 + 分类进度 + 锁定状态）。 */
export function fetchPatientBaseline(patientId: string) {
  return requestClient.get<PatientBaseline>(
    apiPath(`/followup/patients/${patientId}/baseline`),
  );
}

/** 自动保存草稿（只在有新改动时发送；40900 转冲突流程）。 */
export function saveBaselineSectionDraft(
  patientId: string,
  categoryKey: string,
  payload: BaselineDraftPayload,
) {
  return requestClient.put<BaselineDraftSavedView>(
    apiPath(`/followup/baseline/${patientId}/sections/${categoryKey}/draft`),
    payload,
  );
}

/** 正式保存分节（完整校验 42200；写字段级历史并清草稿；锁定后 40901）。 */
export function saveBaselineSection(
  patientId: string,
  categoryKey: string,
  payload: BaselineSectionSavePayload,
) {
  return requestClient.put<BaselineSectionSaveResult>(
    apiPath(`/followup/baseline/${patientId}/sections/${categoryKey}`),
    payload,
  );
}

/** 分节字段级历史（A-PAT-005：旧值 → 新值、人、时间；fieldKey 缺省查全部）。 */
export function fetchBaselineSectionHistory(
  patientId: string,
  categoryKey: string,
  fieldKey?: string,
) {
  const query = fieldKey ? `?fieldKey=${encodeURIComponent(fieldKey)}` : '';
  return requestClient.get<BaselineFieldHistoryRow[]>(
    apiPath(
      `/followup/baseline/${patientId}/sections/${categoryKey}/history${query}`,
    ),
  );
}

/** 锁定 / 解锁基线（A-PAT-006：解锁原因必填；权限 followup:patient:lock）。 */
export function setBaselineLock(
  patientId: string,
  payload: BaselineLockPayload,
) {
  return requestClient.post<BaselineLockInfo>(
    apiPath(`/followup/patients/${patientId}/lock`),
    payload,
  );
}
