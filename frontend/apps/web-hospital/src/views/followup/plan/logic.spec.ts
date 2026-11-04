import type { PlanDetailView, PlanNodeView } from '@crdp/api';

import { planEnrollmentStatusText, planLogActionText } from '@crdp/api';
import { describe, expect, it } from 'vitest';

import {
  buildNodeInputs,
  canEditPlan,
  enrollmentReasonText,
  newNodeRow,
  newSubplanRow,
  planActionsFor,
  planDetailPath,
  planLogJsonText,
  planTimeText,
  toNodeRows,
  validateBasicInfo,
  validateNodeRows,
} from './logic';

// E14-T02 / A-FU-002、A-FU-016、A-FU-018、A-FU-019 · 计划配置页逻辑。
// 验收：A、B、C 三种节点表都能配出——A 预置（hospital-a-spec §14 前的 A-FU-017）、
// 医院 B 预置（第 14 章）、医院 C 节点表（hospital-c-spec §5）分别用编辑行模型拼出，
// 校验通过并转换成 PUT /plans/{id}/nodes 的入参，参数值与规格表逐项一致。
// 状态动作与后端 PlanService 状态机（A-FU-018）同口径。

describe('行模型：后端节点两层共表 → 编辑行', () => {
  it('子计划行在前、节点行挂到所属子计划之后（sort_no、id 升序保持）', () => {
    const rows = toNodeRows(
      detailOfNodes([
        subplan('1', '第 1 年随访', {
          repeatMode: 'CYCLE',
          cycleValue: 1,
          cycleUnit: 'YEAR',
        }),
        node('2', '1', '电话随访', { offsetValue: 1, offsetUnit: 'YEAR' }),
      ]),
    );
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({
      key: 'sp-1',
      kind: 'SUBPLAN',
      name: '第 1 年随访',
    });
    expect(rows[1]).toMatchObject({
      key: 'n-2',
      kind: 'NODE',
      parentKey: 'sp-1',
      offsetValue: 1,
      offsetUnit: 'YEAR',
      remindValue: 0,
    });
  });

  it('空节点 → 空行（发布前由页面引导先建子计划与节点）', () => {
    expect(toNodeRows(detailOfNodes([]))).toEqual([]);
  });

  it('新增行默认值 = A 医院预置口径（+1 年、提前 7 天、前后 7 天；A-FU-017）', () => {
    const subplan = newSubplanRow();
    expect(subplan.repeatMode).toBe('CYCLE');
    expect(subplan.cycleValue).toBe(1);
    expect(subplan.cycleUnit).toBe('YEAR');

    const node = newNodeRow('sp-1');
    expect(node).toMatchObject({
      basis: 'PLAN_START',
      offsetUnit: 'YEAR',
      offsetValue: 1,
      remindUnit: 'DAY',
      remindValue: 7,
      taskType: 'FORM',
      windowAfterUnit: 'DAY',
      windowAfterValue: 7,
      windowBeforeUnit: 'DAY',
      windowBeforeValue: 7,
    });
  });
});

describe('buildNodeInputs：编辑行 → PUT /plans/{id}/nodes 入参（key/parentKey 引用）', () => {
  it('子计划行 parentKey 为空、节点行指向子计划 key；sortNo 按行序 10 步进', () => {
    const rows = [
      { ...newSubplanRow(), key: 'sp-a', name: '里程碑' },
      { ...newNodeRow('sp-a'), key: 'n-b', name: '电话随访' },
    ];
    const inputs = buildNodeInputs(rows);
    expect(inputs).toHaveLength(2);
    expect(inputs[0]).toMatchObject({
      key: 'sp-a',
      parentKey: null,
      repeatMode: 'CYCLE',
      name: '里程碑',
      sortNo: 0,
    });
    expect(inputs[1]).toMatchObject({
      key: 'n-b',
      parentKey: 'sp-a',
      repeatMode: null,
      basis: 'PLAN_START',
      sortNo: 10,
    });
  });
});

