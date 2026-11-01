/**
 * E07-T05 / A-PAT-010、C-PAT-012：患者敏感字段明文查看的类型与纯逻辑
 * （接口契约 docs/api/patient.md §5，后端 `com.crdp.followup.patient.api`）。
 *
 * 默认脱敏：列表 / 详情的敏感字段由后端按字段策略序列化输出（掩码 / 整字段隐藏）；
 * 明文只经 `POST /api/hospital/followup/patients/{id}/plaintext`（权限
 * `followup:patient:plain` + popconfirm 二次确认 + 每次写审计，60 秒自动恢复脱敏）。
 */

/** 敏感字段目录码（后端 SensitiveFieldCatalog 患者身份五位字段）。 */
export type PatientPlainField =
  | 'address'
  | 'idCard'
  | 'medicalRecordNo'
  | 'name'
  | 'phone';

/** 目录字段与展示名（components.md §3 MaskedField 确认文案同口径）。 */
export const PATIENT_PLAIN_FIELDS: ReadonlyArray<{
  field: PatientPlainField;
  label: string;
}> = [
  { field: 'name', label: '姓名' },
  { field: 'idCard', label: '证件号' },
  { field: 'phone', label: '手机号' },
  { field: 'medicalRecordNo', label: '病案号（病历号）' },
  { field: 'address', label: '住址' },
];

/**
 * MaskedField 的 field 键别名（components.md §3：'name' | 'mrn' | 'phone' | 'idNo' | string）
 * 到接口目录码的映射；目录码本身原样通过。
 */
const FIELD_ALIASES: Readonly<Record<string, PatientPlainField>> = {
  idNo: 'idCard',
  mrn: 'medicalRecordNo',
};

/** 场景：detail = 患者档案（PatientHeader），form = 表单内只读显示。 */
export type PatientPlaintextScene = 'detail' | 'form';

export interface PatientPlaintextRequest {
  fields: PatientPlainField[];
  scene: PatientPlaintextScene;
}

/** 只含请求到的字段；expiresInSeconds 前端据此定时恢复脱敏（60 秒）。 */
export interface PatientPlaintextResult {
  expiresInSeconds: number;
  values: Partial<Record<PatientPlainField, string>>;
}

/** 把组件字段键归一成接口目录码；不在目录返回 null（调用方不发起请求）。 */
export function normalizePatientPlainField(
  key: string,
): null | PatientPlainField {
  const direct = PATIENT_PLAIN_FIELDS.some((item) => item.field === key);
  if (direct) {
    return key as PatientPlainField;
  }
  return FIELD_ALIASES[key] ?? null;
}

/** 场景归一：空 → detail；非法值 → null（不发起请求）。 */
export function normalizePatientPlainScene(
  scene: null | string | undefined,
): null | PatientPlaintextScene {
  if (scene === null || scene === undefined || scene === '') {
    return 'detail';
  }
  return scene === 'detail' || scene === 'form' ? scene : null;
}

/** 组装请求体；字段或场景非法返回 null（组件层按「无入口 / 不请求」处理）。 */
export function buildPlaintextRevealRequest(
  fieldKey: string,
  scene: null | string | undefined = 'detail',
): null | PatientPlaintextRequest {
  const field = normalizePatientPlainField(fieldKey);
  const normalizedScene = normalizePatientPlainScene(scene);
  if (!field || !normalizedScene) {
    return null;
  }
  return { fields: [field], scene: normalizedScene };
}
