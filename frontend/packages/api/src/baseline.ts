/**
 * E13-T02 / A-PAT-003、A-PAT-005、A-PAT-006：基线分节、基线锁定与解锁的类型与纯逻辑
 * （接口契约 docs/api/patient.md §4，后端 `com.crdp.followup.patient.api`）。
 *
 * - 分节：出院登记的最小必填集在患者主档（E13-T01），其余分类按模板分节补录
 *   （可分次：先暂存草稿、后正式保存；正式保存执行完整校验并写字段级历史）；
 * - 锁定（A-PAT-006）：锁定后只读（保存 / 暂存一律 40901），「编辑」置灰并提示；
 *   解锁需专门权限 `followup:patient:lock` 并填写原因（ReasonModal）；
 * - 冲突：baseVersion 过期 40900（form-engine `asDraftConflictError` 同一契约，
 *   patient.md §4.1），「保留我的并覆盖」以 currentVersion 重发并带 overwrite。
 */

import type { FormSchema } from './form';

/** 分节完整度状态（后端 BaselineSectionService：EMPTY / PARTIAL / DONE，A-PAT-019 口径）。 */
export type BaselineSectionStatus = 'DONE' | 'EMPTY' | 'PARTIAL';

/** 一个分节（每名患者每个模板分类一行；未建行分类输出 version 0 / EMPTY / 空 data）。 */
export interface BaselineSection {
  code: string;
  data: Record<string, unknown>;
  hasDraft: boolean;
  id: null | string;
  meta: Record<string, unknown>;
  status: BaselineSectionStatus;
  updatedAt: null | string;
  updatedBy: null | string;
  version: number;
}

/** 分类进度（已填 / 应填必填，A-PAT-021 口径；分类条 / 分节标题共用）。 */
export interface BaselineCategoryProgress {
  filled: number;
  key: string;
  label: string;
  total: number;
}

/** 锁定状态（未锁定时 locked=false、其余为 null）。 */
export interface BaselineLockInfo {
  locked: boolean;
  lockedAt: null | string;
  lockedBy: null | string;
  lockReason: null | string;
  unlockedAt: null | string;
  unlockedBy: null | string;
  unlockReason: null | string;
}

/** GET /patients/{id}/baseline 响应（patient.md §4）。 */
export interface PatientBaseline {
  formCode: string;
  formVersion: number;
  lock: BaselineLockInfo;
  progress: BaselineCategoryProgress[];
  sections: BaselineSection[];
}

/** 暂存草稿请求（E25 契约：changes 只带变更字段，null = 清空）。 */
export interface BaselineDraftPayload {
  baseVersion: number;
  changes: Record<string, unknown>;
  overwrite?: boolean;
}

/** 暂存结果（表单顶部「已自动保存 HH:mm」）。 */
export interface BaselineDraftSavedView {
  savedAt: null | string;
  version: number;
}

/** 正式保存请求：data 全量、meta 缺省保留原值。 */
export interface BaselineSectionSavePayload {
  baseVersion: number;
  data: Record<string, unknown>;
  meta?: null | Record<string, unknown>;
}

/** 保存结果：保存后的分节 + 软校验警告（不拦截，A-FLD-003）。 */
export interface BaselineSectionSaveResult {
  section: BaselineSection;
  warnings: Array<{ key: string; message: string }>;
}

/** 锁定 / 解锁请求（A-PAT-006：解锁 reason 必填）。 */
export interface BaselineLockPayload {
  locked: boolean;
  reason?: null | string;
}

/** 字段级历史行（A-PAT-005：旧值、新值、人、时间；敏感字段值不落）。 */
export interface BaselineFieldHistoryRow {
  changedAt: string;
  fieldKey: string;
  id: string;
  newValue: unknown;
  oldValue: unknown;
  operatorId: null | string;
  operatorName: null | string;
  reason: null | string;
  source: string;
  sourceLabel: null | string;
}

/** 分节状态文案（pages.md §4.2：某分类无数据仍显示分类标题 +「未填写」）。 */
export function baselineSectionStatusText(
  status: BaselineSectionStatus,
): string {
  switch (status) {
    case 'DONE': {
      return '已填写完整';
    }
    case 'PARTIAL': {
      return '部分填写';
    }
    default: {
      return '未填写';
    }
  }
}