describe('validateNodeRows：与后端 PlanNodeRule 同口径的第一道拦截（40001 前置）', () => {
  it('没有子计划时报错（A-FU-016 三层结构）', () => {
    const rows = [newNodeRow('sp-1')];
    expect(validateNodeRows(rows)).toContain(
      '至少需要一个子计划（里程碑），节点挂在子计划下',
    );
  });

  it('固定次数（FIXED）须填次数 ≥1；持续循环（CYCLE）须填周期 ≥1', () => {
    const fixed = {
      ...newSubplanRow(),
      repeatMode: 'FIXED' as const,
      repeatCount: null,
    };
    expect(validateNodeRows([fixed])).toContain(
      '子计划「子计划（示例）」固定次数至少为 1',
    );
    const cycle = {
      ...newSubplanRow(),
      repeatMode: 'CYCLE' as const,
      cycleValue: 0,
    };
    expect(validateNodeRows([cycle])).toContain(
      '子计划「子计划（示例）」循环周期至少为 1',
    );
  });

  it('节点数值不能为负；FORM 任务必须填表单 code；上级子计划必须存在', () => {
    const rows = [
      { ...newSubplanRow(), key: 'sp-a' },
      {
        ...newNodeRow('sp-a'),
        key: 'n-1',
        name: '电话随访',
        formCode: '',
        offsetValue: -1,
      },
      { ...newNodeRow('sp-x'), key: 'n-2', name: '无上级' },
    ];
    const errors = validateNodeRows(rows);
    expect(errors).toContain('节点「电话随访」偏移不能为负数');
    expect(errors).toContain(
      '节点「电话随访」是表单任务，须填写随访记录表单 code',
    );
    expect(errors).toContain('节点「无上级」的上级子计划不存在：sp-x');
  });

  it('名称必填；完整合法行无错误', () => {
    const rows = [
      { ...newSubplanRow(), key: 'sp-a', name: '' },
      { ...newSubplanRow(), key: 'sp-b', name: '第 1 年' },
      {
        ...newNodeRow('sp-b'),
        key: 'n-1',
        name: '电话随访',
        formCode: 'fu_annual_v1',
      },
    ];
    expect(validateNodeRows(rows)).toEqual(['SUBPLAN名称不能为空']);
  });
});

