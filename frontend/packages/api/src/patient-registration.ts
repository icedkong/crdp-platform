/**
 * E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002：出院登记 / 患者登记与病案号查重的
 * 类型与纯逻辑（接口契约 docs/api/patient.md §2，后端 `com.crdp.followup.patient.api`）。
 *
 * 病案号本院唯一：查重接口实时提示（exists 按全院；范围内重复附脱敏摘要供跳转），
 * 登记接口重复返回 40902 阻止；姓名 + 出生日期相同为可能重复提示（C-PAT-002，不阻止）。
 */

import type { ImplantPayload } from './implant';

/** 已有患者脱敏摘要（后端只返回脱敏值，不暴露明文）。 */
export interface DuplicatePatientSummary {
  dischargeDate?: null | string;
  id: string;
  nameMasked: string;
  wardName?: null | string;
}

/** 查重结果：exists 按全院；patient 仅当已有患者在当前用户数据范围内。 */
export interface PatientDuplicateCheckResult {
  exists: boolean;
  patient?: DuplicatePatientSummary | null;
  possibleMatches?: DuplicatePatientSummary[];
}

/**
 * 登记表单（A 出院登记最小必填集；birthDate / deptId / legacyNo 可选；visitNo 为医院 C
 * 手填随访编号〔纸质原编号，可空 = 自动生成〕，C-PAT-003 / E21-T10）。
 */
export interface PatientRegistrationForm {
  admissionDate?: null | string;
  birthDate?: null | string;
  deptId?: null | number;
  dischargeDate?: null | string;
  dischargeStatus?: null | number | string;
  gender?: null | string;
  legacyNo?: null | string;
  medicalRecordNo?: null | string;
  name?: null | string;
  surgeryDate?: null | string;
  visitNo?: null | string;
  wardId?: null | number;
}

/**
 * 登记请求体（日期 YYYY-MM-DD，编号语义见 docs/api/patient.md §2）。
 * implant 为医院 C 首条植入记录（C-PAT-001：implant 开关开启时必填，OQ-71①；结构见 implant.ts）。
 */
export interface PatientRegistrationPayload {
  admissionDate?: null | string;
  birthDate?: null | string;
  deptId?: null | number;
  dischargeDate?: null | string;
  dischargeStatus?: null | number;
  gender?: null | string;
  implant?: ImplantPayload | null;
  legacyNo?: null | string;
  medicalRecordNo: string;
  name: string;
  surgeryDate?: null | string;
  visitNo?: null | string;
  wardId?: null | number;
}

/** 登记成功响应（id 为字符串，超过 2^53 的 ID 序列化为字符串口径；visitNo 为 C 随访编号，非敏感）。 */
export interface PatientCreatedResult {
  dischargeDate?: null | string;
  id: string;
  nameMasked: string;
  visitNo?: null | string;
  wardName?: null | string;
}

/** 登记候选：病区 / 科室（启用项），性别与出院状态为实体列编码。 */
export interface RegistrationOptionItem {
  code: string;
  label: string;
}

export interface RegistrationWardOption {
  id: number;
  name: string;
}

export interface PatientRegistrationOptions {
  depts: RegistrationWardOption[];
  dischargeStatuses: RegistrationOptionItem[];
  genders: RegistrationOptionItem[];
  visitNoRule?: null | VisitNoRule;
  wards: RegistrationWardOption[];
}

/**
 * 随访编号规则（E21-T10 / C-PAT-003）：prefix 为本院生效前缀（租户参数 followup.visitno.prefix
 * 覆盖 → 平台默认 → 出厂 105，OQ-131①），sample 为年内首号示例；implant 开关关闭（A / B）为 null。
 */
export interface VisitNoRule {
  prefix: string;
  sample: string;
}

/** 登记页随访编号帮助文案：留空自动生成（含示例）；历史患者可填纸质原编号。 */
export function visitNoHelpText(rule: null | undefined | VisitNoRule): string {
  if (!rule) return '随访编号';
  return `留空按「${rule.prefix}-建档年份-年内流水号」自动生成（如 ${rule.sample}）；历史患者可填纸质原编号，本院唯一`;
}

/** 手填随访编号校验（与后端 40001 同口径）：去空白后 ≤64 位；空白视为留空（自动生成）。 */
export function visitNoError(value: null | string | undefined): null | string {
  const trimmed = (value ?? '').trim();
  if (trimmed.length === 0) return null;
  if (trimmed.length > 64) return '随访编号过长（最多 64 个字符）';
  return null;
}

/** 出院状态口径（A 规格字段 191：1=存活 / 2=病重自动出院 / 3=死亡）。 */
export const DISCHARGE_STATUS_TEXT: Record<string, string> = {
  1: '存活',
  2: '病重自动出院',
  3: '死亡',
};

export function dischargeStatusText(
  code: null | number | string | undefined,
): string {
  if (code === null || code === undefined || code === '') return '';
  return DISCHARGE_STATUS_TEXT[String(code)] ?? String(code);
}

