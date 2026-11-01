import type { MenuPreviewNode, UserOptionView } from './tenant-user';

import { describe, expect, it } from 'vitest';

import {
  flattenMenuPreview,
  scopeSummary,
  userOptionLabel,
} from './tenant-user';

/**
 * E06-T09 / PLT-ORG-009、012：权限预览与用户选择器的纯逻辑（文案与树摊平）。
 */

function node(partial: Partial<MenuPreviewNode>): MenuPreviewNode {
  return {
    children: [],
    id: 1,
    moduleCode: 'followup',
    parentId: 0,
    permCode: null,
    sourceRoles: [],
    title: '菜单',
    type: 'MENU',
    ...partial,
  };
}

describe('userOptionLabel（PLT-ORG-012 用户选择器文案）', () => {
  it('有科室时显示 姓名（账号 · 科室）', () => {
    const option: UserOptionView = {
      deptName: '虚构心内科',
      displayName: '张医生（示例）',
      id: 1,
      username: 'zhang.demo',
    };
    expect(userOptionLabel(option)).toBe(
      '张医生（示例）（zhang.demo · 虚构心内科）',
    );
  });

  it('无科室时省略科室段', () => {
    const option: UserOptionView = {
      deptName: null,
      displayName: '李护士（示例）',
      id: 2,
      username: 'li.demo',
    };
    expect(userOptionLabel(option)).toBe('李护士（示例）（li.demo）');
  });
});

describe('scopeSummary（数据范围摘要文案）', () => {
  it('全院（租户管理员）显示全院（全部病区）', () => {
    expect(
      scopeSummary({ scopeAll: true, scopeTypes: [{ type: 'ALL' }] }),
    ).toBe('全院（全部病区）');
  });

  it('受限范围按类型中文名拼接（多角色并集）', () => {
    expect(
      scopeSummary({
        scopeAll: false,
        scopeTypes: [{ type: 'WARD' }, { type: 'RESPONSIBLE_PATIENT' }],
      }),
    ).toBe('本人负责病区、仅本人负责的患者');
  });

  it('完全没有范围类型时给出空范围提示（数据范围默认拒绝的排查入口）', () => {
    expect(scopeSummary({ scopeAll: false, scopeTypes: [] })).toBe(
      '暂无数据范围（受控数据将不可见）',
    );
  });
});

describe('flattenMenuPreview（菜单树摊平）', () => {
  const tree: MenuPreviewNode[] = [
    node({
      children: [
        node({
          children: [
            node({
              id: 211,
              parentId: 21,
              permCode: 'followup:patient:plain',
              sourceRoles: ['租户管理员'],
              title: '查看明文',
              type: 'BUTTON',
            }),
          ],
          id: 21,
          parentId: 20,
          permCode: 'followup:patient:view',
          sourceRoles: ['随访医生'],
          title: '患者档案',
        }),
      ],
      id: 20,
      title: '随访',
      type: 'DIR',
    }),
  ];

  it('按深度优先摊平并标注按钮', () => {
    const rows = flattenMenuPreview(tree);
    expect(rows.map((row) => row.title)).toEqual([
      '随访',
      '患者档案',
      '查看明文（按钮）',
    ]);
    expect(rows.map((row) => row.indent)).toEqual([0, 1, 2]);
    expect(rows[2]?.node.permCode).toBe('followup:patient:plain');
    expect(rows[1]?.node.sourceRoles).toEqual(['随访医生']);
  });

  it('空树摊平为空数组', () => {
    expect(flattenMenuPreview([])).toEqual([]);
  });
});
