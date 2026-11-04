/**
 * E14-T02 / A-FU-002、A-FU-016、A-FU-018、A-FU-019：随访计划配置页的类型与展示映射
 * （接口契约 docs/api/followup.md §1，后端 `com.crdp.followup.plan`，E14-T01 已实现；
 * 页面 pages.md §8）。状态中文（StatusTag 文案）在 enums.ts 的 PLAN_STATUS_TEXT；
 * 日期计算（计算预览）在 @crdp/rules 的 plan-date，与后端 E14-T03 共享用例。
 */

/** 计划状态（fu_plan.status；「随访中 / RUNNING」为派生展示态：已发布且有在组入组）。 */
export type PlanStatus =
  | 'DISABLED'
  | 'PAUSED'
  | 'PUBLISHED'
  | 'RUNNING'
  | 'UNPUBLISHED';

/** 起算日期字段（A-FU-016：出院 / 手术 / 建档 / 植入（C）/ 自定义）。 */
export type PlanStartDateField =
  | 'CUSTOM'
  | 'DISCHARGE_DATE'
  | 'IMPLANT_DATE'
  | 'REGISTRATION_DATE'
  | 'SURGERY_DATE';

/** 管理周期类型：YEARS 固定 N 年 / ONGOING 长期（A 医院不设结束年度）。 */
export type PlanCycleType = 'ONGOING' | 'YEARS';

export const PLAN_START_DATE_FIELD_TEXT: Record<string, string> = {
  CUSTOM: '自定义',
  DISCHARGE_DATE: '出院日期',
  IMPLANT_DATE: '植入日期',
  REGISTRATION_DATE: '建档日期',
  SURGERY_DATE: '手术日期',
};

/** 新建 / 编辑弹窗下拉（顺序即展示顺序）。 */
export const PLAN_START_DATE_FIELD_OPTIONS: Array<{
  label: string;
  value: PlanStartDateField;
}> = [
  { label: '出院日期', value: 'DISCHARGE_DATE' },
  { label: '手术日期', value: 'SURGERY_DATE' },
  { label: '建档日期', value: 'REGISTRATION_DATE' },
  { label: '植入日期', value: 'IMPLANT_DATE' },
  { label: '自定义', value: 'CUSTOM' },
];

export function planStartDateFieldText(
  field: null | string | undefined,
): string {
  if (!field) return '—';
  return PLAN_START_DATE_FIELD_TEXT[field] ?? field;
}

/** 周期列文案：YEARS → 「N 年」；ONGOING → 「长期」。 */
export function planCycleText(
  cycleType: null | string | undefined,
  cycleYears: null | number | undefined,
): string {
  if (cycleType === 'YEARS') {
    return cycleYears ? `${cycleYears} 年` : 'N 年（未填）';
  }
  if (cycleType === 'ONGOING') {
    return '长期';
  }
  return cycleType ?? '—';
}

/** 偏移 / 窗口单位（fu_plan_node.offset_unit / window_*_unit）。 */
export type PlanOffsetUnit = 'DAY' | 'MONTH' | 'WEEK' | 'YEAR';

/** 提醒提前量单位（A-FU-016：N 天 / 周 / 月）。 */
export type PlanRemindUnit = 'DAY' | 'MONTH' | 'WEEK';

export const PLAN_OFFSET_UNIT_TEXT: Record<string, string> = {
  DAY: '天',
  MONTH: '月',
  WEEK: '周',
  YEAR: '年',
};

export const PLAN_REMIND_UNIT_TEXT: Record<string, string> = {
  DAY: '天',
  MONTH: '月',
  WEEK: '周',
};

/** 子计划重复方式：FIXED 固定次数 / CYCLE 持续循环（直到死亡、失访或人工停止）。 */
export type PlanRepeatMode = 'CYCLE' | 'FIXED';

export const PLAN_REPEAT_MODE_TEXT: Record<string, string> = {
  CYCLE: '持续循环',
  FIXED: '固定次数',
};

/** 节点偏移基准：PLAN_START 计划起算日期 / LAST_VISIT 上次实际随访日期（C 常规随访滚动）。 */
export type PlanNodeBasis = 'LAST_VISIT' | 'PLAN_START';

export const PLAN_NODE_BASIS_TEXT: Record<string, string> = {
  LAST_VISIT: '上次实际随访',
  PLAN_START: '起算日期',
};

