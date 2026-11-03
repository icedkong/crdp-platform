/**
 * E13-T08 / A-WARD-001、A-WARD-005、A-WARD-008：病区 / 中心字典与患者转病区的
 * 类型与纯逻辑（接口契约 docs/api/system.md §1、docs/api/patient.md §4，后端
 * `com.crdp.followup.ward.api` / `com.crdp.followup.patient.api.PatientTransferController`）。
 *
 * 病区是独立字典（不挂科室下，医院已确认）；编码本院唯一、创建后不可改；
 * 病区下仍有在册患者时不能停用（40901，须先转出）。转病区只改患者归属列——
 * 原病区医生立即失去访问权由后端数据范围承担（AGENTS.md §5.2），前端在成功
 * 后刷新列表 / 离开患者页即可感知。
 */

/** 病区类型（A-WARD-001 关键字段：病区 / 中心；C-WARD-001 同一字典）。 */
export type WardType = 'CENTER' | 'WARD';

export const WARD_TYPE_TEXT: Record<WardType, string> = {
  WARD: '病区',
  CENTER: '中心',
};

export function wardTypeText(type: null | string | undefined): string {
  if (type === null || type === undefined || type === '') return '';
  return WARD_TYPE_TEXT[type as WardType] ?? String(type);
}

export const WARD_STATUS_TEXT: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
};

/** 病区行（列表 / 回显；id / defaultOwnerId 为字符串，超过 2^53 的 ID 序列化为字符串口径）。 */
export interface WardView {
  aliases: string[];
  code: string;
  defaultOwnerId?: null | string;
  defaultOwnerName?: null | string;
  id: string;
  memberCount: number;
  name: string;
  patientCount: number;
  sortNo: number;
  status: string;
  wardType: WardType;
}

export interface WardCreateRequest {
  aliases?: null | string[];
  code: string;
  defaultOwnerId?: null | number | string;
  name: string;
  sortNo?: null | number;
  wardType: WardType;
}

export type WardUpdateRequest = WardCreateRequest;

export interface WardStatusRequest {
  status: 'DISABLED' | 'ENABLED';
}

export interface WardMemberView {
  displayName: string;
  id: string;
  username: string;
}

export interface WardMembersView {
  members: WardMemberView[];
  patientCount: number;
  wardId: string;
}

/** 转病区请求（A-WARD-005：目标病区 + 原因必填）。 */
export interface PatientTransferPayload {
  reason: string;
  wardId: number;
}

export interface PatientTransferredResult {
  patientId: string;
  wardId: string;
  wardName: string;
}

/** 转病区历史条目（GET ward-history，新在前）。 */
export interface PatientWardHistoryItem {
  fromWardName?: null | string;
  id: string;
  operatorName?: null | string;
  reason: string;
  toWardName?: null | string;
  transferredAt: string;
}

export interface PatientWardHistory {
  items: PatientWardHistoryItem[];
  patientId: string;
}

// ---------------------------------------------------------------- 病区编辑表单逻辑

/** 病区编辑表单（页面状态；别名为多值清单）。 */
export interface WardFormState {
  aliases: string;
  code: string;
  defaultOwnerId: null | number | string;
  name: string;
  sortNo: null | number;
  wardType: '' | WardType;
}

export function emptyWardForm(): WardFormState {
  return {
    code: '',
    name: '',
    wardType: 'WARD',
    aliases: '',
    defaultOwnerId: null,
    sortNo: 0,
  };
}

export function wardFormOf(ward: WardView): WardFormState {
  // defaultOwnerId 后端为字符串（2^53 口径）：回显归一为数值供 Select 匹配
  const owner =
    ward.defaultOwnerId === null || ward.defaultOwnerId === undefined
      ? null
      : Number(ward.defaultOwnerId);
  return {
    code: ward.code,
    name: ward.name,
    wardType: ward.wardType,
    aliases: ward.aliases.join(';'),
    defaultOwnerId: owner,
    sortNo: ward.sortNo,
  };
}

