/**
 * E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010：档案概览与患者时间轴的类型与纯逻辑
 * （接口契约 docs/api/patient.md §2 / §7，后端 `com.crdp.followup.patient.api.PatientOverviewController`）。
 *
 * 概览三块（验收「概览显示基线、计划、最近随访」）：基线 = 登记实体列 + 基线资料
 * 完整度（E13-T02 写入）；计划 = 下次随访窗口（E14 写入）；最近随访 = 关键指标
 * （E15 写入）。写入方未落地前列为 null，页面显示占位而不是猜测。
 * 姓名 / 病案号只含后端脱敏值（AGENTS.md §5.3，明文走 E07-T05 专用接口）。
 */
import { PATIENT_STATUS_TEXT, survivalStatusText } from './enums';

/** 下次随访（计划窗口；E14 写入前整体 null）。 */
export interface PatientNextVisit {
  planDate?: null | string;
  windowEnd?: null | string;
  windowStart?: null | string;
}

/** 最近一次随访关键指标（A-PAT-004：生存状态、EF、终点事件；E15 写入前整体 null）。 */
export interface PatientLatestFollowup {
  date: string;
  endpointEvents?: null | Record<string, string>;
  keyIndicators?: null | Record<string, unknown>;
  survivalStatus?: null | string;
}

/** 患者概览（PatientHeader 数据，components.md §4）：name / patientNo 为脱敏值。 */
export interface PatientOverview {
  actions: string[];
  admissionDate?: null | string;
  age?: null | number;
  baselineCompleteness?: null | number;
  deptName?: null | string;
  device?: null | Record<string, string>;
  dischargeDate?: null | string;
  dischargeStatus?: null | number;
  followupCompleteness?: null | number;
  gender?: null | string;
  id: string;
  latestFollowup?: null | PatientLatestFollowup;
  legacyNo?: null | string;
  name: string;
  nextVisit?: null | PatientNextVisit;
  patientNo: string;
  patientType?: null | string;
  responsibleName?: null | string;
  status: string;
  surgeryDate?: null | string;
  terminated: boolean;
  visitNo?: null | string;
  wardId?: null | number;
  wardName?: null | string;
}

/**
 * 时间轴节点类型（A-PAT-013、A-FU-023 / C-FU-023）：DISCHARGE 出院 / STATUS 状态变更（含死亡）/
 * FOLLOWUP 每年随访（任务节点，E15-T07）/ BACKFILL 历史补录（无任务的补录记录，A-FU-014、C-FU-013）/
 * PLANNED 下次计划日期（「计划中 / 待开始」，实时计算不落库，A-FU-001）；UNPLANNED 计划外随访随
 * E15-T06、EVENT 终点事件随 E17 接入。
 */
export type PatientTimelineKind =
  | 'BACKFILL'
  | 'DISCHARGE'
  | 'EVENT'
  | 'FOLLOWUP'
  | 'PLANNED'
  | 'STATUS'
  | 'UNPLANNED';

/**
 * 时间轴节点（E15-T07 起随访节点带任务列）：status 对 FOLLOWUP = 任务状态、BACKFILL = COMPLETED /
 * IN_PROGRESS、PLANNED = PLANNED（前端映射「计划中」）；progressDone / progressTotal 为该子计划次数的
 * 完成进度 x / y（A-FU-023「完成进度 x/y」）；canOpen = false 时节点置灰（计划中，任务开启日才创建）。
 */
export interface PatientTimelineEntry {
  canOpen?: boolean | null;
  completeKind?: null | string;
  createType?: null | string;
  date: string;
  doneDate?: null | string;
  highlightCount?: null | number;
  kind: PatientTimelineKind;
  label?: null | string;
  note?: null | string;
  planDate?: null | string;
  progressDone?: null | number;
  progressTotal?: null | number;
  recordId?: null | string;
  status?: null | string;
  taskId?: null | string;
  taskType?: null | string;
  windowEnd?: null | string;
  windowStart?: null | string;
}

export interface PatientTimeline {
  items: PatientTimelineEntry[];
  patientId: string;
}

/** 时间轴节点名（components.md §9）：STATUS / PLANNED 按状态码映射文案，其余用后端数据名。 */
export function timelineEntryLabel(entry: PatientTimelineEntry): string {
  if (entry.kind === 'STATUS') {
    const status = entry.status ?? '';
    return PATIENT_STATUS_TEXT[status] ?? status ?? '状态变更';
  }
  if (entry.kind === 'PLANNED') return '下次随访';
  return entry.label ?? '';
}