describe('验收：A、B、C 三种节点表都能配出', () => {
  it('医院 A「冠心病术后年度随访」：持续循环 +1 年、提前 7 天、前后 7 天、长期不设结束', () => {
    const rows = [
      {
        ...newSubplanRow(),
        key: 'sp-a',
        name: '年度随访（持续循环）',
      },
      {
        ...newNodeRow('sp-a'),
        key: 'n-a',
        name: '电话随访 + 随访记录表单',
        formCode: 'fu_annual_v1',
        ownerType: 'DOCTOR' as const,
      },
    ];
    expect(validateNodeRows(rows)).toEqual([]);
    const inputs = buildNodeInputs(rows);
    const [sub, node] = inputs;
    expect(sub).toMatchObject({
      parentKey: null,
      name: '年度随访（持续循环）',
      repeatMode: 'CYCLE',
      cycleValue: 1,
      cycleUnit: 'YEAR',
    });
    expect(node).toMatchObject({
      parentKey: sub?.key,
      name: '电话随访 + 随访记录表单',
      basis: 'PLAN_START',
      offsetValue: 1,
      offsetUnit: 'YEAR',
      remindValue: 7,
      remindUnit: 'DAY',
      windowBeforeValue: 7,
      windowBeforeUnit: 'DAY',
      windowAfterValue: 7,
      windowAfterUnit: 'DAY',
      taskType: 'FORM',
      formCode: 'fu_annual_v1',
      ownerType: 'DOCTOR',
    });
  });

  it('医院 B「心脏术后随访」：术后 1/3/5 天（当天提醒、前 0 后 1、补建补录）、3 个月、1 年、N≥2 持续循环', () => {
    const rows = [
      { ...newSubplanRow(), key: 'sp-b', name: '心脏术后随访（持续循环）' },
      bNode('术后第 1 天', 1, 'DAY', 0, 'DAY', 0, 1, 'DAY', 'BACKFILL'),
      bNode('术后第 3 天', 3, 'DAY', 0, 'DAY', 0, 1, 'DAY', 'BACKFILL'),
      bNode('术后第 5 天', 5, 'DAY', 0, 'DAY', 0, 1, 'DAY', 'BACKFILL'),
      bNode('术后 3 个月', 3, 'MONTH', 2, 'WEEK', 14, 14, 'DAY', null),
      bNode('术后 1 年', 1, 'YEAR', 1, 'MONTH', 1, 1, 'MONTH', null),
      bNode('第 N 年（N ≥ 2）', 2, 'YEAR', 1, 'MONTH', 1, 1, 'MONTH', null),
    ];
    expect(validateNodeRows(rows)).toEqual([]);
    const inputs = buildNodeInputs(rows);
    const byName = new Map(inputs.map((row) => [row.name, row]));

    expect(byName.get('术后第 1 天')).toMatchObject({
      offsetValue: 1,
      offsetUnit: 'DAY',
      remindValue: 0,
      windowBeforeValue: 0,
      windowAfterValue: 1,
      windowAfterUnit: 'DAY',
      passedWindowAction: 'BACKFILL',
    });
    expect(byName.get('术后第 3 天')).toMatchObject({
      offsetValue: 3,
      passedWindowAction: 'BACKFILL',
    });
    expect(byName.get('术后第 5 天')).toMatchObject({
      offsetValue: 5,
      passedWindowAction: 'BACKFILL',
    });
    expect(byName.get('术后 3 个月')).toMatchObject({
      offsetValue: 3,
      offsetUnit: 'MONTH',
      remindValue: 2,
      remindUnit: 'WEEK',
      windowBeforeValue: 14,
      windowAfterValue: 14,
    });
    expect(byName.get('术后 1 年')).toMatchObject({
      offsetValue: 1,
      offsetUnit: 'YEAR',
      remindValue: 1,
      remindUnit: 'MONTH',
      windowBeforeValue: 1,
      windowBeforeUnit: 'MONTH',
      windowAfterValue: 1,
      windowAfterUnit: 'MONTH',
    });
    expect(byName.get('第 N 年（N ≥ 2）')).toMatchObject({
      basis: 'PLAN_START',
      offsetValue: 2,
      offsetUnit: 'YEAR',
    });
    const sub = inputs.find((row) => row.repeatMode === 'CYCLE');
    expect(sub).toMatchObject({ cycleValue: 1, cycleUnit: 'YEAR' });
    // 术后 1/3/5 天与 3 个月、1 年全部挂同一子计划下（parentKey 解析一致）
    const subKey = sub?.key;
    for (const name of [
      '术后第 1 天',
      '术后 3 个月',
      '术后 1 年',
      '第 N 年（N ≥ 2）',
    ]) {
      expect(byName.get(name)?.parentKey).toBe(subKey);
    }
  });

  it('医院 C「起搏器术后随访」：72 小时不对称窗口（植入日至植入后 7 天）、1/3/6/12 个月、常规与 ERI 期按上次随访滚动', () => {
    const rows = [
      { ...newSubplanRow(), key: 'sp-c', name: '起搏器术后随访（持续循环）' },
      cNode(
        '术后72小时/出院前',
        'PLAN_START',
        3,
        'DAY',
        0,
        'DAY',
        3,
        4,
        'DAY',
        'SKIP',
        null,
      ),
      cNode(
        '术后1个月',
        'PLAN_START',
        1,
        'MONTH',
        0,
        'DAY',
        7,
        7,
        'DAY',
        null,
        null,
      ),
      cNode(
        '术后3个月',
        'PLAN_START',
        3,
        'MONTH',
        0,
        'DAY',
        14,
        14,
        'DAY',
        null,
        null,
      ),
      cNode(
        '术后6个月',
        'PLAN_START',
        6,
        'MONTH',
        0,
        'DAY',
        14,
        14,
        'DAY',
        null,
        null,
      ),
      cNode(
        '术后1年',
        'PLAN_START',
        12,
        'MONTH',
        0,
        'DAY',
        30,
        30,
        'DAY',
        null,
        null,
      ),
      cNode(
        '常规随访（每 12 个月）',
        'LAST_VISIT',
        12,
        'MONTH',
        0,
        'DAY',
        30,
        30,
        'DAY',
        null,
        null,
      ),
      cNode(
        'ERI 期随访（每 1 个月）',
        'LAST_VISIT',
        1,
        'MONTH',
        0,
        'DAY',
        7,
        7,
        'DAY',
        null,
        'ERI',
      ),
    ];
    expect(validateNodeRows(rows)).toEqual([]);
    const inputs = buildNodeInputs(rows);
    const byName = new Map(inputs.map((row) => [row.name, row]));

    expect(byName.get('术后72小时/出院前')).toMatchObject({
      basis: 'PLAN_START',
      offsetValue: 3,
      offsetUnit: 'DAY',
      windowBeforeValue: 3,
      windowBeforeUnit: 'DAY',
      windowAfterValue: 4,
      windowAfterUnit: 'DAY',
      passedWindowAction: 'SKIP',
    });
    expect(byName.get('术后1个月')).toMatchObject({
      offsetValue: 1,
      offsetUnit: 'MONTH',
      windowBeforeValue: 7,
      windowAfterValue: 7,
    });
    expect(byName.get('术后3个月')).toMatchObject({
      offsetValue: 3,
      offsetUnit: 'MONTH',
      windowBeforeValue: 14,
      windowAfterValue: 14,
    });
    expect(byName.get('术后6个月')).toMatchObject({
      offsetValue: 6,
      offsetUnit: 'MONTH',
      windowBeforeValue: 14,
      windowAfterValue: 14,
    });
    expect(byName.get('术后1年')).toMatchObject({
      offsetValue: 12,
      offsetUnit: 'MONTH',
      windowBeforeValue: 30,
      windowAfterValue: 30,
    });
    // 常规随访（普通起搏器 +12 个月）与 ERI 期（+1 个月）都按上次实际随访滚动（A-FU-016 基准）
    expect(byName.get('常规随访（每 12 个月）')).toMatchObject({
      basis: 'LAST_VISIT',
      offsetValue: 12,
      offsetUnit: 'MONTH',
      windowBeforeValue: 30,
      windowAfterValue: 30,
      enrollMode: null,
    });
    expect(byName.get('ERI 期随访（每 1 个月）')).toMatchObject({
      basis: 'LAST_VISIT',
      offsetValue: 1,
      offsetUnit: 'MONTH',
      windowBeforeValue: 7,
      windowAfterValue: 7,
      enrollMode: 'ERI',
    });
  });
});

