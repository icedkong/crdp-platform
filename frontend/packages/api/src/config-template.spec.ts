import type { ConfigTableMeta, ConfigTemplateView } from './config-template';

import { describe, expect, it } from 'vitest';

import { CONFIG_COPY_MODE_TEXT, configModeText } from './config-template';

describe('e12-T04 配置模板类型与展示口径', () => {
  it('复制方式文案：COPY / STRUCTURE_ONLY 两档，未知值原样返回', () => {
    expect(CONFIG_COPY_MODE_TEXT.COPY).toBe('复制');
    expect(CONFIG_COPY_MODE_TEXT.STRUCTURE_ONLY).toBe('只复制结构');
    expect(configModeText('COPY')).toBe('复制');
    expect(configModeText('STRUCTURE_ONLY')).toBe('只复制结构');
    expect(configModeText('WHATEVER')).toBe('WHATEVER');
    expect(configModeText(null)).toBe('—');
    expect(configModeText(undefined)).toBe('—');
  });

  it('类型结构按 docs/api/platform.md §7.1：模板头、表节元信息字段齐备（编译期契约）', () => {
    const template: ConfigTemplateView = {
      exportedAt: '2027-05-25T10:00:00+08:00',
      formatVersion: 1,
      id: 3,
      name: '示例医院 配置模板',
      packageHash:
        '0000000000000000000000000000000000000000000000000000000000000000',
      rowCount: 120,
      tableCount: 14,
      tenantCode: 'demoa',
      tenantId: 1,
      tenantName: '示例医院',
      version: 2,
    };
    const meta: ConfigTableMeta = {
      category: '字段字典与表单',
      columns: ['id', 'code', 'name'],
      excludedRowCount: 0,
      mode: 'COPY',
      module: 'followup',
      present: true,
      rowCount: 40,
      sourceRowCount: 40,
      table: 'fe_field',
    };
    expect(template.name).toBe('示例医院 配置模板');
    expect(meta.present).toBe(true);
    expect(configModeText(meta.mode)).toBe('复制');
  });
});
