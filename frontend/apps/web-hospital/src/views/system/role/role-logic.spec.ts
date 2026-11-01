import type {
  RoleScopeType,
  TenantRoleMenuNode,
  TenantRoleView,
} from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  checkedMenuIds,
  copyNameSuggestion,
  deptTreeOptions,
  filterRoles,
  isCustomRole,
  isScopeTypeRequiresDept,
  menuNodeTitle,
  menuTreeData,
  validateReason,
  validateRoleCode,
  validateRoleDescription,
  validateRoleName,
  withAncestorDirs,
} from './role-logic';

function role(overrides: Partial<TenantRoleView>): TenantRoleView {
  return {
    code: 'FOLLOWUP_NURSE',
    description: '模板说明（示例）',
    id: 11,
    menuCount: 4,
    memberCount: 0,
    moduleCode: 'followup',
    name: '随访员',
    roleType: 'TEMPLATE',
    status: 'ENABLED',
    ...overrides,
  };
}

function menuNode(overrides: Partial<TenantRoleMenuNode>): TenantRoleMenuNode {
  return {
    assignable: true,
    children: [],
    id: 20,
    moduleCode: 'followup',
    parentId: 0,
    permCode: null,
    title: '随访',
    type: 'DIR',
    ...overrides,
  };
}

describe('e06-T05 角色表单校验（与后端 40001 口径一致）', () => {
  it('校验编码必填、长度与字符集', () => {
    expect(validateRoleCode(' ward_nurse ')).toBeNull();
    expect(validateRoleCode('')).toBe('角色编码必填');
    expect(validateRoleCode('  ')).toBe('角色编码必填');
    expect(validateRoleCode('随访员')).toBe(
      '角色编码只允许字母、数字、下划线与短横线',
    );
    expect(validateRoleCode('a'.repeat(65))).toBe(
      '角色编码过长（最多 64 个字符）',
    );
  });

  it('校验名称与说明长度', () => {
    expect(validateRoleName(' 病区随访护士 ')).toBeNull();
    expect(validateRoleName('')).toBe('角色名称必填');
    expect(validateRoleName('长'.repeat(65))).toBe(
      '角色名称过长（最多 64 个字符）',
    );
    expect(validateRoleDescription('说明（示例）')).toBeNull();
    expect(validateRoleDescription('长'.repeat(201))).toBe(
      '角色说明过长（最多 200 个字符）',
    );
  });

  it('变更原因可空但有长度上限', () => {
    expect(validateReason('')).toBeNull();
    expect(validateReason('科室合并（示例）')).toBeNull();
    expect(validateReason('长'.repeat(201))).toBe(
      '原因过长（最多 200 个字符）',
    );
  });
});

describe('e06-T05 列表过滤与复制命名', () => {
  const roles = [
    role({}),
    role({
      code: 'TENANT_ADMIN',
      id: 5,
      moduleCode: null,
      name: '租户管理员',
      roleType: 'BUILT_IN',
    }),
    role({
      code: 'IT-NURSE',
      description: 'IT 用例角色（示例）',
      id: 31,
      moduleCode: null,
      name: '自定义随访护士',
      roleType: 'CUSTOM',
    }),
  ];

  it('按类型与关键字过滤（编码 / 名称 / 说明）', () => {
    expect(
      filterRoles(roles, { keyword: '', type: 'TEMPLATE' }).map(
        (item) => item.code,
      ),
    ).toEqual(['FOLLOWUP_NURSE']);
    expect(
      filterRoles(roles, { keyword: 'admin', type: 'ALL' }).map(
        (item) => item.code,
      ),
    ).toEqual(['TENANT_ADMIN']);
    expect(
      filterRoles(roles, { keyword: 'IT 用例', type: 'ALL' }).map(
        (item) => item.code,
      ),
    ).toEqual(['IT-NURSE']);
    expect(filterRoles(roles, { keyword: '', type: 'ALL' })).toHaveLength(3);
  });

  it('复制默认命名：模板沿用名称，自定义加（副本）', () => {
    const template = roles.find((item) => item.roleType === 'TEMPLATE');
    const custom = roles.find((item) => item.roleType === 'CUSTOM');
    if (!template || !custom) throw new Error('测试夹具缺失');
    expect(copyNameSuggestion(template)).toBe('随访员');
    expect(copyNameSuggestion(custom)).toBe('自定义随访护士（副本）');
  });

  it('只有自定义角色可编辑配置', () => {
    const builtIn = roles.find((item) => item.roleType === 'BUILT_IN');
    const custom = roles.find((item) => item.roleType === 'CUSTOM');
    if (!builtIn || !custom) throw new Error('测试夹具缺失');
    expect(isCustomRole(builtIn)).toBe(false);
    expect(isCustomRole(custom)).toBe(true);
  });
});

