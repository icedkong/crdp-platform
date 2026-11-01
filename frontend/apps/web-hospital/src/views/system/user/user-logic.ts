import type {
  DeptNodeView,
  OrgCampusNode,
  TenantUserDetailView,
  TenantUserView,
  WardOptionView,
} from '@crdp/api';

/**
 * E06-T07 用户管理页的纯逻辑（校验、装配），供 index.vue 与单测共用；
 * 校验口径与后端 UserAccountAdminService 的 40001 预检保持一致。
 */

/** 账号：必填、≤64、字母数字下划线短横线与点（演示账号 nurse.demo 同字符集），创建后不可改。 */
export function validateUsername(username: string): null | string {
  const value = username.trim();
  if (value === '') return '账号必填';
  if (value.length > 64) return '账号过长（最多 64 个字符）';
  if (!/^[A-Za-z0-9._-]+$/.test(value)) {
    return '账号只允许字母、数字、下划线、短横线与点';
  }
  return null;
}

/** 姓名：必填、≤64。 */
export function validateDisplayName(name: string): null | string {
  const value = name.trim();
  if (value === '') return '姓名必填';
  if (value.length > 64) return '姓名过长（最多 64 个字符）';
  return null;
}

/** 工号：可空、≤64。 */
export function validateStaffNo(staffNo: string): null | string {
  const value = staffNo.trim();
  if (value.length > 64) return '工号过长（最多 64 个字符）';
  return null;
}

/** 手机号：新建必填；编辑留空＝保持不变。 */
export function validatePhone(phone: string, required: boolean): null | string {
  const value = phone.trim();
  if (value === '') {
    return required ? '手机号必填' : null;
  }
  if (!/^1\d{10}$/.test(value)) return '手机号格式不正确（11 位，1 开头）';
  return null;
}

/** 主科室必填、至少一个角色（platform-spec §6 关键字段·用户与新建用户流程）。 */
export function validateAssignments(
  mainDeptId: null | number | undefined,
  roleIds: number[],
): null | string {
  if (mainDeptId === null || mainDeptId === undefined || mainDeptId <= 0) {
    return '主科室必填';
  }
  if (roleIds.length === 0) return '至少分配一个角色';
  return null;
}

/** 变更原因可空、≤200（与角色 / 组织状态变更同口径）。 */
export function validateReason(reason: string): null | string {
  const value = reason.trim();
  if (value.length > 200) return '变更原因过长（最多 200 个字符）';
  return null;
}

/** 列表关键字过滤已由服务端完成，这里只做展示层的科室名回落。 */
export function mainDeptText(user: TenantUserView): string {
  return user.mainDeptName ?? '—';
}

/** 病区多选候选（启用病区；label 附加编码便于区分同名病区）。 */
export function wardSelectOptions(wards: WardOptionView[]): Array<{
  label: string;
  value: number;
}> {
  return wards.map((ward) => ({
    label: `${ward.name}（${ward.code}）`,
    value: ward.id,
  }));
}

/** 组织树 → 主科室（单选）与负责科室（多选）的 TreeSelect 数据：院区为不可选分组节点。 */
export interface DeptTreeOption {
  children?: DeptTreeOption[];
  disabled?: boolean;
  selectable?: boolean;
  title: string;
  value: number;
}

export function deptTreeOptions(
  orgs: OrgCampusNode[],
  options?: { onlyEnabled?: boolean },
): DeptTreeOption[] {
  const onlyEnabled = options?.onlyEnabled ?? false;
  return orgs.map((org) => ({
    children: deptOptions(org.depts, onlyEnabled),
    selectable: false,
    title: org.name,
    value: -org.id,
  }));
}

function deptOptions(
  depts: DeptNodeView[],
  onlyEnabled: boolean,
): DeptTreeOption[] {
  return depts
    .filter((dept) => !onlyEnabled || dept.status === 'ENABLED')
    .map((dept) => ({
      children: deptOptions(dept.children, onlyEnabled),
      title: dept.status === 'ENABLED' ? dept.name : `${dept.name}（停用）`,
      value: dept.id,
    }));
}

/** 编辑回显：后端详情 → 表单模型（手机号只显示脱敏值，留空提交＝保持不变）。 */
export interface UserFormModel {
  displayName: string;
  deptScopeIds: number[];
  mainDeptId: null | number;
  phone: string;
  roleIds: number[];
  staffNo: string;
  username: string;
  wardIds: number[];
}

export function toFormModel(detail: TenantUserDetailView): UserFormModel {
  return {
    displayName: detail.displayName,
    deptScopeIds: detail.deptScopeIds,
    mainDeptId: detail.mainDeptId,
    phone: '',
    roleIds: detail.roleIds,
    staffNo: detail.staffNo ?? '',
    username: detail.username,
    wardIds: detail.wardIds,
  };
}

/** 表单校验汇总：第一个错误即返回（弹窗逐字段展示）。 */
export function validateForm(
  form: UserFormModel,
  options: { creating: boolean },
): Record<'displayName' | 'phone' | 'staffNo' | 'username', string> {
  const errors = {
    displayName: '',
    phone: '',
    staffNo: '',
    username: '',
  } as Record<'displayName' | 'phone' | 'staffNo' | 'username', string>;
  if (options.creating) {
    errors.username = validateUsername(form.username) ?? '';
  }
  errors.displayName = validateDisplayName(form.displayName) ?? '';
  errors.staffNo = validateStaffNo(form.staffNo) ?? '';
  errors.phone = validatePhone(form.phone, options.creating) ?? '';
  return errors;
}

/** 负责范围变更的提示文案（保存后立即生效，platform-spec §4）。 */
export function responsibleScopeHint(
  wardCount: number,
  deptCount: number,
): string {
  return `已选负责病区 ${wardCount} 个、负责科室 ${deptCount} 个；保存后立即生效（在线会话的下一次请求按新范围计算）。`;
}
