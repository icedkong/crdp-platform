import type { DeptNodeView, DeptSaveRequest, OrgCampusNode } from '@crdp/api';

/**
 * E06-T02 / PLT-ORG-001、002：组织架构页的纯逻辑（树检索、拖拽排序提交集、表单校验）。
 * 与后端 `OrgTreeAssembler` 同一口径；界面层只做展示，不在这里发请求。
 */

export interface DeptLocation {
  campus: OrgCampusNode;
  dept: DeptNodeView;
  parent: DeptNodeView | null;
}

/** 在树中定位科室（含所在院区与父科室），找不到返回 null。 */
export function findDeptNode(
  orgs: OrgCampusNode[],
  deptId: number,
): DeptLocation | null {
  for (const campus of orgs) {
    const found = findInList(campus.depts, deptId, null);
    if (found) {
      return { campus, dept: found.node, parent: found.parent };
    }
  }
  return null;
}

/** 定位院区节点。 */
export function findCampus(
  orgs: OrgCampusNode[],
  orgId: number,
): null | OrgCampusNode {
  return orgs.find((campus) => campus.id === orgId) ?? null;
}

/** 面包屑路径：「示例院区 / 心内科 / CCU」。 */
export function deptPath(orgs: OrgCampusNode[], deptId: number): string {
  const found = findDeptNode(orgs, deptId);
  if (!found) return '';
  const segments = [found.campus.name];
  const walk = (nodes: DeptNodeView[], trail: string[]): null | string[] => {
    for (const node of nodes) {
      const next = [...trail, node.name];
      if (node.id === deptId) return next;
      const deeper = walk(node.children, next);
      if (deeper) return deeper;
    }
    return null;
  };
  const path = walk(found.campus.depts, []);
  return [...segments, ...(path ?? [])].join(' / ');
}

/** 本科室 + 全部下级（与后端 subtreeIds 同口径，用于拖拽防环的本地预判）。 */
export function deptSubtreeIds(dept: DeptNodeView): number[] {
  const ids: number[] = [];
  const walk = (node: DeptNodeView) => {
    ids.push(node.id);
    node.children.forEach((child) => walk(child));
  };
  walk(dept);
  return ids;
}

/** 拖拽目标是否是被拖科室自身或其下级（会成环，本地直接拦截）。 */
export function isSelfOrDescendant(
  dragged: DeptNodeView,
  targetId: number,
): boolean {
  return deptSubtreeIds(dragged).includes(targetId);
}

/**
 * 拖拽落点后的提交集合：对父级、院区或顺序发生变化的兄弟逐个生成整条更新
 * （后端 PUT /depts/{id} 是整对象更新，sortNo 按 10、20、30 重排）。
 */
export function sortChangePayloads(
  newSiblings: DeptNodeView[],
  parentId: number,
  orgId: number,
): Array<{ id: number; request: DeptSaveRequest }> {
  return newSiblings
    .map((node, index) => ({ node, sortNo: (index + 1) * 10 }))
    .filter(
      ({ node, sortNo }) =>
        node.parentId !== parentId ||
        node.sortNo !== sortNo ||
        node.orgId !== orgId,
    )
    .map(({ node, sortNo }) => ({
      id: node.id,
      request: {
        code: node.code,
        deptType: node.deptType,
        hisCode: node.hisCode,
        leaderUserId: node.leaderUserId,
        name: node.name,
        orgId,
        parentDeptId: parentId,
        sortNo,
      },
    }));
}

/** 拖拽落点（tree drop 事件解析结果）。 */
export type DropTarget =
  | { deptId: number; type: 'dept' }
  | { orgId: number; type: 'campus' };

/** 落点计划：新的父级与同级顺序（含被拖节点）。 */
export interface DropPlan {
  orgId: number;
  parentId: number;
  siblings: DeptNodeView[];
}

/**
 * 由拖拽信息计算落点计划（纯函数，不发请求）。
 * relative 取 AntD Tree 的 dropPosition − node 末段 pos：-1 落在前、0 落入（成为子级）、1 落在后。
 * 落入本科室的子树由调用方先用 `isSelfOrDescendant` 拦截。
 */