/** B 医院节点（第 14 章）的编辑行构造。 */
function bNode(
  name: string,
  offsetValue: number,
  offsetUnit: 'DAY' | 'MONTH' | 'YEAR',
  remindValue: number,
  remindUnit: 'DAY' | 'MONTH' | 'WEEK',
  windowBeforeValue: number,
  windowAfterValue: number,
  windowUnit: 'DAY' | 'MONTH',
  passedWindowAction: 'BACKFILL' | null,
) {
  return {
    ...newNodeRow('sp-b'),
    key: `n-${name}`,
    name,
    formCode: 'fu_record_v1',
    offsetValue,
    offsetUnit,
    remindValue,
    remindUnit,
    windowBeforeValue,
    windowBeforeUnit: windowUnit,
    windowAfterValue,
    windowAfterUnit: windowUnit,
    passedWindowAction,
  };
}

/** C 医院节点（hospital-c-spec §5 节点表）的编辑行构造。 */
function cNode(
  name: string,
  basis: 'LAST_VISIT' | 'PLAN_START',
  offsetValue: number,
  offsetUnit: 'DAY' | 'MONTH',
  remindValue: number,
  remindUnit: 'DAY' | 'MONTH' | 'WEEK',
  windowBeforeValue: number,
  windowAfterValue: number,
  windowUnit: 'DAY' | 'MONTH',
  passedWindowAction: 'SKIP' | null,
  enrollMode: 'ERI' | null,
) {
  return {
    ...newNodeRow('sp-c'),
    key: `n-${name}`,
    name,
    basis,
    formCode: 'fu_pacer_v1',
    offsetValue,
    offsetUnit,
    remindValue,
    remindUnit,
    windowBeforeValue,
    windowBeforeUnit: windowUnit,
    windowAfterValue,
    windowAfterUnit: windowUnit,
    passedWindowAction,
    enrollMode,
  };
}