/** 任务类型：FORM 电话随访 + 表单 / REMINDER 站内提醒（A-FU-026）。 */
export type PlanTaskType = 'FORM' | 'REMINDER';

export const PLAN_TASK_TYPE_TEXT: Record<string, string> = {
  FORM: '电话随访 + 表单',
  REMINDER: '站内提醒',
};

/** 责责人类型（A-FU-009）：DOCTOR 责任医生 / WARD_DEFAULT 病区默认负责人。 */
export type PlanOwnerType = 'DOCTOR' | 'WARD_DEFAULT';

export const PLAN_OWNER_TYPE_TEXT: Record<string, string> = {
  DOCTOR: '责任医生',
  WARD_DEFAULT: '病区默认负责人',
};

/** 入组前已过窗口策略：BACKFILL 补建补录（A、B，Q44）/ SKIP 跳过（C，Q14）。 */
export type PlanPassedWindowAction = 'BACKFILL' | 'SKIP';

export const PLAN_PASSED_WINDOW_TEXT: Record<string, string> = {
  BACKFILL: '补建补录',
  SKIP: '跳过',
};

/** 启用条件（C）：NORMAL 常规 / ERI ERI 期循环节点；空 = 全部模式生效。 */
export type PlanEnrollMode = '' | 'ERI' | 'NORMAL';

export const PLAN_ENROLL_MODE_TEXT: Record<string, string> = {
  '': '全部',
  ERI: 'ERI 期',
  NORMAL: '常规',
};

/** 计划行（GET /plans、GET /plans/{id} 的 plan；id 超 2^53 序列化为字符串）。 */
export interface PlanView {
  code: string;
  cycleType: PlanCycleType | string;
  cycleYears: null | number;
  enrolledCount: number;
  id: string;
  lockVersion: number;
  name: string;
  publishedAt: null | string;
  remark: null | string;
  startDateField: PlanStartDateField | string;
  status: PlanStatus | string;
  version: number;
}

/**
 * 节点行（SUBPLAN / NODE 共形，用不到的一层为 null / 默认值）。
 * parent_id 为 null 即子计划行；两层共表 fu_plan_node。
 */
export interface PlanNodeView {
  basis: null | PlanNodeBasis | string;
  cycleUnit: null | PlanOffsetUnit | string;
  cycleValue: null | number;
  enrollMode: null | PlanEnrollMode | string;
  formCode: null | string;
  id: string;
  name: string;
  nodeType: 'NODE' | 'SUBPLAN';
  offsetUnit: PlanOffsetUnit | string;
  offsetValue: number;
  ownerType: PlanOwnerType | string;
  parentId: null | string;
  passedWindowAction: null | PlanPassedWindowAction | string;
  remindUnit: PlanRemindUnit | string;
  remindValue: number;
  repeatCount: null | number;
  repeatMode: null | PlanRepeatMode | string;
  sortNo: number;
  taskType: PlanTaskType | string;
  windowAfterUnit: PlanOffsetUnit | string;
  windowAfterValue: number;
  windowBeforeUnit: PlanOffsetUnit | string;
  windowBeforeValue: number;
}

/** 计划详情：基本信息 + 节点两层（顺序 = sort_no、id 升序）。 */
export interface PlanDetailView {
  nodes: PlanNodeView[];
  plan: PlanView;
}

/** 新建计划请求（POST /plans；code 创建后不可改）。 */
export interface PlanCreateRequest {
  code: string;
  cycleType: PlanCycleType | string;
  cycleYears: null | number;
  name: string;
  remark: null | string;
  startDateField: PlanStartDateField | string;
}

/** 编辑基本信息请求（PUT /plans/{id}，仅未发布；baseVersion = 详情 lockVersion）。 */
export interface PlanUpdateRequest {
  baseVersion: number;
  cycleType: PlanCycleType | string;
  cycleYears: null | number;
  name: string;
  remark: null | string;
  startDateField: PlanStartDateField | string;
}

/**
 * 待保存的节点入参（PUT /plans/{id}/nodes）：key / parentKey 是请求内临时引用，
 * 两层树整体替换，仅未发布；结构与后端 PlanNodeRule.NodeInput 一致。
 */