export function planDrop(
  orgs: OrgCampusNode[],
  dragDeptId: number,
  target: DropTarget,
  relative: -1 | 0 | 1,
): DropPlan | null {
  const dragged = findDeptNode(orgs, dragDeptId);
  if (!dragged) return null;
  if (target.type === 'campus') {
    const campusNode = findCampus(orgs, target.orgId);
    if (!campusNode) return null;
    return {
      orgId: campusNode.id,
      parentId: 0,
      siblings: [
        ...campusNode.depts.filter((dept) => dept.id !== dragDeptId),
        dragged.dept,
      ],
    };
  }
  if (target.deptId === dragDeptId) return null;
  if (isSelfOrDescendant(dragged.dept, target.deptId)) return null;
  const drop = findDeptNode(orgs, target.deptId);
  if (!drop) return null;
  if (relative === 0) {
    return {
      orgId: drop.campus.id,
      parentId: drop.dept.id,
      siblings: [
        ...drop.dept.children.filter((dept) => dept.id !== dragDeptId),
        dragged.dept,
      ],
    };
  }
  const siblings = (drop.parent?.children ?? drop.campus.depts).filter(
    (dept) => dept.id !== dragDeptId,
  );
  const index = siblings.findIndex((dept) => dept.id === drop.dept.id);
  if (index === -1) return null;
  siblings.splice(index + (relative === 1 ? 1 : 0), 0, dragged.dept);
  return { orgId: drop.campus.id, parentId: drop.parent?.id ?? 0, siblings };
}

/** 关键字过滤树：保留名称 / 编码命中或含命中后代的节点（大小写不敏感）。 */
export function filterCampus(
  orgs: OrgCampusNode[],
  keyword: string,
): OrgCampusNode[] {
  const text = keyword.trim().toLowerCase();
  if (!text) return orgs;
  const match = (dept: DeptNodeView): DeptNodeView | null => {
    const children = dept.children
      .map((child) => match(child))
      .filter((item): item is DeptNodeView => item !== null);
    const self =
      dept.name.toLowerCase().includes(text) ||
      dept.code.toLowerCase().includes(text);
    if (!self && children.length === 0) return null;
    return { ...dept, children };
  };
  return orgs
    .map((campus) => {
      const depts = campus.depts
        .map((root) => match(root))
        .filter((item): item is DeptNodeView => item !== null);
      const self =
        campus.name.toLowerCase().includes(text) ||
        campus.code.toLowerCase().includes(text);
      if (!self && depts.length === 0) return null;
      return { ...campus, depts };
    })
    .filter((campus): campus is OrgCampusNode => campus !== null);
}

/** 可挂新科室的院区（启用中的院区；停用院区不再扩展，与「停用后不进选择器」同一口径）。 */
export function selectableCampuses(orgs: OrgCampusNode[]): OrgCampusNode[] {
  return orgs.filter((campus) => campus.status === 'ENABLED');
}

/** 院区编码 / 科室编码校验：必填、≤64、只允许字母数字下划线短横线（与后端一致）。 */
export function validateCode(value: string): null | string {
  const code = value.trim();
  if (!code) return '编码必填';
  if (code.length > 64) return '编码过长（最多 64 个字符）';
  if (!/^[A-Za-z0-9_-]+$/.test(code))
    return '编码只允许字母、数字、下划线与短横线';
  return null;
}

/** 名称校验：必填、≤128。 */
export function validateName(value: string): null | string {
  const name = value.trim();
  if (!name) return '名称必填';
  if (name.length > 128) return '名称过长（最多 128 个字符）';
  return null;
}

/** 变更原因（停用 / 启用确认框，可选）：≤200。 */
export function validateReason(value: string): null | string {
  if (value.trim().length > 200) return '原因过长（最多 200 个字符）';
  return null;
}

function findInList(
  nodes: DeptNodeView[],
  deptId: number,
  parent: DeptNodeView | null,
): null | { node: DeptNodeView; parent: DeptNodeView | null } {
  for (const node of nodes) {
    if (node.id === deptId) return { node, parent };
    const deeper = findInList(node.children, deptId, node);
    if (deeper) return deeper;
  }
  return null;
}