/** 校验错误：字段 → 提示（文案与后端 40001 口径一致，前端提前拦截）。 */
export type WardFormErrors = Partial<Record<keyof WardFormState, string>>;

const CODE_PATTERN = /^[A-Za-z0-9_-]+$/;

/** 别名输入（; 或 ；分隔）拆为去空白清单；空项剔除。 */
export function parseAliasInput(input: null | string | undefined): string[] {
  return (input ?? '')
    .split(/[;；]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function validateWardForm(
  form: WardFormState,
  options: { codeLocked: boolean },
): WardFormErrors {
  const errors: WardFormErrors = {};
  const code = form.code.trim();
  if (!code) errors.code = '请填写病区编码';
  else if (code.length > 64) errors.code = '病区编码最多 64 个字符';
  else if (!options.codeLocked && !CODE_PATTERN.test(code))
    errors.code = '病区编码只支持字母、数字、短横线与下划线';
  if (!form.name.trim()) errors.name = '请填写病区名称';
  else if (form.name.trim().length > 128)
    errors.name = '病区名称最多 128 个字符';
  if (!form.wardType) errors.wardType = '请选择类型';
  const aliases = parseAliasInput(form.aliases);
  if (aliases.some((item) => item.length > 64))
    errors.aliases = '别名单项最多 64 个字符';
  else if (aliases.join(';').length > 255)
    errors.aliases = '别名总长最多 255 个字符';
  const sortNo = form.sortNo ?? 0;
  if (sortNo < 0 || sortNo > 9999 || !Number.isInteger(sortNo))
    errors.sortNo = '排序取值 0–9999 的整数';
  return errors;
}

export function buildWardPayload(form: WardFormState): WardCreateRequest {
  const owner =
    form.defaultOwnerId === null || form.defaultOwnerId === ''
      ? null
      : Number(form.defaultOwnerId);
  return {
    code: form.code.trim(),
    name: form.name.trim(),
    wardType: (form.wardType || 'WARD') as WardType,
    aliases: parseAliasInput(form.aliases),
    defaultOwnerId: owner,
    sortNo: form.sortNo ?? 0,
  };
}

/** 停用拦截提示（A-WARD-008：后端 40901 时前端按患者数给出下一步指引）。 */
export function wardDisableBlockedText(ward: WardView): string {
  return `该病区下仍有 ${ward.patientCount} 名在册患者，不能停用；请先为这些患者转病区后再停用`;
}

// ---------------------------------------------------------------- 转病区表单逻辑

/** 转病区表单（弹窗状态；目标病区 + 原因）。 */
export interface TransferWardFormState {
  reason: string;
  wardId: null | number;
}

export function emptyTransferForm(): TransferWardFormState {
  return { wardId: null, reason: '' };
}

export function validateTransferForm(
  form: TransferWardFormState,
): Partial<Record<keyof TransferWardFormState, string>> {
  const errors: Partial<Record<keyof TransferWardFormState, string>> = {};
  if (form.wardId === null || form.wardId === undefined)
    errors.wardId = '请选择目标病区';
  if (!form.reason.trim()) errors.reason = '请填写转病区原因';
  else if (form.reason.trim().length > 255)
    errors.reason = '转病区原因最多 255 个字符';
  return errors;
}

export function buildTransferPayload(
  form: TransferWardFormState,
): null | PatientTransferPayload {
  if (form.wardId === null || form.wardId === undefined) return null;
  const reason = form.reason.trim();
  if (!reason || reason.length > 255) return null;
  return { wardId: form.wardId, reason };
}

/** 转病区目标候选：启用病区且排除当前病区（已在的病区无需转入）。 */
export function transferTargetOptions(
  wards: Array<{ id: number; name: string }>,
  currentWardId: null | number | undefined,
): Array<{ label: string; value: number }> {
  return wards
    .filter((ward) => ward.id !== currentWardId)
    .map((ward) => ({ label: ward.name, value: ward.id }));
}
