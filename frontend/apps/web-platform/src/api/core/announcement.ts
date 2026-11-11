import type {
  PlatformAnnouncementPage,
  PlatformAnnouncementPublishRequest,
  PlatformAnnouncementView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E05-T11 / PLT-CFG-009：系统公告接口（docs/api/platform.md §7，权限 platform:notice:edit）。
 * 发布即生效：ALL=全部租户、TENANTS=指定租户（未知租户 40400）；
 * 发布后不可编辑，更正走撤下 + 重新发布；发布与撤下写平台审计。
 */

/** 公告分页（新→旧；keyword 按标题模糊）。 */
export function listAnnouncements(query: {
  keyword?: string;
  pageNo?: number;
  pageSize?: number;
}) {
  const params: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value;
    }
  }
  return requestClient.get<PlatformAnnouncementPage>(
    apiPath('/announcements'),
    {
      params,
    },
  );
}

/** 公告详情（含定向租户名单）。 */
export function announcementDetail(id: number) {
  return requestClient.get<PlatformAnnouncementView>(
    apiPath(`/announcements/${id}`),
  );
}

/** 发布公告（发布即生效）。 */
export function publishAnnouncement(data: PlatformAnnouncementPublishRequest) {
  return requestClient.post<PlatformAnnouncementView>(
    apiPath('/announcements'),
    data,
  );
}

/** 撤下公告（医院端即刻不可见；已撤下 40901）。 */
export function revokeAnnouncement(id: number) {
  return requestClient.put<PlatformAnnouncementView>(
    apiPath(`/announcements/${id}/revoke`),
  );
}
