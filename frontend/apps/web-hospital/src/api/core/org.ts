import type {
  DeptImpactView,
  DeptSaveRequest,
  DeptView,
  OrgImpactView,
  OrgSaveRequest,
  OrgStatusRequest,
  OrgTreeView,
  OrgView,
  UserOptionView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E06-T02 / PLT-ORG-001、002：组织架构（院区与科室树）接口。
 * 契约见 docs/api/system.md §1；查看用 tenant:org:view，维护用 tenant:org:edit。
 */

/** 组织树（含停用节点，维护视角要能看到并重新启用）。 */
export function orgTree() {
  return requestClient.get<OrgTreeView>(apiPath('/system/orgs/tree'));
}

/** 院区停用 / 启用前的影响面：科室数、启用科室数、成员数。 */
export function orgImpact(orgId: number) {
  return requestClient.get<OrgImpactView>(
    apiPath(`/system/orgs/${orgId}/impact`),
  );
}

/** 科室停用 / 启用前的影响面：成员数、下级科室数、启用下级数。 */
export function deptImpact(deptId: number) {
  return requestClient.get<DeptImpactView>(
    apiPath(`/system/depts/${deptId}/impact`),
  );
}

/** 新增院区。编码租户内唯一，重复返回 40902。 */
export function createOrg(data: OrgSaveRequest) {
  return requestClient.post<OrgView>(apiPath('/system/orgs'), data);
}

/** 编辑院区名称（编码创建后不可改）。 */
export function updateOrg(orgId: number, data: OrgSaveRequest) {
  return requestClient.put<OrgView>(apiPath(`/system/orgs/${orgId}`), data);
}

/** 院区停用 / 启用：有启用中的科室时 40901。 */
export function changeOrgStatus(orgId: number, data: OrgStatusRequest) {
  return requestClient.post<OrgView>(
    apiPath(`/system/orgs/${orgId}/status`),
    data,
  );
}

/** 新增科室（院区下多级；parentDeptId 省略或 0 = 院区顶层）。 */
export function createDept(data: DeptSaveRequest) {
  return requestClient.post<DeptView>(apiPath('/system/depts'), data);
}

/** 编辑 / 移动科室（名称、类型、负责人、HIS 编码、排序、上级）。 */
export function updateDept(deptId: number, data: DeptSaveRequest) {
  return requestClient.put<DeptView>(apiPath(`/system/depts/${deptId}`), data);
}

/** 科室停用 / 启用：有启用中的下级科室时 40901。 */
export function changeDeptStatus(deptId: number, data: OrgStatusRequest) {
  return requestClient.post<DeptView>(
    apiPath(`/system/depts/${deptId}/status`),
    data,
  );
}

/** 负责人候选（暂行：本院启用用户；PLT-ORG-012 共用选择器在 E06-T09 替换）。 */
export function userOptions(keyword: string) {
  return requestClient.get<UserOptionView[]>(
    apiPath('/system/orgs/user-options'),
    { params: keyword ? { keyword } : {} },
  );
}
