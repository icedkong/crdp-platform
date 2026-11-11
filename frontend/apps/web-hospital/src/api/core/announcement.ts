/**
 * E05-T11 / PLT-CFG-009：医院端系统公告接口（docs/api/platform.md §7）。
 * 登录即可（无权限码）；平台按范围（全部 / 指定租户）下发生效公告，本院只读。
 */
import type { HospitalAnnouncement } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 本院可见的生效公告（新→旧，上限 50 条；撤下后即刻不可见）。 */
export function fetchAnnouncements() {
  return requestClient.get<HospitalAnnouncement[]>(apiPath('/announcements'));
}
