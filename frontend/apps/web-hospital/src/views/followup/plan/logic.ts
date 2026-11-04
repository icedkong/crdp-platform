import type {
  PlanCycleType,
  PlanDetailView,
  PlanEnrollMode,
  PlanNodeBasis,
  PlanNodeInput,
  PlanNodeView,
  PlanOffsetUnit,
  PlanOwnerType,
  PlanPassedWindowAction,
  PlanRemindUnit,
  PlanRepeatMode,
  PlanStartDateField,
  PlanStatus,
  PlanTaskType,
} from '@crdp/api';

/**
 * E14-T02 / A-FU-002、A-FU-016 · 计划配置页逻辑（纯函数，页面 /followup/plan、
 * /followup/plan/:planId，pages.md §8）。节点两层共表（fu_plan_node：SUBPLAN / NODE），
 * 编辑行模型 = 后端 PlanNodeRule 的前端镜像：校验消息与 40001 同口径（第一道拦截，
 * 后端仍逐项校验）；计算预览（计划日期 / 窗口 / 开启日）在 @crdp/rules 的 plan-date，
 * 与后端 E14-T03 共享用例。
 */

// ---------------------------------------------------------------- 节点编辑行

/** 子计划（里程碑）编辑行。 */
export interface SubplanRow {
  cycleUnit: PlanOffsetUnit;
  cycleValue: null | number;
  key: string;
  kind: 'SUBPLAN';
  name: string;
  repeatCount: null | number;
  repeatMode: PlanRepeatMode;
}

/** 节点（任务定义）编辑行：挂在某个子计划下。 */
export interface NodeRow {
  basis: PlanNodeBasis;
  enrollMode: PlanEnrollMode;
  formCode: string;
  key: string;
  kind: 'NODE';
  name: string;
  offsetUnit: PlanOffsetUnit;
  offsetValue: number;
  ownerType: PlanOwnerType;
  parentKey: string;
  passedWindowAction: '' | PlanPassedWindowAction;
  remindUnit: PlanRemindUnit;
  remindValue: number;
  taskType: PlanTaskType;
  windowAfterUnit: PlanOffsetUnit;
  windowAfterValue: number;
  windowBeforeUnit: PlanOffsetUnit;
  windowBeforeValue: number;
}

export type PlanNodeRow = NodeRow | SubplanRow;

/** 新增子计划默认行：持续循环、每年一次（A-FU-017 预置口径）。 */
export function newSubplanRow(key = `sp-${nextKey++}`): SubplanRow {
  return {
    cycleUnit: 'YEAR',
    cycleValue: 1,
    key,
    kind: 'SUBPLAN',
    name: '子计划（示例）',
    repeatCount: null,
    repeatMode: 'CYCLE',
  };
}

/**
 * 新增节点默认行 = A 医院预置口径（A-FU-017）：+1 年、提前 7 天提醒、窗口前后 7 天、
 * 电话随访 + 表单、责任医生。
 */
export function newNodeRow(parentKey: string, key = `n-${nextKey++}`): NodeRow {
  return {
    basis: 'PLAN_START',
    enrollMode: '',
    formCode: '',
    key,
    kind: 'NODE',
    name: '节点（示例）',
    offsetUnit: 'YEAR',
    offsetValue: 1,
    ownerType: 'DOCTOR',
    parentKey,
    passedWindowAction: '',
    remindUnit: 'DAY',
    remindValue: 7,
    taskType: 'FORM',
    windowAfterUnit: 'DAY',
    windowAfterValue: 7,
    windowBeforeUnit: 'DAY',
    windowBeforeValue: 7,
  };
}

let nextKey = 1;

/** 后端节点视图（两层共表、sort_no / id 升序）→ 编辑行（顺序保持）。 */
export function toNodeRows(detail: PlanDetailView): PlanNodeRow[] {
  return detail.nodes.map((view) => {
    const key = `sp-${view.id}`;
    if (view.nodeType === 'SUBPLAN') {
      return {
        cycleUnit: (view.cycleUnit ?? 'YEAR') as PlanOffsetUnit,
        cycleValue: view.cycleValue,
        key,
        kind: 'SUBPLAN',
        name: view.name,
        repeatCount: view.repeatCount,
        repeatMode: (view.repeatMode ?? 'CYCLE') as PlanRepeatMode,
      } satisfies SubplanRow;
    }
    return {
      basis: (view.basis ?? 'PLAN_START') as PlanNodeBasis,
      enrollMode: (view.enrollMode ?? '') as PlanEnrollMode,
      formCode: view.formCode ?? '',
      key: `n-${view.id}`,
      kind: 'NODE',
      name: view.name,
      offsetUnit: view.offsetUnit as PlanOffsetUnit,
      offsetValue: view.offsetValue,
      ownerType: view.ownerType as PlanOwnerType,
      parentKey: `sp-${view.parentId}`,
      passedWindowAction: (view.passedWindowAction ?? '') as
        | ''
        | PlanPassedWindowAction,
      remindUnit: view.remindUnit as PlanRemindUnit,
      remindValue: view.remindValue,
      taskType: view.taskType as PlanTaskType,
      windowAfterUnit: view.windowAfterUnit as PlanOffsetUnit,
      windowAfterValue: view.windowAfterValue,
      windowBeforeUnit: view.windowBeforeUnit as PlanOffsetUnit,
      windowBeforeValue: view.windowBeforeValue,
    } satisfies NodeRow;
  });
}