describe('基本信息校验与状态动作（A-FU-018/019 同口径）', () => {
  it('新建 / 编辑：名称必填 ≤128、code ≤64；YEARS 须填 ≥1 年', () => {
    expect(
      validateBasicInfo({ code: '', name: '年度随访', cycleType: 'ONGOING' }),
    ).toEqual(['计划编码不能为空']);
    expect(
      validateBasicInfo({ code: 'fu_annual', name: '', cycleType: 'ONGOING' }),
    ).toEqual(['计划名称不能为空']);
    expect(
      validateBasicInfo({
        code: 'fu_annual',
        name: '年度随访',
        cycleType: 'YEARS',
      }),
    ).toEqual(['固定管理周期须填年数（≥1）']);
    expect(
      validateBasicInfo({
        code: 'fu_annual',
        name: '年度随访',
        cycleType: 'ONGOING',
      }),
    ).toEqual([]);
    expect(
      validateBasicInfo({
        code: 'x'.repeat(65),
        name: '年度随访',
        cycleType: 'ONGOING',
      }),
    ).toEqual(['计划编码最多 64 个字符']);
  });

  it('可编辑 = 仅未发布（A-FU-019：已发布只能复制出新版本）', () => {
    expect(canEditPlan('UNPUBLISHED')).toBe(true);
    expect(canEditPlan('PUBLISHED')).toBe(false);
    expect(canEditPlan('RUNNING')).toBe(false);
    expect(canEditPlan('PAUSED')).toBe(false);
    expect(canEditPlan('DISABLED')).toBe(false);
  });

  it('状态机动作：未发布 → 发布 / 复制仅对已发布及其后状态；暂停 ↔ 恢复；停用终态', () => {
    expect(planActionsFor('UNPUBLISHED')).toEqual(['publish']);
    expect(planActionsFor('PUBLISHED')).toEqual(['copy', 'disable', 'pause']);
    expect(planActionsFor('RUNNING')).toEqual(['copy', 'disable', 'pause']);
    expect(planActionsFor('PAUSED')).toEqual(['copy', 'disable', 'resume']);
    expect(planActionsFor('DISABLED')).toEqual(['copy']);
  });

  it('详情路由（隐藏路由 /followup/plan/:planId 由后端菜单下发）', () => {
    expect(planDetailPath('42')).toBe('/followup/plan/42');
  });
});

// ---------------------------------------------------------------- 构造工具

