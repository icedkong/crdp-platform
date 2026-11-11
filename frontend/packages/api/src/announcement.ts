/**
 * E05-T11 / PLT-CFG-009：系统公告的类型与文案
 * （接口契约 docs/api/platform.md §7，后端 `com.crdp.server.web`）。
 *
 * 平台向全部或指定租户发布公告：scope=ALL 面向全部租户（tenants 恒空）、
 * scope=TENANTS 只面向登记租户；发布即生效（PUBLISHED）、撤下后医院端即刻不可见（REVOKED）。
 */

/** 发布范围：ALL 全部租户 / TENANTS 指定租户。 */
export type AnnouncementScope = 'ALL' | 'TENANTS';

/** 公告状态：PUBLISHED 发布中 / REVOKED 已撤下。 */
export type AnnouncementStatus = 'PUBLISHED' | 'REVOKED';

/** 定向租户引用（scope=TENANTS 的名单项）。 */
export interface AnnouncementTenantRef {
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}

/** 平台端公告视图（列表与详情同构）。 */
export interface PlatformAnnouncementView {
  content: string;
  id: number;
  publishedAt: string;
  publishedByName: null | string;
  revokedAt: null | string;
  scope: AnnouncementScope;
  scopeText: string;
  status: AnnouncementStatus;
  tenants: AnnouncementTenantRef[];
  title: string;
}

/** 公告分页（新→旧，统一分页口径）。 */
export interface PlatformAnnouncementPage {
  items: PlatformAnnouncementView[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 发布请求：scope=ALL 不带 tenantIds。 */
export interface PlatformAnnouncementPublishRequest {
  content: string;
  scope: AnnouncementScope;
  tenantIds?: null | number[];
  title: string;
}

/** 医院端公告（本院可见的生效公告，登录即可读）。 */
export interface HospitalAnnouncement {
  content: string;
  id: number;
  publishedAt: string;
  title: string;
}

/** 公告状态文案（平台端列表）。 */
export function announcementStatusText(
  status: AnnouncementStatus | null | undefined,
): string {
  return status === 'REVOKED' ? '已撤下' : '发布中';
}

/**
 * 医院端公告铃铛的角标数：最近 windowMs 内发布的公告条数（无已读跟踪，
 * 用「近 7 天有新公告」提示用户查看；超出 99 由角标组件自截断）。
 */
export function recentAnnouncementCount(
  announcements: HospitalAnnouncement[],
  now: number,
  windowMs: number,
): number {
  return announcements.filter((item) => {
    const publishedAt = Date.parse(item.publishedAt);
    return Number.isFinite(publishedAt) && now - publishedAt < windowMs;
  }).length;
}
