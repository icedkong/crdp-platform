/**
 * 授权范围模板页逻辑单测（E05-T07 / PLT-SCOPE-003、004）：树表行、勾选模型、
 * 提交载荷与敏感清单校验。
 */
import type {
  ScopeTemplateModule,
  ScopeTemplateView,
  SensitivePermItem,
} from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  initialChecked,
  toSensitiveRows,
  toTemplateRows,
  toUpdateRequest,
  validateSensitiveRows,
} from './logic';

const followupModule: ScopeTemplateModule = {
  baseModule: false,
  code: 'followup',
  items: [
    {
      inTemplate: true,
      menuId: 20,
      parentId: 0,
      permCode: null,
      sensitive: false,
      title: '随访',
    },
    {
      inTemplate: true,
      menuId: 21,
      parentId: 20,
      permCode: 'followup:patient:view',
      sensitive: false,
      title: '患者档案',
    },
    {
      inTemplate: false,
      menuId: 211,
      parentId: 21,
      permCode: 'followup:patient:plain',
      sensitive: true,
      title: '查看明文',
    },
  ],
  name: '随访',
};

const view: ScopeTemplateView = {
  modules: [followupModule],
  sensitivePerms: [
    { id: 1, name: '敏感字段明文查看', permCode: 'followup:patient:plain' },
  ] satisfies SensitivePermItem[],
};

describe('toTemplateRows', () => {
  it('按 parentId 组树表行；敏感行勾选禁用', () => {
    const rows = toTemplateRows(followupModule);
    const root = rows[0];
    const patient = root?.children?.[0];
    const plain = patient?.children?.[0];
    expect(rows).toHaveLength(1);
    expect(patient?.title).toBe('患者档案');
    expect(plain?.disabled).toBe(true);
    expect(root?.disabled).toBe(false);
  });
});

describe('勾选模型', () => {
  it('初始勾选 = inTemplate；提交载荷只送勾选 id 且升序', () => {
    const state = initialChecked(view);
    expect(state.followup?.[20]).toBe(true);
    expect(state.followup?.[211]).toBe(false);
    expect(
      toUpdateRequest({ 22: true, 20: true, 211: false } as Record<
        string,
        boolean
      >),
    ).toEqual({ menuIds: [20, 22] });
  });
});

describe('敏感清单', () => {
  it('清单 → 编辑行', () => {
    expect(toSensitiveRows(view.sensitivePerms)).toEqual([
      { name: '敏感字段明文查看', permCode: 'followup:patient:plain' },
    ]);
  });

  it('校验权限码格式、必填与重复', () => {
    expect(
      validateSensitiveRows([
        { name: '明文查看', permCode: 'followup:patient:plain' },
      ]),
    ).toEqual({});
    expect(
      validateSensitiveRows([
        { name: '格式错误', permCode: 'patient-plain' },
      ])[0],
    ).toContain('权限码');
    expect(
      validateSensitiveRows([{ name: ' ', permCode: 'rct:blind:unblind' }])[0],
    ).toContain('说明必填');
    expect(
      validateSensitiveRows([
        { name: '甲', permCode: 'rct:blind:unblind' },
        { name: '乙', permCode: 'rct:blind:unblind' },
      ])[1],
    ).toContain('重复');
  });
});