describe('e06-T05 菜单树与数据范围', () => {
  const menus = [
    menuNode({
      children: [
        menuNode({
          id: 21,
          parentId: 20,
          permCode: 'followup:patient:view',
          title: '患者档案',
          type: 'MENU',
        }),
        menuNode({
          assignable: false,
          id: 211,
          parentId: 20,
          permCode: 'followup:patient:plain',
          title: '查看明文',
          type: 'BUTTON',
        }),
      ],
      id: 20,
    }),
  ];

  it('勾选态只保留可分配项（敏感按钮即使误存也不提交）', () => {
    expect(checkedMenuIds(menus, [20, 21, 211])).toEqual([20, 21]);
    expect(checkedMenuIds(menus, [211])).toEqual([]);
    expect(checkedMenuIds(menus, [])).toEqual([]);
  });

  it('树数据带不可分配标记，按钮节点附权限码', () => {
    const [root] = menuTreeData(menus);
    expect(root?.children[0]?.title).toContain('followup:patient:view');
    expect(root?.children[1]?.title).toContain('不可分配');
    expect(root?.children[1]?.assignable).toBe(false);
  });

  it('保存前补齐可分配的祖先目录（半选父目录不在勾选态里，但菜单树按 parent 挂载）', () => {
    // 顺序按「勾选项 → 祖先」的访问序（后端按集合处理）
    expect(withAncestorDirs(menus, [21])).toEqual([21, 20]);
    // 不可分配的祖先不补（平台收回的目录不越范围提交）
    const restricted = [
      menuNode({
        assignable: false,
        children: [menuNode({ id: 21, parentId: 20 })],
      }),
    ];
    expect(withAncestorDirs(restricted, [21])).toEqual([21]);
    expect(withAncestorDirs(menus, [])).toEqual([]);
  });

  it('节点标题：可分配不带标记', () => {
    const root = menus.find((node) => node.id === 20);
    if (!root) throw new Error('测试夹具缺失');
    expect(menuNodeTitle(root)).toBe('随访');
  });

  it('只有自定义科室需要勾选科室清单', () => {
    const types: RoleScopeType[] = [
      'ALL',
      'CUSTOM_DEPT',
      'DEPT',
      'RESPONSIBLE_DEPT',
      'RESPONSIBLE_PATIENT',
      'SELF',
      'WARD',
    ];
    expect(types.filter((type) => isScopeTypeRequiresDept(type))).toEqual([
      'CUSTOM_DEPT',
    ]);
  });

  it('组织树转科室选择数据：院区为分组节点（负数 key），科室为可选项', () => {
    const data = deptTreeOptions([
      {
        code: 'ORG-DEMO',
        depts: [
          {
            children: [
              {
                children: [],
                code: 'DEPT-CCU',
                deptType: null,
                hisCode: null,
                id: 12,
                leaderName: null,
                leaderUserId: null,
                name: 'CCU',
                orgId: 1,
                parentId: 11,
                sortNo: 10,
                status: 'ENABLED',
              },
            ],
            code: 'DEPT-CARDIO',
            deptType: null,
            hisCode: null,
            id: 11,
            leaderName: null,
            leaderUserId: null,
            name: '虚构心内科',
            orgId: 1,
            parentId: 0,
            sortNo: 10,
            status: 'ENABLED',
          },
        ],
        id: 1,
        name: '虚构院区',
        status: 'ENABLED',
      },
    ]);
    const [campus] = data;
    expect(campus?.value).toBe(-1);
    expect(campus?.children[0]?.value).toBe(11);
    expect(campus?.children[0]?.children[0]?.title).toBe('CCU');
  });
});
