import type { ConfigTemplateView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  exportedAtText,
  hashText,
  rowNumber,
  sourceTenantText,
  TEMPLATE_NAME_MAX,
  tenantOptions,
  toExportFormModel,
  toExportRequest,
  validateExportModel,
  versionText,
} from './list';

const template: ConfigTemplateView = {
  exportedAt: '2027-05-25T10:06:30+08:00',
  formatVersion: 1,
  id: 3,
  name: '示例医院 配置模板',
  packageHash: 'a'.repeat(64),
  rowCount: 120,
  tableCount: 14,
  tenantCode: 'demoa',
  tenantId: 1,
  tenantName: '示例医院',
  version: 2,
};

describe('e12-T04 配置模板列表逻辑', () => {
  it('源租户列：名称（编码）', () => {
    expect(sourceTenantText(template)).toBe('示例医院（demoa）');
  });

  it('版本列 v2、导出时间精确到分钟、哈希截断显示', () => {
    expect(versionText(template)).toBe('v2');
    expect(exportedAtText(template)).toBe('2027-05-25 10:06');
    expect(exportedAtText({ exportedAt: '' })).toBe('—');
    expect(hashText('a'.repeat(64))).toBe('aaaaaaaaaaaa…');
    expect(hashText('short')).toBe('short');
    expect(hashText('')).toBe('—');
  });

  it('序号跨页连续：第 2 页第 1 行是 21', () => {
    expect(rowNumber(0, 2, 20)).toBe(21);
    expect(rowNumber(4, 1, 20)).toBe(5);
  });

  it('源租户下拉选项：名称（编码）', () => {
    expect(
      tenantOptions([
        { code: 'demoa', id: 1, name: '示例医院', status: 'ENABLED' },
      ]),
    ).toEqual([{ label: '示例医院（demoa）', value: 1 }]);
  });
});

describe('e12-T04 导出表单', () => {
  it('未选源租户要拦下；名称超 128 字要拦下', () => {
    expect(validateExportModel(toExportFormModel())).toEqual(['请选择源租户']);
    expect(validateExportModel({ name: 'x'.repeat(129), tenantId: 1 })).toEqual(
      [`模板名称不能超过 ${TEMPLATE_NAME_MAX} 字`],
    );
    expect(validateExportModel({ name: '  ', tenantId: 1 })).toEqual([]);
  });

  it('导出请求体：名称去空格，空串不上送（后端补「{租户名} 配置模板」）', () => {
    expect(toExportRequest({ name: '  示例模板 ', tenantId: 1 })).toEqual({
      name: '示例模板',
      tenantId: 1,
    });
    expect(toExportRequest({ name: '   ', tenantId: 2 })).toEqual({
      tenantId: 2,
    });
  });
});
