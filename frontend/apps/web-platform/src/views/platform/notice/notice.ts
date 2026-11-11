import type {
  AnnouncementScope,
  PlatformAnnouncementView,
  TenantListItem,
} from '@crdp/api';

/**
 * E05-T11 / PLT-CFG-009 · 平台端「系统公告」页逻辑（纯函数，视图组装见 index.vue）。
 * 校验口径与后端 PlatformAnnouncementService 一致：标题 ≤128、正文 ≤5000、
 * ALL=全部租户（不带指定租户）、TENANTS=指定租户（至少 1 个，未知租户由后端 40400）。
 */

/** 标题上限（与后端 TITLE_MAX 同口径）。 */
export const TITLE_MAX = 128;

/** 正文上限（与后端 CONTENT_MAX 同口径）。 */
export const CONTENT_MAX = 5000;

/** 指定租户一次最多 50 个（与后端 TENANT_LIST_MAX 同口径）。 */
export const TENANT_LIST_MAX = 50;

/** 发布范围单选项。 */
export const SCOPE_OPTIONS: Array<{ label: string; value: AnnouncementScope }> =
  [
    { label: '全部租户', value: 'ALL' },
    { label: '指定租户', value: 'TENANTS' },
  ];

/** 发布表单模型。 */
export interface AnnouncementFormModel {
  content: string;
  scope: AnnouncementScope;
  tenantIds: number[];
  title: string;
}

/** 发布表单空模型（默认面向全部租户）。 */
export function emptyFormModel(): AnnouncementFormModel {
  return { content: '', scope: 'ALL', tenantIds: [], title: '' };
}

/** 发布表单校验：返回错误消息数组（空数组 = 通过）。 */
export function validateFormModel(model: AnnouncementFormModel): string[] {
  const errors: string[] = [];
  const title = model.title.trim();
  if (!title) {
    errors.push('标题不能为空');
  } else if (title.length > TITLE_MAX) {
    errors.push(`标题不能超过 ${TITLE_MAX} 个字符`);
  }
  const content = model.content.trim();
  if (!content) {
    errors.push('正文不能为空');
  } else if (content.length > CONTENT_MAX) {
    errors.push(`正文不能超过 ${CONTENT_MAX} 个字符`);
  }
  if (model.scope === 'TENANTS') {
    if (model.tenantIds.length === 0) {
      errors.push('指定租户不能为空');
    }
    if (model.tenantIds.length > TENANT_LIST_MAX) {
      errors.push(`指定租户一次最多 ${TENANT_LIST_MAX} 个`);
    }
  }
  return errors;
}

/** 租户下拉选项（label = 医院名称（编码），P2 全量拉取不过滤状态——后端只按存在性校验）。 */
export function tenantOptions(
  tenants: TenantListItem[],
): Array<{ label: string; value: number }> {
  return tenants.map((tenant) => ({
    label: `${tenant.name}（${tenant.code}）`,
    value: tenant.id,
  }));
}

/**
 * 列表「指定租户」列文案：全名展示，超过 3 个显示前 3 个 + 「等 n 家」。
 */
export function tenantNamesText(row: PlatformAnnouncementView): string {
  if (row.scope === 'ALL') {
    return '全部租户';
  }
  const names = row.tenants.map((tenant) => tenant.tenantName);
  if (names.length === 0) {
    return '—';
  }
  return names.length > 3
    ? `${names.slice(0, 3).join('、')} 等 ${names.length} 家`
    : names.join('、');
}

/** 发布 / 撤下时间展示（ISO-8601 带时区 → 本地 `YYYY-MM-DD HH:mm`；非法值返回原串）。 */
export function announcementTimeText(iso: null | string): string {
  if (!iso) {
    return '—';
  }
  const time = new Date(iso);
  if (Number.isNaN(time.getTime())) {
    return iso;
  }
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(
    time.getDate(),
  )} ${pad(time.getHours())}:${pad(time.getMinutes())}`;
}