/** 分节标题的已填 / 应填（A-PAT-021 口径；无必填项的分类显示「无必填项」）。 */
export function baselineProgressText(filled: number, total: number): string {
  return total > 0 ? `已填 ${filled} / 应填 ${total}` : '无必填项';
}

/**
 * 锁定横幅（pages.md §5 横幅文案「基线已锁定（A-PAT-006）」；§4.2 附锁定人与说明）。
 * 未锁定返回 null（横幅不出现）。
 */
export function baselineLockBanner(
  lock: BaselineLockInfo | null,
): null | string {
  if (!lock?.locked) return null;
  const parts: string[] = [];
  if (lock.lockedBy) parts.push(`锁定人 ${lock.lockedBy}`);
  if (lock.lockReason) parts.push(`说明：${lock.lockReason}`);
  const detail = parts.length > 0 ? `（${parts.join('，')}）` : '';
  return `基线已锁定（A-PAT-006）${detail}`;
}

/** 「编辑」是否可进（pages.md §4.2：锁定置灰；无编辑权限隐藏）。 */
export function canEditBaseline(
  locked: boolean,
  hasEditPermission: boolean,
): boolean {
  return hasEditPermission && !locked;
}

/** 「编辑」置灰提示（pages.md §4.2 文案照抄）。 */
export const BASELINE_LOCKED_TOOLTIP =
  '基线已锁定：有解锁权限并填写原因后才进入编辑';

/** 「解锁」入口（pages.md §4.2：有 followup:patient:lock 且已锁定时出现 → ReasonModal）。 */
export function showBaselineUnlock(
  locked: boolean,
  hasLockPermission: boolean,
): boolean {
  return locked && hasLockPermission;
}

/** 「锁定」入口（A-PAT-006：病区医生确认无误后锁定；需 followup:patient:lock）。 */
export function showBaselineLock(
  locked: boolean,
  hasLockPermission: boolean,
): boolean {
  return !locked && hasLockPermission;
}

/** 解锁原因校验（A-PAT-006 必填；上限 255 字与后端一致）：合法返回 null，否则错误文案。 */
export function baselineReasonError(reason: string): null | string {
  const trimmed = reason.trim();
  if (!trimmed) return '请填写原因';
  if (trimmed.length > 255) return '原因不超过 255 字';
  return null;
}

/**
 * 基线本地草稿键（components.md §1.4 / design-system §13）：
 * `${tenantId}:${userId}:baseline:${patientId}:${categoryKey}` —— 键含租户、用户、
 * 患者 + 分类，与随访记录草稿（kind=followup）互不冲突。
 */
export function baselineDraftRecordKey(
  tenantCode: null | string | undefined,
  userId: null | number | string | undefined,
  patientId: string,
  categoryKey: string,
): string {
  return `${tenantCode ?? ''}:${userId ?? ''}:baseline:${patientId}:${categoryKey}`;
}

/**
 * 单分类模板切片（分节按分类单独渲染 / 单独保存，pages.md §4.3）：
 * 复制 schema 并只保留指定分类；分类不存在返回 null（页面按 40400 处理）。
 */
export function sliceSchemaCategory(
  schema: FormSchema,
  categoryKey: string,
): FormSchema | null {
  const category = schema.categories.find((item) => item.key === categoryKey);
  if (!category) return null;
  return { ...schema, categories: [category] };
}

/**
 * 基线资料页签的分节只读展示模板（pages.md §4.2「模板分类连续滚动的只读视图」）：
 * 只保留 storage=json 的字段——姓名 / 病案号等敏感身份字段与出院登记实体列
 * （性别、手术 / 入院 / 出院日期等）不经分节 JSON，值来自患者主档（顶部登记信息块），
 * 渲染器里显示「未填写」会与主档值矛盾，故过滤；分类无 JSON 字段时返回 null（跳过该块）。
 */
export function sectionDisplaySchema(
  schema: FormSchema,
  categoryKey: string,
): FormSchema | null {
  const sliced = sliceSchemaCategory(schema, categoryKey);
  const category = sliced?.categories[0];
  if (!category) return null;
  const fields = category.fields.filter((field) => field.storage === 'json');
  if (fields.length === 0) return null;
  return {
    ...sliced,
    categories: [{ ...category, fields }],
  };
}
