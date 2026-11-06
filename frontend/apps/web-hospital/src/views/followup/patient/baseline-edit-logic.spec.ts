import type { BaselineSection } from '@crdp/api';

// E13-T02 / A-PAT-003、A-PAT-006：基线分节编辑整页的纯逻辑（分节定位与保存文案）
import { describe, expect, it } from 'vitest';

import {
  BASELINE_CONFLICT_SAVE_TEXT,
  BASELINE_LOCKED_SAVE_TEXT,
  pickSection,
  sectionSaveSuccessText,
} from './baseline-edit-logic';

function section(code: string, overrides: Partial<BaselineSection> = {}) {
  return {
    code,
    data: {},
    hasDraft: false,
    id: '77',
    meta: {},
    status: 'EMPTY',
    updatedAt: null,
    updatedBy: null,
    version: 0,
    ...overrides,
  } satisfies BaselineSection;
}

describe('pickSection（?cat 定位，pages.md §4.3）', () => {
  const sections = [section('basic'), section('case'), section('echo')];

  it('?cat 指定且存在取该分节', () => {
    expect(pickSection(sections, 'case')?.code).toBe('case');
  });

  it('缺省或不存在回落第一个分节', () => {
    expect(pickSection(sections, undefined)?.code).toBe('basic');
    expect(pickSection(sections, 'missing')?.code).toBe('basic');
    expect(pickSection(sections, null)?.code).toBe('basic');
  });

  it('无分节返回 null（页面按 40400 处理）', () => {
    expect(pickSection([], 'case')).toBeNull();
  });
});

describe('sectionSaveSuccessText（A-PAT-005 留痕提示 + 软校验警告）', () => {
  it('常规保存提示留痕', () => {
    expect(sectionSaveSuccessText(0)).toBe(
      '分节已保存，字段修改已留痕（A-PAT-005）',
    );
  });

  it('带软校验警告时附加提示数（A-FLD-003 提示但可保存）', () => {
    expect(sectionSaveSuccessText(2)).toBe(
      '分节已保存，字段修改已留痕（A-PAT-005）；2 项软校验提示请留意',
    );
  });
});

describe('锁定与冲突文案（A-PAT-006 锁定后只读）', () => {
  it('锁定保存提示引导解锁', () => {
    expect(BASELINE_LOCKED_SAVE_TEXT).toContain('基线已锁定，不能保存');
  });

  it('保存冲突提示不做字段级合并（刷新后重新保存）', () => {
    expect(BASELINE_CONFLICT_SAVE_TEXT).toContain('已被他人更新');
  });
});
