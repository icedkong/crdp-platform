import type { MenuResourceNode, MenuSyncDiffItem } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  applyResultText,
  diffLine,
  diffSummary,
  flattenMenuTree,
  newMenuModel,
  parentCandidates,
  PERM_CODE_PATTERN,
  toCreateRequest,
  toEditModel,
  validateCreate,
} from './resource';

function node(partial: Partial<MenuResourceNode>): MenuResourceNode {
  return {
    children: [],
    component: null,
    hidden: false,
    icon: null,
    id: partial.id ?? 1,
    moduleCode: partial.moduleCode ?? 'base',
    parentId: partial.parentId ?? 0,
    path: null,
    permCode: null,
    routeName: null,
    side: 'PLATFORM',
    sortNo: 0,
    source: 'MANIFEST',
    status: 'ENABLED',
    title: '演示',
    type: 'MENU',
    ...partial,
  };
}

describe('e05-T06 菜单资源页逻辑', () => {
  it('新建校验：按钮必填合规权限码（PLT-PERM-003）', () => {
    const model = newMenuModel('PLATFORM', ['base', 'followup']);
    model.type = 'BUTTON';
    model.title = '导出患者';
    expect(validateCreate(model)).toContain('按钮必须绑定权限码');
    model.permCode = 'platform_menu_edit';
    expect(validateCreate(model)).toContain(
      '权限码格式 {模块}:{资源}:{动作}，如 followup:task:view',
    );
    model.permCode = 'followup:patient:export';
    expect(validateCreate(model)).toEqual([]);
    expect(PERM_CODE_PATTERN.test('followup:patient:view')).toBe(true);
    expect(PERM_CODE_PATTERN.test('a:b')).toBe(false);
  });

  it('新建校验：菜单必填路由名 / 路径 / 组件（PLT-PERM-001）', () => {
    const model = newMenuModel('HOSPITAL', ['base', 'followup']);
    model.type = 'MENU';
    model.title = '患者档案';
    const errors = validateCreate(model);
    expect(errors).toContain(
      '目录 / 菜单必填路由名（大驼峰，如 FollowupPatient）',
    );
    expect(errors).toContain('目录 / 菜单必填以 / 开头的路径');
    expect(errors).toContain(
      '菜单必填前端视图路径（如 followup/patient/index）',
    );
    model.routeName = 'FollowupPatient';
    model.path = '/followup/patient';
    model.component = 'followup/patient/index';
    expect(validateCreate(model)).toEqual([]);
  });

  it('新建校验：必须归属模块（PLT-PERM-002）', () => {
    const model = newMenuModel('PLATFORM', ['base', 'followup']);
    model.type = 'DIR';
    model.title = '随访';
    model.routeName = 'Followup';
    model.path = '/followup';
    model.moduleCode = '';
    expect(validateCreate(model)).toContain(
      '所属模块必填（每个菜单与按钮必须归属一个模块）',
    );
  });

  it('提交请求按类型收窄：按钮不带路由字段，菜单不带权限码', () => {
    const model = newMenuModel('HOSPITAL', ['followup']);
    model.type = 'BUTTON';
    model.title = '查看明文';
    model.permCode = 'followup:patient:plain';
    model.path = '/不应提交';
    const request = toCreateRequest(model);
    expect(request.permCode).toBe('followup:patient:plain');
    expect(request.path).toBeNull();
    expect(request.routeName).toBeNull();
    expect(request.component).toBeNull();

    const menuModel = toEditModel(
      node({
        type: 'MENU',
        routeName: 'FollowupPatient',
        path: '/followup/patient',
      }),
    );
    menuModel.permCode = '不应生效';
    const menuRequest = toCreateRequest(menuModel);
    expect(menuRequest.permCode).toBeNull();
  });

  it('展平树与可选上级：目录 / 菜单挂目录下，按钮挂菜单下', () => {
    const tree = [
      node({
        id: 20,
        type: 'DIR',
        routeName: 'Followup',
        children: [
          node({
            id: 21,
            parentId: 20,
            type: 'MENU',
            routeName: 'FollowupPatient',
          }),
        ],
      }),
      node({ id: 30, type: 'DIR', routeName: 'HospitalSystem' }),
    ];
    expect(flattenMenuTree(tree)).toHaveLength(3);
    expect(parentCandidates(tree, 'DIR').map((item) => item.id)).toEqual([
      20, 30,
    ]);
    expect(parentCandidates(tree, 'MENU').map((item) => item.id)).toEqual([
      20, 30,
    ]);
    expect(parentCandidates(tree, 'BUTTON').map((item) => item.id)).toEqual([
      21,
    ]);
  });

  it('清单差异文案与摘要（PLT-PERM-011）', () => {
    const items: MenuSyncDiffItem[] = [
      {
        action: 'ADDED',
        current: null,
        incoming: { title: '随访日历' },
        key: 'FollowupCalendar',
        moduleCode: 'followup',
        side: 'HOSPITAL',
      },
      {
        action: 'OFFLINE',
        current: { title: '旧版报告单' },
        incoming: null,
        key: 'FollowupReportOld',
        moduleCode: 'followup',
        side: 'HOSPITAL',
      },
    ];
    expect(diffLine(items[0])).toBe(
      '新增 · 医院端 · FollowupCalendar：随访日历',
    );
    expect(diffLine(items[1])).toBe(
      '下线 · 医院端 · FollowupReportOld：旧版报告单',
    );
    expect(diffSummary(items)).toBe('新增 1 项、变更 0 项、下线 1 项');
    expect(applyResultText({ added: 1, changed: 0, offline: 1 })).toBe(
      '已应用：新增 1 项、变更 0 项、下线 1 项',
    );
  });
});
