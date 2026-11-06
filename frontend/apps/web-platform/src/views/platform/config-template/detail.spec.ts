import type { ConfigTemplateView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  headerRows,
  packageFileBody,
  packageFileName,
  tableMetaRow,
  tableMetaRows,
} from './detail';

const template: ConfigTemplateView = {
  exportedAt: '2027-05-25T10:06:30+08:00',
  formatVersion: 1,
  id: 3,
  name: '示例医院 配置模板',
  packageHash: 'b'.repeat(64),
  rowCount: 120,
  tableCount: 14,
  tenantCode: 'demoa',
  tenantId: 1,
  tenantName: '示例医院',
  version: 2,
};

describe('e12-T04 配置模板详情逻辑', () => {
  it('头信息行：名称、源租户、版本、包结构版本、表数、行数、导出时间、完整哈希', () => {
    const rows = headerRows(template);
    expect(rows.map((row) => row.label)).toEqual([
      '模板名称',
      '源租户',
      '版本',
      '包结构版本',
      '配置表数',
      '入包行数',
      '导出时间',
      '包哈希（SHA-256）',
    ]);
    expect(rows[1]).toEqual({
      key: 'tenant',
      label: '源租户',
      value: '示例医院（demoa）',
    });
    expect(rows[6]?.value).toBe('2027-05-25 10:06');
    expect(rows[7]?.value).toBe('b'.repeat(64));
  });

  it('配置表节行：复制方式文案、空节标注、剔除行数（术者取值）随行数展示', () => {
    const copyRow = tableMetaRow({
      category: '字段字典与表单',
      columns: [],
      excludedRowCount: 0,
      mode: 'COPY',
      module: 'followup',
      present: true,
      rowCount: 40,
      sourceRowCount: 40,
      table: 'fe_field',
    });
    expect(copyRow.modeText).toBe('复制');
    expect(copyRow.presentText).toBe('正常');
    expect(copyRow.rowsText).toBe('40');

    const dictRow = tableMetaRow({
      category: '业务字典',
      columns: [],
      excludedRowCount: 3,
      mode: 'COPY',
      module: 'base',
      present: true,
      rowCount: 7,
      sourceRowCount: 10,
      table: 'cfg_dict_item',
    });
    expect(dictRow.rowsText).toBe('7（已剔除 3 行取值）');

    const wardRow = tableMetaRow({
      category: '术者、病区 / 中心',
      columns: [],
      excludedRowCount: 0,
      mode: 'STRUCTURE_ONLY',
      module: 'followup',
      present: true,
      rowCount: 0,
      sourceRowCount: 6,
      table: 'ward',
    });
    expect(wardRow.modeText).toBe('只复制结构');
    expect(wardRow.rowsText).toBe('0');

    const absentRow = tableMetaRow({
      category: '随访计划模板',
      columns: [],
      excludedRowCount: 0,
      mode: 'COPY',
      module: 'followup',
      present: false,
      rowCount: 0,
      sourceRowCount: 0,
      table: 'fu_plan',
    });
    expect(absentRow.presentText).toBe('源租户未开通该模块');
  });

  it('表格行按包内顺序输出', () => {
    const rows = tableMetaRows([
      {
        category: 'c',
        columns: [],
        excludedRowCount: 0,
        mode: 'COPY',
        module: 'base',
        present: true,
        rowCount: 1,
        sourceRowCount: 1,
        table: 'a',
      },
      {
        category: 'c',
        columns: [],
        excludedRowCount: 0,
        mode: 'COPY',
        module: 'base',
        present: true,
        rowCount: 2,
        sourceRowCount: 2,
        table: 'b',
      },
    ]);
    expect(rows.map((row) => row.table)).toEqual(['a', 'b']);
  });

  it('下载文件名与包内容：config-template-{租户编码}-v{版本}.json，内容缩进 2 格', () => {
    expect(packageFileName(template)).toBe('config-template-demoa-v2.json');
    const body = packageFileBody({
      exportedAt: '2027-05-25T10:06:30+08:00',
      formatVersion: 1,
      tables: [],
      tenantCode: 'demoa',
      tenantName: '示例医院',
    });
    expect(body).toContain('"formatVersion": 1');
    expect(body.endsWith('\n')).toBe(true);
  });
});
