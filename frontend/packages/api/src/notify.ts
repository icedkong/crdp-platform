/**
 * E09-T02 / A-NTF-002、C-NTF-002：站内信收件箱的类型与跳转路由
 * （接口契约 docs/api/notify.md §1，后端 `com.crdp.followup.notice.api`）。
 *
 * 患者姓名由后端按接收人当前字段策略脱敏（MASKED 掩码 / HIDDEN 整字段不出现），
 * 前端只展示、不缓存明文；点击消息按 target 跳到对应任务 / 患者 / 录入页，
 * 落点接口在服务端按数据范围再校验（越范围 40400）。
 */

/** 点击跳转的落点类型（后端 NoticeInboxService.TargetKind）。 */
export type NoticeTargetType = 'PATIENT' | 'RECORD' | 'TASK';

/** 跳转落点；无落点的消息（如每日汇总）target 为 undefined。 */
export interface NoticeTarget {
  id: number;
  type: NoticeTargetType;
}

/** 一条站内信（notify.md §1）；patientName 可能因 HIDDEN 策略整字段不出现。 */
export interface NoticeMessage {
  createdAt: string;
  eventType: string;
  id: number;
  patientId?: null | number;
  patientName?: null | string;
  read: boolean;
  summary?: null | string;
  target?: NoticeTarget | null;
  title: string;
}

/** 收件箱一页（统一分页口径）。 */
export interface NoticeMessagePage {
  items: NoticeMessage[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 未读数响应。 */
export interface NoticeUnreadCount {
  unread: number;
}

/** 全部已读响应。 */
export interface NoticeMarkedCount {
  marked: number;
}

/** 路由落点（vue-router 的 path/query 子集，避免在本包依赖 router 类型）。 */
export interface NoticeRouteLocation {
  path: string;
  query?: Record<string, string>;
}

/**
 * 站内信 target → 页面路由（pages.md §7 / §3 / §5）：
 * TASK → 我的任务（带 taskId）；PATIENT → 患者档案（带 patientId）；
 * RECORD → 随访录入 `/followup/record/:recordId`（该隐藏路由随 E15 菜单下发，
 * 未注册时由调用方回落提示）。无 target / 类型未知 / id 非法 → null（不跳转）。
 */
export function noticeTargetRoute(
  target: NoticeTarget | null | undefined,
): NoticeRouteLocation | null {
  if (!target || typeof target.id !== 'number' || !Number.isFinite(target.id)) {
    return null;
  }
  switch (target.type) {
    case 'PATIENT': {
      return {
        path: '/followup/patient',
        query: { patientId: String(target.id) },
      };
    }
    case 'RECORD': {
      return { path: `/followup/record/${target.id}` };
    }
    case 'TASK': {
      return { path: '/followup/task', query: { taskId: String(target.id) } };
    }
    default: {
      return null;
    }
  }
}
