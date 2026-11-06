import type { ConfigTableMeta, TenantListItem } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  applyResultSummaryText,
  IMPORT_RULE_NOTES,
  importActionOf,
  previewSummary,
  previewSummaryText,
  tablePreview,
  tablePreviews,
  targetTenantOptions,
  toApplyFormModel,
  validateApplyModel,
} from './apply';

function section(overrides: Partial<ConfigTableMeta> = {}): ConfigTableMeta {
  return {
    category: '字段字典与表单',
    columns: ['id', 'code'],
    excludedRowCount: 0,
    mode: 'COPY',
    module: 'followup',
    present: true,
    rowCount: 10,
    sourceRowCount: 10,
    table: 'fe_field',
    ...overrides,
  };
}

function tenant(overrides: Partial<TenantListItem> = {}): TenantListItem {
  return {
    code: 'demob',
    createdAt: '2027-01-01T08:00:00+08:00',
    expireAt: null,
    expireType: 'LONG_TERM',
    id: 2,
    modules: [],
    name: '示例医院B',
    shortName: null,
    status: 'ENABLED',
    userCount: 5,
    viewEnterable: false,
    ...overrides,
  };
}

describe('e12-T04 导入前预览推演（验收：导入前预览差异）', () => {
  it('按导入口径推演单表动作：COPY 且 present → 导入；STRUCTURE_ONLY → 只复制结构；空节 → 跳过', () => {
    expect(importActionOf(section())).toBe('IMPORT');
    expect(
      importActionOf(section({ mode: 'STRUCTURE_ONLY', rowCount: 0 })),
    ).toBe('SKIP_STRUCTURE');
    expect(importActionOf(section({ present: false, rowCount: 0 }))).toBe(
      'SKIP_ABSENT',
    );
  });

  it('单表预览行：导入行说明行数与合并口径；跳过行给出原因', () => {
    const importRow = tablePreview(section({ rowCount: 42 }));
    expect(importRow.actionText).toBe(
      '将导入 42 行：与目标自然键相同的行被包值覆盖（合并），其余新增',
    );
    expect(importRow.tone).toBe('import');
    expect(importRow.modeText).toBe('复制');

    const structureRow = tablePreview(
      section({ mode: 'STRUCTURE_ONLY', table: 'ward', rowCount: 0 }),
    );
    expect(structureRow.tone).toBe('skip');
    expect(structureRow.actionText).toContain('只复制结构');
    expect(structureRow.modeText).toBe('只复制结构');

    const absentRow = tablePreview(
      section({ present: false, rowCount: 0, module: 'followup' }),
    );
    expect(absentRow.actionText).toContain('空节');
  });

  it('预览汇总：只统计会写入的表；全部不写入时结论另有文案', () => {
    const summary = previewSummary([
      section({ rowCount: 40 }),
      section({ rowCount: 2 }),
      section({ mode: 'STRUCTURE_ONLY', rowCount: 0 }),
      section({ present: false, rowCount: 0 }),
    ]);
    expect(summary).toEqual({ importRows: 42, importTables: 2, skipTables: 2 });
    expect(previewSummaryText(summary)).toBe(
      '将向目标租户写入 2 张配置表、共 42 行；其余 2 张表不写入（只复制结构或空节）。',
    );

    const empty = previewSummary([
      section({ mode: 'STRUCTURE_ONLY', rowCount: 0 }),
    ]);
    expect(previewSummaryText(empty)).toBe(
      '本包没有会写入目标租户的配置行（全部为只复制结构或空节）。',
    );
  });

  it('tablePreviews 按包内顺序逐表输出预览行', () => {
    const rows = tablePreviews([
      section({ table: 'a' }),
      section({ table: 'b', mode: 'STRUCTURE_ONLY', rowCount: 0 }),
    ]);
    expect(rows.map((row) => row.table)).toEqual(['a', 'b']);
    expect(rows.map((row) => row.tone)).toEqual(['import', 'skip']);
  });

  it('导入口径提示覆盖合并语义、整组替换、交集、置空与权限版本递增', () => {
    const text = IMPORT_RULE_NOTES.join('\n');
    expect(text).toContain('源值覆盖');
    expect(text).toContain('整组替换');
    expect(text).toContain('取交集');
    expect(text).toContain('置空');
    expect(text).toContain('权限版本递增');
    expect(text).toContain('以应用结果为准');
  });
});

describe('e12-T04 目标租户选项与确认校验', () => {
  const tenants = [
    tenant({ id: 1, code: 'demoa', name: '示例医院A' }),
    tenant({ id: 2 }),
    tenant({ id: 3, status: 'CANCELLING' }),
    tenant({ id: 4, status: 'CANCELLED' }),
  ];

  it('目标选项排除源租户；注销中 / 已注销置灰并给原因', () => {
    const options = targetTenantOptions(tenants, 1);
    expect(options.map((option) => option.value)).toEqual([2, 3, 4]);
    expect(options[0]).toEqual({
      disabled: false,
      disabledReason: '',
      label: '示例医院B（demob）',
      value: 2,
    });
    expect(options[1]?.disabled).toBe(true);
    expect(options[1]?.disabledReason).toBe('该租户已注销或注销中');
    expect(options[2]?.disabled).toBe(true);
  });

  it('应用前校验：未选租户、选了源租户、未勾选确认都要拦下', () => {
    expect(validateApplyModel(toApplyFormModel(), 1)).toEqual([
      '请选择目标租户',
      '请先阅读导入预览并勾选确认',
    ]);
    expect(validateApplyModel({ confirmed: true, tenantId: 1 }, 1)).toEqual([
      '目标租户不能是模板的源租户',
    ]);
    expect(validateApplyModel({ confirmed: true, tenantId: 2 }, 1)).toEqual([]);
  });

  it('应用结果汇总文案：四类计数齐全', () => {
    expect(
      applyResultSummaryText({
        droppedRows: 3,
        insertedRows: 100,
        mergedRows: 20,
        skippedRows: 5,
      }),
    ).toBe('已应用：新增 100 行、覆盖 20 行、跳过 5 行、丢弃 3 行。');
  });
});