/** 病案号查重最小条件：去空白后非空（长度口径与后端一致，≤64）。 */
export function canCheckDuplicate(
  medicalRecordNo: null | string | undefined,
): boolean {
  const trimmed = (medicalRecordNo ?? '').trim();
  return trimmed.length > 0 && trimmed.length <= 64;
}

/** 查重请求体：姓名 + 出生日期成对出现才作为可能重复条件（C-PAT-002）。 */
export function buildDuplicateCheckPayload(form: {
  birthDate?: null | string;
  medicalRecordNo?: null | string;
  name?: null | string;
}): null | {
  birthDate?: string;
  medicalRecordNo: string;
  name?: string;
} {
  if (!canCheckDuplicate(form.medicalRecordNo)) return null;
  const payload: {
    birthDate?: string;
    medicalRecordNo: string;
    name?: string;
  } = { medicalRecordNo: (form.medicalRecordNo ?? '').trim() };
  const name = (form.name ?? '').trim();
  if (name && form.birthDate) {
    payload.name = name;
    payload.birthDate = form.birthDate;
  }
  return payload;
}

/** 校验错误：字段 → 提示（文案与后端 40001 口径一致，前端提前拦截）。 */
export type RegistrationErrors = Partial<
  Record<keyof PatientRegistrationForm, string>
>;

/** A-PAT-003 最小必填集与日期先后（入院 ≤ 手术 ≤ 出院）校验。 */
export function validateRegistration(
  form: PatientRegistrationForm,
): RegistrationErrors {
  const errors: RegistrationErrors = {};
  if (!canCheckDuplicate(form.medicalRecordNo))
    errors.medicalRecordNo = '请填写病案号';
  if (!(form.name ?? '').trim()) errors.name = '请填写姓名';
  if (!form.gender) errors.gender = '请选择性别';
  if (!form.wardId) errors.wardId = '请选择病区';
  if (!form.surgeryDate) errors.surgeryDate = '请填写手术日期';
  if (!form.admissionDate) errors.admissionDate = '请填写入院日期';
  if (!form.dischargeDate) errors.dischargeDate = '请填写出院日期';
  if (!form.dischargeStatus && form.dischargeStatus !== 0) {
    errors.dischargeStatus = '请选择出院状态';
  }
  if (
    form.surgeryDate &&
    form.admissionDate &&
    form.surgeryDate < form.admissionDate
  ) {
    errors.surgeryDate = '手术日期不能早于入院日期';
  }
  if (
    form.dischargeDate &&
    form.surgeryDate &&
    form.dischargeDate < form.surgeryDate
  ) {
    errors.dischargeDate = '出院日期不能早于手术日期';
  }
  return errors;
}

/** 登记请求体：编号去空白、出院状态转数值、空串归一为 null/缺省。 */
export function buildRegistrationPayload(
  form: PatientRegistrationForm,
): PatientRegistrationPayload {
  const dischargeStatus =
    form.dischargeStatus === null ||
    form.dischargeStatus === undefined ||
    form.dischargeStatus === ''
      ? null
      : Number(form.dischargeStatus);
  return {
    medicalRecordNo: (form.medicalRecordNo ?? '').trim(),
    name: (form.name ?? '').trim(),
    gender: form.gender ?? null,
    birthDate: form.birthDate || null,
    deptId: form.deptId ?? null,
    wardId: form.wardId ?? null,
    surgeryDate: form.surgeryDate || null,
    admissionDate: form.admissionDate || null,
    dischargeDate: form.dischargeDate || null,
    dischargeStatus,
    legacyNo: form.legacyNo?.trim() || null,
    visitNo: form.visitNo?.trim() || null,
  };
}

/** 查重提示（页面 Alert）：病案号重复 = 阻止（红色）；同名同生日 = 提示（橙色，不阻止）。 */
export type DuplicateNotice =
  | null
  | {
      kind: 'blocked';
      patient?: DuplicatePatientSummary | null;
      text: string;
    }
  | {
      kind: 'possible';
      matches: DuplicatePatientSummary[];
      patient?: undefined;

      text: string;
    };

export function duplicateNoticeOf(
  result: null | PatientDuplicateCheckResult | undefined,
): DuplicateNotice {
  if (!result) return null;
  if (result.exists) {
    return {
      kind: 'blocked',
      patient: result.patient ?? null,
      text: result.patient
        ? `该病案号已在本院登记：${result.patient.nameMasked}（${result.patient.wardName ?? '病区未知'}）。可打开已有患者档案，或核对病案号后重试。`
        : '该病案号已在本院登记，请核对后重试；如需查看已有患者，请联系有权限的同事。',
    };
  }
  const matches = result.possibleMatches ?? [];
  if (matches.length > 0) {
    return {
      kind: 'possible',
      matches,
      text: `姓名与出生日期相同的患者已有 ${matches.length} 名，请核对是否重复登记（不影响保存）。`,
    };
  }
  return null;
}
