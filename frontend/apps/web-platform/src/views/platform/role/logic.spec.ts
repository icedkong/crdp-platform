import type { PlatformGrantMenuNode, PlatformRoleView } from '@crdp/api';

/**
 * E06-T04 平台角色页纯逻辑测试：授权树组装（含孤儿挂根）、表单模型往返、校验口径
 * （名称 / 说明 / 视角字段枚举 / SPECIFIED 暂不可选）。
 */
import { describe, expect, it } from 'vitest';

import {
  buildGrantTree,
  roleTimeText,
  roleViewSummaryText,
  toRoleEditModel,
  toRoleSaveRequest,
  validateRoleModel,
} from './logic';

function grantNode(
  id: number,
  parentId: number,
  type: 'BUTTON' | 'DIR' | 'MENU' = 'MENU',
): PlatformGrantMenuNode {
  return {
    id,
    parentId,
    permCode: type === 'BUTTON' ? `base:x:${id}` : null,
    sortNo: id,
    title: `菜单${id}`,
    type,
  };
}

describe('buildGrantTree', () => {
  it('按 parentId 组树并把按钮标进标题', () => {
    const tree = buildGrantTree([
      grantNode(110, 0, 'DIR'),
      grantNode(111, 110),
      grantNode(1111, 111, 'BUTTON'),
    ]);
    expect(tree).toHaveLength(1);
    expect(tree[0]?.key).toBe(110);
    expect(tree[0]?.children[0]?.key).toBe(111);
    expect(tree[0]?.children[0]?.children[0]?.title).toContain('按钮');
  });

  it('父节点缺失的孤儿挂到根，授权项不丢', () => {
    const tree = buildGrantTree([grantNode(100, 0), grantNode(999, 12_345)]);
    expect(tree.map((node) => node.key)).toEqual([100, 999]);
  });

  it('空清单返回空树', () => {
    expect(buildGrantTree([])).toEqual([]);
  });
});

describe('toRoleEditModel / toRoleSaveRequest', () => {
  it('空模型给安全默认值（不可进入视角、仅配置类、负责的租户）', () => {
    const model = toRoleEditModel(null, []);
    expect(model.viewCanEnter).toBe(false);
    expect(model.viewVisibleScope).toBe('CONFIG_ONLY');
    expect(model.viewTenantScope).toBe('RESPONSIBLE');
    expect(model.status).toBe('ENABLED');
  });

  it('角色详情 → 模型 → 请求往返保真', () => {
    const role = {
      builtIn: true,
      code: 'PLATFORM_ADMIN',
      createdAt: '2026-11-01T08:00:00+08:00',
      description: '全部平台权限',
      holderCount: 1,
      id: 1,
      menuIds: [100, 110],
      name: '平台管理员',
      status: 'ENABLED',
      updatedAt: null,
      view: {
        canConfigure: true,
        canEditBusiness: true,
        canEnter: true,
        canResetPassword: true,
        tenantScope: 'ALL',
        visibleScope: 'CONFIG_AND_BUSINESS',
      },
    } as unknown as PlatformRoleView;
    const model = toRoleEditModel(role, [100, 110]);
    expect(model.menuIds).toEqual([100, 110]);
    const request = toRoleSaveRequest(model);
    expect(request).toEqual({
      description: '全部平台权限',
      menuIds: [100, 110],
      name: '平台管理员',
      status: 'ENABLED',
      viewCanConfigure: true,
      viewCanEditBusiness: true,
      viewCanEnter: true,
      viewCanResetPassword: true,
      viewTenantScope: 'ALL',
      viewVisibleScope: 'CONFIG_AND_BUSINESS',
    });
  });
});

describe('validateRoleModel', () => {
  it('名称必填与长度、说明长度', () => {
    expect(validateRoleModel(toRoleEditModel(null, []))).toContain(
      '角色名称必填',
    );
    const long: Parameters<typeof validateRoleModel>[0] = {
      ...toRoleEditModel(null, []),
      name: '角'.repeat(65),
    };
    expect(validateRoleModel(long)).toContain('角色名称过长（最多 64 个字符）');
    const longDesc: Parameters<typeof validateRoleModel>[0] = {
      ...toRoleEditModel(null, []),
      description: '长'.repeat(256),
      name: 'x',
    };
    expect(validateRoleModel(longDesc)).toContain(
      '角色说明过长（最多 255 个字符）',
    );
  });

  it('sPECIFIED 暂不可选（OQ-48：指定租户清单由 E20-T01 落地）', () => {
    const model: Parameters<typeof validateRoleModel>[0] = {
      ...toRoleEditModel(null, []),
      name: 'x',
      viewTenantScope: 'SPECIFIED',
    };
    const errors = validateRoleModel(model);
    expect(errors.join(',')).toContain('指定租户');
  });

  it('合法模型通过', () => {
    const model = toRoleEditModel(null, []);
    model.name = '巡检角色（示例）';
    expect(validateRoleModel(model)).toEqual([]);
  });
});

describe('文案', () => {
  it('视角权限摘要：不可进入 / 可进入的组合', () => {
    expect(
      roleViewSummaryText({
        builtIn: true,
        code: 'SUPPORT',
        createdAt: null,
        description: null,
        id: 4,
        name: '客服支持',
        status: 'ENABLED',
        updatedAt: null,
        view: {
          canConfigure: false,
          canEditBusiness: false,
          canEnter: false,
          canResetPassword: false,
          tenantScope: 'RESPONSIBLE',
          visibleScope: 'CONFIG_ONLY',
        },
      }),
    ).toBe('不可进入租户视角');

    expect(
      roleViewSummaryText({
        builtIn: true,
        code: 'SUPPORT',
        createdAt: null,
        description: null,
        id: 4,
        name: '客服支持',
        status: 'ENABLED',
        updatedAt: null,
        view: {
          canConfigure: false,
          canEditBusiness: false,
          canEnter: true,
          canResetPassword: true,
          tenantScope: 'RESPONSIBLE',
          visibleScope: 'CONFIG_AND_BUSINESS',
        },
      }),
    ).toBe('可进入 · 配置类 + 业务数据 · 负责的租户 · 可代重置密码');
  });

  it('时间列空值与分钟精度', () => {
    expect(roleTimeText(null)).toBe('—');
    expect(roleTimeText('2026-11-01T08:30:00+08:00')).toBe('2026-11-01 08:30');
  });
});
