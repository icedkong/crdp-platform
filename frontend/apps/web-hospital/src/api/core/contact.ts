/**
 * E15-T04 / A-FU-006、A-FU-025、C-FU-019：沟通记录接口（docs/api/followup.md §6，
 * 后端 ContactLogController；页面 pages.md §4.8 沟通记录页签）。
 *
 * 权限：列表 followup:patient:view（菜单 21）；记录 / 修改 / 删除 followup:record:edit
 * （按钮 222）。数据范围由后端出行（越范围患者 40400，不暴露存在）；录音的播放与下载
 * 走文件服务短时链接（E10-T04 / T05），预览每次签发写审计。
 */
import type {
  ContactLogCreateRequest,
  ContactLogCreateResult,
  ContactLogPage,
  ContactLogRow,
  ContactLogUpdateRequest,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 患者沟通记录分页（倒序，最近在前；行含录音展示信息与 inlinePlayable）。 */
export function fetchPatientContacts(
  patientId: string,
  params?: { pageNo?: number; pageSize?: number },
) {
  return requestClient.get<ContactLogPage>(
    apiPath(`/followup/patients/${patientId}/contacts`),
    { params },
  );
}

/**
 * 记录一次联系（A-FU-006）：结果 = DECEASED 时后端同事务触发死亡终止，
 * 返回 terminatedTasks；第一次联系任务进入进行中。
 */
export function createContactLog(
  patientId: string,
  body: ContactLogCreateRequest,
) {
  return requestClient.post<ContactLogCreateResult>(
    apiPath(`/followup/patients/${patientId}/contacts`),
    body,
  );
}

/** 修改沟通记录（乐观锁 baseVersion，冲突 40900；结果改为「告知已故」同样触发死亡终止）。 */
export function updateContactLog(
  contactId: string,
  body: ContactLogUpdateRequest,
) {
  return requestClient.put<ContactLogRow>(
    apiPath(`/followup/contacts/${contactId}`),
    body,
  );
}

/** 删除沟通记录（连带软删录音：两处同时删除，A-ATT-017；确认弹窗要写明）。 */
export function deleteContactLog(contactId: string) {
  return requestClient.delete(apiPath(`/followup/contacts/${contactId}`));
}
