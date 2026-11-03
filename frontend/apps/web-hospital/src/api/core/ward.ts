/**
 * E13-T08 / A-WARD-001、A-WARD-008：病区 / 中心字典维护（docs/api/system.md §1，
 * 权限 followup:ward:edit，页面 /system/ward）。编码本院唯一且创建后不可改；
 * 病区下仍有在册患者时不能停用（40901，须先转出）；增改停由后端写审计。
 */
import type {
  WardCreateRequest,
  WardMembersView,
  WardStatusRequest,
  WardUpdateRequest,
  WardView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 病区列表（含停用，按排序号升序；行含在册患者数与成员数）。 */
export function fetchWards() {
  return requestClient.get<{ items: WardView[] }>(apiPath('/followup/wards'));
}

/** 新建病区（重复编码 40902）。 */
export function createWard(payload: WardCreateRequest) {
  return requestClient.post<WardView>(apiPath('/followup/wards'), payload);
}

/** 编辑病区（编码不可改；前后值写审计）。 */
export function updateWard(id: number | string, payload: WardUpdateRequest) {
  return requestClient.put<WardView>(apiPath(`/followup/wards/${id}`), payload);
}

/** 停用 / 启用（A-WARD-008：仍有在册患者时停用 40901）。 */
export function changeWardStatus(
  id: number | string,
  payload: WardStatusRequest,
) {
  return requestClient.put<WardView>(
    apiPath(`/followup/wards/${id}/status`),
    payload,
  );
}

/** 病区成员与患者数（负责病区 = 本病区的启用用户）。 */
export function fetchWardMembers(id: number | string) {
  return requestClient.get<WardMembersView>(
    apiPath(`/followup/wards/${id}/members`),
  );
}