/** PLANNED 节点的显示状态（A-FU-001「计划中 / 待开始」，不对应实际任务）。 */
export const TIMELINE_PLANNED_TEXT = '计划中';

/**
 * 随访节点的完成进度文案（A-FU-023「完成进度 x/y」）：「进度 0/1」；无进度列的节点（出院 /
 * 状态 / 无任务信息）返回空串。
 */
export function timelineProgressText(entry: PatientTimelineEntry): string {
  if (entry.progressDone === null || entry.progressDone === undefined)
    return '';
  if (entry.progressTotal === null || entry.progressTotal === undefined)
    return '';
  return `进度 ${entry.progressDone}/${entry.progressTotal}`;
}

/**
 * 下次随访显示（PatientHeader 元信息行）：已终止显示「—（已终止）」（pages.md §4.10）；
 * E14 写入前无计划显示「未安排」。
 */
export function nextVisitText(overview: PatientOverview): string {
  if (overview.terminated) return '—（已终止）';
  const planDate = overview.nextVisit?.planDate;
  if (!planDate) return '未安排';
  const { windowEnd, windowStart } = overview.nextVisit ?? {};
  if (windowStart && windowEnd) {
    return `计划 ${planDate}（窗口 ${windowStart} ~ ${windowEnd}）`;
  }
  return `计划 ${planDate}`;
}

/** 最近随访显示：E15 写入前显示「暂无随访记录」，否则日期 + 生存状态。 */
export function latestFollowupText(overview: PatientOverview): string {
  const latest = overview.latestFollowup;
  if (!latest) return '暂无随访记录';
  return `${latest.date} · 生存状态 ${survivalStatusText(latest.survivalStatus)}`;
}

/** 完整度显示（A-PAT-019）：null →「—」（写入方 E13-T02 / E15 未落地），不显示 0%。 */
export function completenessText(value: null | number | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${value}%`;
}

/** 性别显示（pt_patient.gender M / F，patient.md §1 口径）。 */
export function genderText(gender: null | string | undefined): string {
  if (gender === 'M') return '男';
  if (gender === 'F') return '女';
  return '—';
}

// ---------------------------------------------------------------- 联系方式与变更记录（A-PAT-004「从概览进入」）

/** 联系方式版本（E13-T03 pt_contact：换号保留历史，当前版本 = 槽位内最大 version）。 */
export interface PatientContactVersion {
  current: boolean;
  effectiveDate: string;
  id: string;
  phoneMasked: string;
  remark?: null | string;
  slot: string;
  source: string;
  sourceRefId?: null | number;
  version: number;
  wechatAdded?: boolean | null;
}

export interface PatientContactInfo {
  items: PatientContactVersion[];
}

/** 号码槽位（A 规格 §2：电话1、电话2）。 */
export const CONTACT_SLOT_TEXT: Record<string, string> = {
  PHONE_1: '电话1',
  PHONE_2: '电话2',
};

export function contactSlotText(slot: string): string {
  return CONTACT_SLOT_TEXT[slot] ?? slot;
}

/** 版本来源（pt_contact.source）。 */
export const CONTACT_SOURCE_TEXT: Record<string, string> = {
  REGISTRATION: '登记时',
  FOLLOWUP: '随访中',
  IMPORT: '历史导入',
  MANUAL: '页面维护',
};

export function contactSourceText(source: string): string {
  return CONTACT_SOURCE_TEXT[source] ?? source;
}

/** 状态变更历史行（E13-T03 pt_status_history：只追加，登记初始行 source=REGISTRATION）。 */
export interface PatientStatusHistoryRow {
  createdAt: string;
  createdBy?: null | number;
  effectiveDate: string;
  id: string;
  reason?: null | string;
  source: string;
  status: string;
}

/** 状态来源（pt_status_history.source）。 */
export const STATUS_SOURCE_TEXT: Record<string, string> = {
  REGISTRATION: '登记',
  MANUAL: '人工流转',
  PLAN: '计划联动',
  SYSTEM: '系统判定',
};

export function statusSourceText(source: string): string {
  return STATUS_SOURCE_TEXT[source] ?? source;
}
