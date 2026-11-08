import type { PatientFilterState } from './patient-search';

import { describe, expect, it } from 'vitest';

import {
  buildPatientSearchPayload,
  completenessDrillOfQuery,
  emptyPatientFilters,
  hasActiveFilters,
  isKeywordValid,
  patientEmptyText,
  patientStatusOfQuery,
  patientStatusOptions,
} from './patient-search';

describe('e13-T04 患者列表检索 payload（A-PAT-002）', () => {
  it('空筛选只带分页（不过滤）', () => {
    const payload = buildPatientSearchPayload(emptyPatientFilters(), 1, 20);
    expect(payload).toEqual({ pageNo: 1, pageSize: 20, filters: {} });
    expect(payload.keyword).toBeUndefined();
  });

  it('检索词去空白后作为 keyword（姓名 / 病案号共用，后端盲索引精确 OR）', () => {
    const state = { ...emptyPatientFilters(), keyword: '  张小明（示例）  ' };
    const payload = buildPatientSearchPayload(state, 2, 20);
    expect(payload.keyword).toBe('张小明（示例）');
    expect(payload.pageNo).toBe(2);
  });

  it('病区与随访状态筛选进 filters（单选转数组）', () => {
    const state: PatientFilterState = {
      ...emptyPatientFilters(),
      wardId: 3,
      status: 'ACTIVE',
    };
    const payload = buildPatientSearchPayload(state, 1, 20);
    expect(payload.filters.wardIds).toEqual([3]);
    expect(payload.filters.status).toEqual(['ACTIVE']);
  });

  it('日期区间两端齐备才发送（半选不作为筛选）', () => {
    const state: PatientFilterState = {
      ...emptyPatientFilters(),
      dischargeRange: ['2026-03-01', '2026-03-31'],
      nextVisitRange: ['2026-11-01', null as unknown as string],
    };
    const payload = buildPatientSearchPayload(state, 1, 20);
    expect(payload.filters.dischargeFrom).toBe('2026-03-01');
    expect(payload.filters.dischargeTo).toBe('2026-03-31');
    expect(payload.filters.nextVisitFrom).toBeUndefined();
    expect(payload.filters.nextVisitTo).toBeUndefined();
  });

  it('排序透传白名单字段（默认排序由后端兜底）', () => {
    const payload = buildPatientSearchPayload(emptyPatientFilters(), 1, 20, {
      field: 'dischargeDate',
      order: 'desc',
    });
    expect(payload.sort).toEqual([{ field: 'dischargeDate', order: 'desc' }]);
  });
});

describe('e13-T04 检索词与筛选状态', () => {
  it('检索词长度口径与后端一致（1–64）', () => {
    expect(isKeywordValid('')).toBe(false);
    expect(isKeywordValid('   ')).toBe(false);
    expect(isKeywordValid('张三')).toBe(true);
    expect(isKeywordValid('非'.repeat(64))).toBe(true);
    expect(isKeywordValid('非'.repeat(65))).toBe(false);
  });

  it('hasActiveFilters 区分空态文案（pages.md §3）', () => {
    expect(hasActiveFilters(emptyPatientFilters())).toBe(false);
    expect(
      hasActiveFilters({ ...emptyPatientFilters(), keyword: '张三' }),
    ).toBe(true);
    expect(hasActiveFilters({ ...emptyPatientFilters(), wardId: 3 })).toBe(
      true,
    );
    expect(
      hasActiveFilters({ ...emptyPatientFilters(), status: 'ACTIVE' }),
    ).toBe(true);
    expect(
      hasActiveFilters({
        ...emptyPatientFilters(),
        dischargeRange: ['2026-03-01', '2026-03-31'],
      }),
    ).toBe(true);
    // 半选区间不算生效筛选（不会发给后端）
    expect(
      hasActiveFilters({
        ...emptyPatientFilters(),
        nextVisitRange: ['2026-11-01', null],
      }),
    ).toBe(false);
  });

  it('空态文案：无筛选空 → 暂无患者（有新建权限带入口）；筛选空 → 清除筛选', () => {
    expect(patientEmptyText(false, true)).toEqual({
      text: '暂无患者',
      actionText: '新建患者',
    });
    expect(patientEmptyText(false, false)).toEqual({
      text: '暂无患者',
      actionText: null,
    });
    expect(patientEmptyText(true, true)).toEqual({
      text: '没有符合条件的患者',
      actionText: '清除筛选',
    });
  });

  it('随访状态选项来自 components.md §2 patient 域映射', () => {
    const options = patientStatusOptions();
    expect(options[0]).toEqual({ label: '随访中', value: 'ACTIVE' });
    expect(options).toHaveLength(6);
  });
});

describe('e19-T02 指标卡钻取进入患者列表（A-DASH-001 点击进入对应列表）', () => {
  it('路由 query status 解析为随访状态筛选（未知值不筛选）', () => {
    expect(patientStatusOfQuery('ACTIVE')).toBe('ACTIVE');
    expect(patientStatusOfQuery(['dead'])).toBe('DEAD');
    expect(patientStatusOfQuery('NOT_A_STATUS')).toBeUndefined();
    expect(patientStatusOfQuery(null)).toBeUndefined();
  });
});

describe('e19-t05 完整度缺失字段钻取进入患者列表（A-DASH-010 / A-DASH-011）', () => {
  it('路由 query 解析为 completenessDrill（missingFieldRoute 的逆过程）', () => {
    expect(
      completenessDrillOfQuery({
        from: '2026-01-01',
        missingField: 'f9t5b',
        missingFieldLabel: '字段二（示例）',
        missingKind: 'BASELINE',
        patientType: 'ELECTIVE',
        timeField: 'DISCHARGE',
        to: '2026-12-31',
        wardId: '3',
      }),
    ).toEqual({
      drill: {
        fieldKey: 'f9t5b',
        from: '2026-01-01',
        kind: 'BASELINE',
        patientType: 'ELECTIVE',
        timeField: 'DISCHARGE',
        to: '2026-12-31',
        wardId: 3,
      },
      label: '字段二（示例）',
    });
  });

  it('缺 missingField 返回 null（非钻取进入）；kind 缺省 BASELINE、year 只在有值时带', () => {
    expect(completenessDrillOfQuery({ status: 'ACTIVE' })).toBeNull();
    expect(completenessDrillOfQuery({ missingField: ['f214'] })).toEqual({
      drill: { fieldKey: 'f214', kind: 'BASELINE' },
      label: 'f214',
    });
    expect(
      completenessDrillOfQuery({
        missingField: 'f214',
        missingKind: 'FOLLOWUP',
        year: '2',
      }).drill.year,
    ).toBe(2);
  });

  it('钻取条件进检索请求体（buildPatientSearchPayload 透传 completenessDrill）', () => {
    const state: PatientFilterState = {
      dischargeRange: null,
      keyword: '',
      nextVisitRange: null,
      status: null,
      wardId: null,
    };
    const payload = buildPatientSearchPayload(
      state,
      1,
      20,
      undefined,
      undefined,
      { fieldKey: 'f214', kind: 'FOLLOWUP' },
    );
    expect(payload.completenessDrill).toEqual({
      fieldKey: 'f214',
      kind: 'FOLLOWUP',
    });
    expect(
      buildPatientSearchPayload(state, 1, 20).completenessDrill,
    ).toBeUndefined();
  });
});