/** 编辑行 → PUT /plans/{id}/nodes 入参（key / parentKey 请求内引用；sortNo 按行序 10 步进）。 */
export function buildNodeInputs(rows: PlanNodeRow[]): PlanNodeInput[] {
  return rows.map((row, index) => {
    const sortNo = index * 10;
    if (row.kind === 'SUBPLAN') {
      return {
        basis: null,
        cycleUnit: row.cycleUnit,
        cycleValue: row.cycleValue,
        enrollMode: null,
        formCode: null,
        key: row.key,
        name: row.name.trim(),
        offsetUnit: 'YEAR',
        offsetValue: 0,
        ownerType: 'WARD_DEFAULT',
        parentKey: null,
        passedWindowAction: null,
        remindUnit: 'DAY',
        remindValue: 0,
        repeatCount: row.repeatCount,
        repeatMode: row.repeatMode,
        sortNo,
        taskType: 'FORM',
        windowAfterUnit: 'DAY',
        windowAfterValue: 0,
        windowBeforeUnit: 'DAY',
        windowBeforeValue: 0,
      };
    }
    return {
      basis: row.basis,
      cycleUnit: null,
      cycleValue: null,
      enrollMode: row.enrollMode === '' ? null : row.enrollMode,
      formCode: row.taskType === 'FORM' ? row.formCode.trim() || null : null,
      key: row.key,
      name: row.name.trim(),
      offsetUnit: row.offsetUnit,
      offsetValue: row.offsetValue,
      ownerType: row.ownerType,
      parentKey: row.parentKey,
      passedWindowAction:
        row.passedWindowAction === '' ? null : row.passedWindowAction,
      remindUnit: row.remindUnit,
      remindValue: row.remindValue,
      repeatCount: null,
      repeatMode: null,
      sortNo,
      taskType: row.taskType,
      windowAfterUnit: row.windowAfterUnit,
      windowAfterValue: row.windowAfterValue,
      windowBeforeUnit: row.windowBeforeUnit,
      windowBeforeValue: row.windowBeforeValue,
    };
  });
}

// ---------------------------------------------------------------- 校验

/** 基本信息校验（新建 / 编辑共用；消息与后端 40001 同口径）。 */
export interface BasicInfoModel {
  code: string;
  cycleType: PlanCycleType | string;
  cycleYears: null | number;
  name: string;
}

export function validateBasicInfo(model: BasicInfoModel): string[] {
  const errors: string[] = [];
  if (!model.code.trim()) {
    errors.push('计划编码不能为空');
  } else if (model.code.trim().length > 64) {
    errors.push('计划编码最多 64 个字符');
  }
  if (!model.name.trim()) {
    errors.push('计划名称不能为空');
  } else if (model.name.trim().length > 128) {
    errors.push('计划名称最多 128 个字符');
  }
  if (
    model.cycleType === 'YEARS' &&
    (!model.cycleYears || model.cycleYears < 1)
  ) {
    errors.push('固定管理周期须填年数（≥1）');
  }
  return errors;
}

/**
 * 节点行校验：与后端 PlanNodeRule.normalize 同口径的第一道拦截
 * （子计划重复方式、节点偏移 / 提醒 / 窗口 / 任务类型 / 表单 code / 上级引用）。
 * 返回错误消息数组；空数组 = 可保存。
 */
