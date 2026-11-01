import type { DeptNodeView, OrgCampusNode } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  deptPath,
  deptSubtreeIds,
  filterCampus,
  findCampus,
  findDeptNode,
  isSelfOrDescendant,
  planDrop,
  selectableCampuses,
  sortChangePayloads,
  validateCode,
  validateName,
  validateReason,
} from './org-tree';

/** 虚构示例树：示例院区一（心内科 → CCU / 超声室）、示例院区二（呼吸科）。 */
function campus(
  id: number,
  code: string,
  name: string,
  depts: DeptNodeView[],
  status = 'ENABLED',
): OrgCampusNode {
  return { code, depts, id, name, status: status as OrgCampusNode['status'] };
}

function dept(id: number, overrides: Partial<DeptNodeView> = {}): DeptNodeView {
  return {
    children: [],
    code: `D-${id}`,
    deptType: null,
    hisCode: null,
    id,
    leaderName: null,
    leaderUserId: null,
    name: `科室${id}`,
    orgId: 1,
    parentId: 0,
    sortNo: 10,
    status: 'ENABLED',
    ...overrides,
  };
}

const tree: OrgCampusNode[] = [
  campus(1, 'ORG-A', '示例院区一', [
    dept(11, {
      name: '示例心内科',
      code: 'D-110',
      children: [
        dept(12, { name: '示例CCU', parentId: 11, sortNo: 20 }),
        dept(13, {
          name: '示例超声室',
          parentId: 11,
          sortNo: 10,
          status: 'DISABLED',
        }),
      ],
    }),
    dept(14, { name: '示例呼吸科', sortNo: 20 }),
  ]),
  campus(
    2,
    'ORG-B',
    '示例院区二',
    [dept(21, { name: '示例康复科', orgId: 2 })],
    'DISABLED',
  ),
];

describe('org-tree 定位与路径', () => {
  it('按 id 定位科室并给出父科室与院区', () => {
    const found = findDeptNode(tree, 12);
    expect(found?.dept.name).toBe('示例CCU');
    expect(found?.parent?.name).toBe('示例心内科');
    expect(found?.campus.name).toBe('示例院区一');
    expect(findDeptNode(tree, 999)).toBeNull();
  });

  it('定位院区与面包屑路径', () => {
    expect(findCampus(tree, 2)?.code).toBe('ORG-B');
    expect(deptPath(tree, 12)).toBe('示例院区一 / 示例心内科 / 示例CCU');
    expect(deptPath(tree, 14)).toBe('示例院区一 / 示例呼吸科');
    expect(deptPath(tree, 999)).toBe('');
  });
});

describe('org-tree 拖拽防环', () => {
  it('子树包含自身与全部下级', () => {
    const cardiology = findDeptNode(tree, 11)?.dept;
    expect(cardiology).toBeTruthy();
    const ids = cardiology ? deptSubtreeIds(cardiology) : [];
    expect(ids.toSorted((a, b) => a - b)).toEqual([11, 12, 13]);
  });

  it('不能拖入自身或下级，可以拖到别的分支', () => {
    const cardiology = findDeptNode(tree, 11)?.dept;
    expect(cardiology).toBeTruthy();
    expect(isSelfOrDescendant(cardiology ?? dept(0), 11)).toBe(true);
    expect(isSelfOrDescendant(cardiology ?? dept(0), 12)).toBe(true);
    expect(isSelfOrDescendant(cardiology ?? dept(0), 14)).toBe(false);
  });
});