function subplan(
  id: string,
  name: string,
  extra: Partial<PlanNodeView> = {},
): PlanNodeView {
  return {
    basis: null,
    cycleUnit: null,
    cycleValue: null,
    enrollMode: null,
    formCode: null,
    id,
    name,
    nodeType: 'SUBPLAN',
    offsetUnit: 'YEAR',
    offsetValue: 0,
    ownerType: 'WARD_DEFAULT',
    parentId: null,
    passedWindowAction: null,
    remindUnit: 'DAY',
    remindValue: 0,
    repeatCount: null,
    repeatMode: 'CYCLE',
    sortNo: 0,
    taskType: 'FORM',
    windowAfterUnit: 'DAY',
    windowAfterValue: 0,
    windowBeforeUnit: 'DAY',
    windowBeforeValue: 0,
    ...extra,
  };
}

function node(
  id: string,
  parentId: string,
  name: string,
  extra: Partial<PlanNodeView> = {},
): PlanNodeView {
  return {
    ...subplan(id, name, extra),
    nodeType: 'NODE',
    parentId,
    basis: 'PLAN_START',
    offsetValue: 1,
    offsetUnit: 'YEAR',
    taskType: 'FORM',
    formCode: 'fu_annual_v1',
    repeatMode: null,
  };
}

function detailOfNodes(nodes: PlanNodeView[]): PlanDetailView {
  return {
    nodes,
    plan: {
      code: 'fu_annual',
      cycleType: 'ONGOING',
      cycleYears: null,
      enrolledCount: 0,
      id: '1',
      lockVersion: 0,
      name: '冠心病术后年度随访（示例）',
      publishedAt: null,
      remark: null,
      startDateField: 'DISCHARGE_DATE',
      status: 'UNPUBLISHED',
      version: 1,
    },
  };
}

// E14-T08 / A-FU-021、A-FU-022 · 患者列表与变更记录页签的逻辑
// （时间到分钟、前后内容 JSON 美化、状态补充说明与中文映射）。
describe('e14-T08 · 患者列表与变更记录', () => {
  it('时间列到分钟（ISO-8601 → YYYY-MM-DD HH:mm）', () => {
    expect(planTimeText('2026-03-01T09:30:25+08:00')).toBe('2026-03-01 09:30');
    expect(planTimeText(null)).toBe('—');
    expect(planTimeText('2026-03-01')).toBe('2026-03-01');
  });

  it('前后内容 JSON 美化，非 JSON 回退原文', () => {
    expect(planLogJsonText('{"status":"PUBLISHED"}')).toBe(
      '{\n  "status": "PUBLISHED"\n}',
    );
    expect(planLogJsonText('非 JSON 文本（示例）')).toBe(
      '非 JSON 文本（示例）',
    );
    expect(planLogJsonText(null)).toBe('—');
  });

  it('状态补充说明：已暂停带暂停原因、已移除带移除原因', () => {
    expect(
      enrollmentReasonText({
        pauseReason: '外地随访（示例）',
        status: 'PAUSED',
      }),
    ).toBe('外地随访（示例）');
    expect(
      enrollmentReasonText({ removeReason: '拒访（示例）', status: 'REMOVED' }),
    ).toBe('拒访（示例）');
    expect(enrollmentReasonText({ status: 'ACTIVE' })).toBe('');
  });

  it('入组状态与变更动作的中文映射（A-FU-022 三态 / A-FU-021 动作集）', () => {
    expect(planEnrollmentStatusText('ACTIVE')).toBe('已启动');
    expect(planEnrollmentStatusText('PAUSED')).toBe('已暂停');
    expect(planEnrollmentStatusText('REMOVED')).toBe('已移除');
    expect(planEnrollmentStatusText('UNKNOWN')).toBe('UNKNOWN');
    expect(planEnrollmentStatusText(null)).toBe('—');

    expect(planLogActionText('PLAN_CREATE')).toBe('新建计划');
    expect(planLogActionText('PLAN_NODE_SAVE')).toBe('保存节点');
    expect(planLogActionText('PLAN_DISABLE')).toBe('停用计划');
    expect(planLogActionText('OTHER')).toBe('OTHER');
    expect(planLogActionText(null)).toBe('—');
  });
});