export function validateNodeRows(rows: PlanNodeRow[]): string[] {
  const errors: string[] = [];
  const subplanKeys = new Set<string>();
  for (const row of rows) {
    if (row.kind === 'SUBPLAN') {
      subplanKeys.add(row.key);
    }
  }
  if (subplanKeys.size === 0) {
    return ['至少需要一个子计划（里程碑），节点挂在子计划下'];
  }
  for (const row of rows) {
    const name = row.name.trim();
    if (!name) {
      errors.push(`${row.kind === 'SUBPLAN' ? 'SUBPLAN' : 'NODE'}名称不能为空`);
      continue;
    }
    if (name.length > 128) {
      errors.push(`${row.kind}名称最多 128 个字符`);
      continue;
    }
    if (row.kind === 'SUBPLAN') {
      if (
        row.repeatMode === 'FIXED' &&
        (!row.repeatCount || row.repeatCount < 1)
      ) {
        errors.push(`子计划「${name}」固定次数至少为 1`);
      }
      if (
        row.repeatMode === 'CYCLE' &&
        (!row.cycleValue || row.cycleValue < 1)
      ) {
        errors.push(`子计划「${name}」循环周期至少为 1`);
      }
      continue;
    }
    if (row.offsetValue < 0) {
      errors.push(`节点「${name}」偏移不能为负数`);
    }
    if (row.remindValue < 0) {
      errors.push(`节点「${name}」提醒提前量不能为负数`);
    }
    if (row.windowBeforeValue < 0) {
      errors.push(`节点「${name}」窗口前不能为负数`);
    }
    if (row.windowAfterValue < 0) {
      errors.push(`节点「${name}」窗口后不能为负数`);
    }
    if (row.taskType === 'FORM' && !row.formCode.trim()) {
      errors.push(`节点「${name}」是表单任务，须填写随访记录表单 code`);
    }
    if (!subplanKeys.has(row.parentKey)) {
      errors.push(`节点「${name}」的上级子计划不存在：${row.parentKey}`);
    }
  }
  return errors;
}

// ---------------------------------------------------------------- 状态与路由

/** 仅未发布可编辑（A-FU-019：已发布 / 暂停 / 停用整体只读，只能复制出新版本）。 */
export function canEditPlan(status: PlanStatus | string): boolean {
  return status === 'UNPUBLISHED';
}

/** 状态机可用动作（与后端 PlanService 同口径；copy 仅对已发布 / 暂停 / 停用版本开放）。 */
export function planActionsFor(
  status: PlanStatus | string,
): Array<'copy' | 'disable' | 'pause' | 'publish' | 'resume'> {
  switch (status) {
    case 'PAUSED': {
      return ['copy', 'disable', 'resume'];
    }
    case 'PUBLISHED':
    case 'RUNNING': {
      return ['copy', 'disable', 'pause'];
    }
    case 'UNPUBLISHED': {
      return ['publish'];
    }
    // DISABLED 为终态：不能再发布 / 暂停 / 恢复，只能复制出新版本
    default: {
      return ['copy'];
    }
  }
}

/** 计划详情路由（隐藏路由 /followup/plan/:planId 由后端菜单下发）。 */
export function planDetailPath(planId: number | string): string {
  return `/followup/plan/${planId}`;
}

/** 数值 + 单位展示（如「1 年」「7 天」「2 周」）。 */
export function unitValueText(
  value: number,
  unit: string,
  unitText: Record<string, string>,
): string {
  return `${value} ${unitText[unit] ?? unit}`;
}

/** 节点视图的偏移 / 提醒 / 窗口展示文本（详情只读态复用）。 */
export function nodeParamsText(view: PlanNodeView): {
  offset: string;
  remind: string;
  window: string;
} {
  const offsetText: Record<string, string> = {
    DAY: '天',
    MONTH: '月',
    WEEK: '周',
    YEAR: '年',
  };
  const remindText: Record<string, string> = {
    DAY: '天',
    MONTH: '月',
    WEEK: '周',
  };
  return {
    offset: `${view.offsetValue} ${offsetText[view.offsetUnit] ?? view.offsetUnit}`,
    remind:
      view.remindValue === 0
        ? '当天'
        : `${view.remindValue} ${remindText[view.remindUnit] ?? view.remindUnit}`,
    window: `前 ${view.windowBeforeValue} ${
      offsetText[view.windowBeforeUnit] ?? view.windowBeforeUnit
    } / 后 ${view.windowAfterValue} ${
      offsetText[view.windowAfterUnit] ?? view.windowAfterUnit
    }`,
  };
}

/** 起算日期字段在计算预览里的示例日期语义（pages.md §8：出院 2025-03-15 → …）。 */
export function previewBaseLabel(
  startDateField: PlanStartDateField | string,
): string {
  const labels: Record<string, string> = {
    CUSTOM: '自定义起算日期',
    DISCHARGE_DATE: '出院日期',
    IMPLANT_DATE: '植入日期',
    REGISTRATION_DATE: '建档日期',
    SURGERY_DATE: '手术日期',
  };
  return labels[startDateField] ?? '起算日期';
}