describe('org-tree 拖拽排序提交集', () => {
  it('只为父级、院区或顺序变化的兄弟生成整条更新，sortNo 按 10 进位', () => {
    // 落点：示例心内科(11) 下，顺序变为 [CCU, 超声室]（原 CCU.sortNo=20、超声=10）
    const ccu = { ...dept(12, { parentId: 11, sortNo: 20 }) };
    const ultrasound = {
      ...dept(13, { parentId: 11, sortNo: 10, status: 'DISABLED' as const }),
    };
    const payloads = sortChangePayloads([ccu, ultrasound], 11, 1);
    const [first, second] = payloads;
    expect(payloads).toHaveLength(2);
    expect(first?.id).toBe(12);
    expect(first?.request).toMatchObject({
      code: 'D-12',
      parentDeptId: 11,
      sortNo: 10,
    });
    expect(second?.id).toBe(13);
    expect(second?.request).toMatchObject({
      code: 'D-13',
      parentDeptId: 11,
      sortNo: 20,
    });
  });

  it('顺序与父级都没变的节点不提交', () => {
    const stable = dept(14, { parentId: 0, sortNo: 10 });
    expect(sortChangePayloads([stable], 0, 1)).toHaveLength(0);
  });
});

describe('org-tree 拖拽落点计划', () => {
  it('落入目标科室：成为其子级并排在末尾', () => {
    const plan = planDrop(tree, 14, { type: 'dept', deptId: 11 }, 0);
    expect(plan?.parentId).toBe(11);
    expect(plan?.siblings.map((node) => node.id)).toEqual([12, 13, 14]);
  });

  it('落在目标科室之后：插入同层对应位置', () => {
    const plan = planDrop(tree, 12, { type: 'dept', deptId: 13 }, 1);
    expect(plan?.parentId).toBe(11);
    expect(plan?.siblings.map((node) => node.id)).toEqual([13, 12]);
  });

  it('落在院区节点：成为院区顶层，末位插入', () => {
    const plan = planDrop(tree, 12, { type: 'campus', orgId: 2 }, 0);
    expect(plan?.parentId).toBe(0);
    expect(plan?.orgId).toBe(2);
    expect(plan?.siblings.map((node) => node.id)).toEqual([21, 12]);
  });

  it('拖入自身或下级、拖到自身、找不到目标都返回 null（不发请求）', () => {
    expect(planDrop(tree, 11, { type: 'dept', deptId: 12 }, 0)).toBeNull();
    expect(planDrop(tree, 11, { type: 'dept', deptId: 11 }, 1)).toBeNull();
    expect(planDrop(tree, 11, { type: 'dept', deptId: 999 }, 0)).toBeNull();
  });
});

describe('org-tree 关键字过滤', () => {
  it('命中科室保留其祖先链，剪掉无关分支', () => {
    const filtered = filterCampus(tree, 'ccu');
    expect(filtered).toHaveLength(1);
    const cardiology = filtered[0]?.depts[0];
    expect(filtered[0]?.depts).toHaveLength(1);
    expect(cardiology?.children.map((node) => node.name)).toEqual(['示例CCU']);
  });

  it('按编码过滤（大小写不敏感）并保留命中院区', () => {
    expect(filterCampus(tree, 'org-b').map((node) => node.name)).toEqual([
      '示例院区二',
    ]);
    expect(filterCampus(tree, 'd-14')[0]?.depts[0]?.name).toBe('示例呼吸科');
  });

  it('空关键字原样返回', () => {
    expect(filterCampus(tree, '  ')).toBe(tree);
  });
});

describe('org-tree 院区选择与表单校验', () => {
  it('只有启用中的院区可挂新科室', () => {
    expect(selectableCampuses(tree).map((node) => node.id)).toEqual([1]);
  });

  it('编码校验与后端同规则', () => {
    expect(validateCode('')).toBe('编码必填');
    expect(validateCode('含有 空格')).toContain('只允许');
    expect(validateCode('x'.repeat(65))).toContain('64');
    expect(validateCode(' IT-D-001 ')).toBeNull();
  });

  it('名称必填且限长，原因可选但限长', () => {
    expect(validateName(' ')).toBe('名称必填');
    expect(validateName('示例科室')).toBeNull();
    expect(validateName('x'.repeat(129))).toContain('128');
    expect(validateReason('x'.repeat(201))).toContain('200');
    expect(validateReason('调整示例')).toBeNull();
  });
});