export interface PlanNodeInput {
  basis: null | PlanNodeBasis | string;
  cycleUnit: null | PlanOffsetUnit | string;
  cycleValue: null | number;
  enrollMode: null | PlanEnrollMode | string;
  formCode: null | string;
  key: string;
  name: string;
  offsetUnit: PlanOffsetUnit | string;
  offsetValue: null | number;
  ownerType: PlanOwnerType | string;
  parentKey: null | string;
  passedWindowAction: null | PlanPassedWindowAction | string;
  remindUnit: PlanRemindUnit | string;
  remindValue: null | number;
  repeatCount: null | number;
  repeatMode: null | PlanRepeatMode | string;
  sortNo: null | number;
  taskType: PlanTaskType | string;
  windowAfterUnit: PlanOffsetUnit | string;
  windowAfterValue: null | number;
  windowBeforeUnit: PlanOffsetUnit | string;
  windowBeforeValue: null | number;
}

/** 节点整体保存请求（{ baseVersion, nodes[] }）。 */
export interface PlanSaveNodesRequest {
  baseVersion: number;
  nodes: PlanNodeInput[];
}

// ---------------------------------------------------------------- 计划患者列表（E14-T08 / A-FU-022）

/** 入组状态（fu_enrollment.status：已启动 / 已暂停 / 已移除）。 */
export type PlanEnrollmentStatus = 'ACTIVE' | 'PAUSED' | 'REMOVED';

export const PLAN_ENROLLMENT_STATUS_TEXT: Record<string, string> = {
  ACTIVE: '已启动',
  PAUSED: '已暂停',
  REMOVED: '已移除',
};

export function planEnrollmentStatusText(
  status: null | string | undefined,
): string {
  if (!status) return '—';
  return PLAN_ENROLLMENT_STATUS_TEXT[status] ?? status;
}

/**
 * 计划患者列表行（GET /plans/{id}/enrollments）：加入时间、随访完成次数、状态。
 * name / mrn 只含脱敏值（AGENTS §5.3，明文走专用接口）；列表按当前用户数据范围出行。
 */
export interface PlanEnrollmentPatientView {
  completedCount: number;
  enrolledAt: null | string;
  gender: null | string;
  id: string;
  legacyNo: null | string;
  mode: null | string;
  mrn: string;
  name: string;
  patientId: string;
  pauseReason: null | string;
  removeReason: null | string;
  status: PlanEnrollmentStatus | string;
  wardName: null | string;
}

/** 计划患者列表分页响应（{ items, total, pageNo, pageSize }）。 */
export interface PlanEnrollmentPage {
  items: PlanEnrollmentPatientView[];
  pageNo: number;
  pageSize: number;
  total: number;
}

// ---------------------------------------------------------------- 计划变更记录（E14-T08 / A-FU-021）

/** 变更动作（fu_plan_log.action；被拒操作不落记录）。 */
export type PlanLogAction =
  | 'PLAN_COPY'
  | 'PLAN_CREATE'
  | 'PLAN_DISABLE'
  | 'PLAN_NODE_SAVE'
  | 'PLAN_PAUSE'
  | 'PLAN_PUBLISH'
  | 'PLAN_RESUME'
  | 'PLAN_UPDATE';

export const PLAN_LOG_ACTION_TEXT: Record<string, string> = {
  PLAN_COPY: '复制为新版本',
  PLAN_CREATE: '新建计划',
  PLAN_DISABLE: '停用计划',
  PLAN_NODE_SAVE: '保存节点',
  PLAN_PAUSE: '暂停计划',
  PLAN_PUBLISH: '发布计划',
  PLAN_RESUME: '恢复计划',
  PLAN_UPDATE: '编辑计划',
};

export function planLogActionText(action: null | string | undefined): string {
  if (!action) return '—';
  return PLAN_LOG_ACTION_TEXT[action] ?? action;
}

/**
 * 变更记录行（GET /plans/{id}/logs，倒序最近 100 条）：操作人、时间与前后内容
 * （基本信息为摘要对象、节点保存为节点数组 JSON）；与审计双写。
 */
export interface PlanLogView {
  action: PlanLogAction | string;
  afterJson: null | string;
  beforeJson: null | string;
  createdAt: string;
  description: null | string;
  id: string;
  operatorId: null | string;
  operatorName: null | string;
  planCode: string;
  planId: string;
}

/** 变更记录响应。 */
export interface PlanLogsView {
  items: PlanLogView[];
}
