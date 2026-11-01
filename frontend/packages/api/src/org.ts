/**
 * E06-T02 / PLT-ORG-001、002：组织架构（院区与科室树）类型，与后端
 * `com.crdp.system.org.OrgAdminService` 视图同构（docs/api/system.md §1）。
 */

/** 院区 / 科室状态。停用节点保留在维护树中，但不出现在业务选择器里（平台规格业务规则）。 */
export type OrgNodeStatus = 'DISABLED' | 'ENABLED';

/** 科室节点（多级）。children 按 sortNo 升序、id 升序稳定排序。 */
export interface DeptNodeView {
  children: DeptNodeView[];
  code: string;
  /** 科室类型（规格为字典：临床 / 医技 / 职能 / 随访中心等；E06-T10 字典落地前为字符串）。 */
  deptType: null | string;
  hisCode: null | string;
  id: number;
  leaderName: null | string;
  leaderUserId: null | number;
  name: string;
  orgId: number;
  parentId: number;
  sortNo: number;
  status: OrgNodeStatus;
}

/** 院区节点：depts 为该院区顶层科室（院区 → 科室 → 下级科室）。 */
export interface OrgCampusNode {
  code: string;
  depts: DeptNodeView[];
  id: number;
  name: string;
  status: OrgNodeStatus;
}

/** 组织树。singleOrg=true 表示启用院区 ≤ 1，页面可隐藏院区层级（PLT-ORG-001）。 */
export interface OrgTreeView {
  orgs: OrgCampusNode[];
  singleOrg: boolean;
}

/** 院区保存：编码创建后不可改，更新时原样回传做一致性校验。 */
export interface OrgSaveRequest {
  code: string;
  name: string;
}

export interface OrgView {
  code: string;
  id: number;
  name: string;
  status: OrgNodeStatus;
}

/** 科室保存：parentDeptId 省略或 0 = 院区顶层；更新时可整体移动（跨科室 / 院区顶层）。 */
export interface DeptSaveRequest {
  code: string;
  deptType?: null | string;
  hisCode?: null | string;
  leaderUserId?: null | number;
  name: string;
  orgId: number;
  parentDeptId?: number;
  sortNo?: number;
}

export interface DeptView {
  code: string;
  deptType: null | string;
  hisCode: null | string;
  id: number;
  leaderName: null | string;
  leaderUserId: null | number;
  name: string;
  orgId: number;
  parentId: number;
  sortNo: number;
  status: OrgNodeStatus;
}

/** 停用 / 启用前的影响面（平台规格「停用前返回影响」）。患者数随患者表（E08+）落地补充。 */
export interface OrgImpactView {
  code: string;
  deptCount: number;
  enabledDeptCount: number;
  id: number;
  memberCount: number;
  name: string;
}

export interface DeptImpactView {
  childCount: number;
  code: string;
  enabledChildCount: number;
  id: number;
  memberCount: number;
  name: string;
}

/** 状态变更：ENABLE / DISABLE；reason 可选（≤200），写审计。 */
export interface OrgStatusRequest {
  action: 'DISABLE' | 'ENABLE';
  reason?: string;
}

/** 科室类型（暂行固定项，取自 platform-spec「临床 / 医技 / 职能 / 随访中心等」；字典随 E06-T10 落地后切换）。 */
export const DEPT_TYPE_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '临床科室', value: '临床' },
  { label: '医技科室', value: '医技' },
  { label: '职能部门', value: '职能' },
  { label: '随访中心', value: '随访中心' },
];

/** 院区 / 科室状态 → 中文（StatusTag 文案来源，components.md §2；tokens statusTone 已有「启用 / 停用」）。 */
export const ORG_STATUS_TEXT: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
};

export function orgStatusText(status: string): string {
  return ORG_STATUS_TEXT[status] ?? status;
}
