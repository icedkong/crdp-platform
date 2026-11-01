/**
 * 授权范围页签逻辑单测（E05-T07）：树表行组装、新放开敏感项识别、
 * 变更 / 放开原因校验、影响分析文案。
 */
import type { TenantScopeItem, TenantScopeModule } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  flattenRows,
  newSensitiveReleases,
  previewLines,
  stateText,
  toScopeRows,
  validateReason,
  validateReleaseReason,
} from './scope';

function item(overrides: Partial<TenantScopeItem>): TenantScopeItem {
  return {
    assignable: true,
    defaultAssignable: true,
    id: 0,
    moduleCode: 'followup',
    fixed: false,
    parentId: 0,
    permCode: null,
    releaseReason: null,
    sensitive: false,
    setAt: null,
    state: 'ASSIGNABLE',
    title: '',
    type: 'MENU',
    ...overrides,
  };
}

const followupModule: TenantScopeModule = {
  baseModule: false,
  code: 'followup',
  items: [
    item({ id: 20, title: '随访', type: 'DIR' }),
    item({
      id: 21,
      title: '患者档案',
      parentId: 20,
      permCode: 'followup:patient:view',
    }),
    item({
      id: 211,
      title: '查看明文',
      parentId: 21,
      type: 'BUTTON',
      permCode: 'followup:patient:plain',
      sensitive: true,
      assignable: false,
      defaultAssignable: false,
      state: 'NOT_RELEASED',
    }),
  ],
  name: '随访',
  opened: true,
};

describe('toScopeRows / flattenRows', () => {
  it('按 parentId 组树表行并保留平铺还原', () => {
    const rows = toScopeRows(followupModule);
    const root = rows[0];
    const patient = root?.children?.[0];
    const plain = patient?.children?.[0];
    expect(rows).toHaveLength(1);
    expect(root?.children).toHaveLength(1);
    expect(plain?.title).toBe('查看明文');
    expect(root?.typeText).toBe('目录');
    expect(
      flattenRows(rows)
        .map((entry) => entry.id)
        .toSorted(),
    ).toEqual([20, 21, 211]);
  });

  it('三种状态的展示文案', () => {
    expect(stateText('ASSIGNABLE')).toBe('可分配');
    expect(stateText('NOT_RELEASED')).toBe('已开通未放开');
    expect(stateText('CLOSED')).toBe('未开通');
    expect(stateText('OTHER')).toBe('OTHER');
  });
});

describe('newSensitiveReleases', () => {
  it('识别本次新放开的敏感项（勾选但当前不可分配）', () => {
    const items = followupModule.items;
    expect(newSensitiveReleases(items, new Set([20, 21]))).toHaveLength(0);
    const releases = newSensitiveReleases(items, new Set([20, 21, 211]));
    expect(releases).toHaveLength(1);
    expect(releases[0]?.title).toBe('查看明文');
  });
});

describe('校验', () => {
  it('变更原因必填 ≤200 字', () => {
    expect(validateReason(' ')).toContain('必须填写');
    expect(validateReason('长'.repeat(201))).toContain('过长');
    expect(validateReason('按合同调整（示例）')).toBeUndefined();
  });

  it('敏感放开原因必填 ≤200 字', () => {
    const releases = newSensitiveReleases(
      followupModule.items,
      new Set([20, 21, 211]),
    );
    expect(validateReleaseReason({}, releases)[211]).toContain('查看明文');
    expect(validateReleaseReason({ 211: ' ' }, releases)[211]).toContain(
      '必须填写',
    );
    expect(
      validateReleaseReason({ 211: '科研协作（示例）' }, releases),
    ).toEqual({});
  });
});

describe('previewLines', () => {
  it('无收回时提示无需同步收回', () => {
    const lines = previewLines({
      added: [],
      affectedRoles: [],
      affectedUserCount: 0,
      removed: [],
      sensitiveReleases: [],
      tenantCode: 'demoa',
      tenantId: 1,
    });
    expect(lines[0]).toContain('不收回');
  });

  it('列出受影响角色、权限码与用户数，并区分敏感放开', () => {
    const lines = previewLines({
      added: [
        { menuId: 211, permCode: 'followup:patient:plain', title: '查看明文' },
        { menuId: 23, permCode: 'followup:report:view', title: '报告单' },
      ],
      affectedRoles: [
        {
          removedMenuCount: 1,
          removedPermCodes: ['followup:task:view'],
          roleCode: 'FOLLOWUP_DOCTOR',
          roleId: 2,
          roleName: '随访医生',
          userCount: 1,
        },
      ],
      affectedUserCount: 2,
      removed: [
        { menuId: 22, permCode: 'followup:task:view', title: '随访任务' },
      ],
      sensitiveReleases: [
        { menuId: 211, permCode: 'followup:patient:plain', title: '查看明文' },
      ],
      tenantCode: 'demoa',
      tenantId: 1,
    });
    expect(lines[0]).toContain('将收回：随访任务');
    expect(lines[1]).toContain('随访医生');
    expect(lines[1]).toContain('followup:task:view');
    expect(lines[2]).toContain('受影响用户：2');
    expect(lines[3]).toContain('将放开敏感权限：查看明文');
    expect(lines[4]).toContain('将放开其余项：报告单');
  });
});
