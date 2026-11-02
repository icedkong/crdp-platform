/**
 * E09-T02 / A-NTF-002、C-NTF-002：顶栏站内信铃铛的纯逻辑（组件 notice-bell.vue 用）。
 * 接口契约见 `@crdp/api/notify`（docs/api/notify.md §1）。
 */
import type { NoticeMessage, NoticeRouteLocation } from '@crdp/api';

import { noticeTargetRoute } from '@crdp/api';

/**
 * 点击消息的跳转落点：按 target 换算路由；落点路由尚未注册（如 RECORD 的
 * `/followup/record/:recordId` 隐藏路由随 E15 菜单下发）或无 target 时返回 null，
 * 由组件提示「该消息对应的页面尚未开放」/ 不跳转。
 *
 * @param routeExists 由组件注入：router.resolve 后是否命中真实路由（不含 404 兜底）
 */
export function noticeJumpLocation(
  message: NoticeMessage,
  routeExists: (location: NoticeRouteLocation) => boolean,
): NoticeRouteLocation | null {
  const location = noticeTargetRoute(message.target);
  if (!location || !routeExists(location)) {
    return null;
  }
  return location;
}

/** 未读消息点击时先标已读（已读 / 他人消息不重复提交）。 */
export function shouldMarkRead(message: NoticeMessage): boolean {
  return !message.read;
}

/** 时间文案：当天只写时分，更早写「MM-DD HH:mm」（站内信只看相对近的待办）。 */
export function noticeTimeText(iso: string, now = new Date()): string {
  const time = new Date(iso);
  if (Number.isNaN(time.getTime())) {
    return '';
  }
  const pad = (value: number) => String(value).padStart(2, '0');
  const hm = `${pad(time.getHours())}:${pad(time.getMinutes())}`;
  const sameDay =
    time.getFullYear() === now.getFullYear() &&
    time.getMonth() === now.getMonth() &&
    time.getDate() === now.getDate();
  if (sameDay) {
    return hm;
  }
  return `${pad(time.getMonth() + 1)}-${pad(time.getDate())} ${hm}`;
}
