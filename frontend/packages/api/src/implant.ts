/**
 * E21-T01 / C-PAT-004、C-PAT-005：植入记录（一对多）与电极记录的类型与纯逻辑
 * （接口契约 docs/api/implant.md §1，后端 `com.crdp.followup.implant.api`）。
 *
 * 植入记录与电极的显示名由后端随行下发（编码 + 显示名一并返回，页面不硬编码文案）；
 * 新建 / 编辑表单的选项清单来自 GET /implants/options（暂行预置，业务字典值随 E21-T04）。
 */

/** 电极行（挂在植入记录下；RA / RV / LV 腔室，部位按腔室过滤）。 */
export interface ImplantLeadView {
  chamber: string;
  chamberLabel: null | string;
  id: string;
  implantDate?: null | string;
  model?: null | string;
  position?: null | string;
  positionLabel?: null | string;
  status: string;
  statusLabel: null | string;
  version: number;
}

/** 植入记录行：implantNo 按植入日期升序（附录 A 植入 #1）；current = 是否当前器械（在用）。 */
export interface ImplantRecordView {
  brand?: null | string;
  brandLabel?: null | string;
  current: boolean;
  deviceType: string;
  deviceTypeLabel: null | string;
  endDate?: null | string;
  endReason?: null | string;
  hospital?: null | string;
  id: string;
  implantDate: string;
  implantNo: number;
  implantType: string;
  implantTypeLabel: null | string;
  indication?: null | string;
  indicationLabel?: null | string;
  indicationNote?: null | string;
  leads: ImplantLeadView[];
  model?: null | string;
  pacingMode?: null | string;
  pacingModeLabel?: null | string;
  patientId: string;
  serialNo?: null | string;
  status: string;
  statusLabel: null | string;
  version: number;
}

/** GET /patients/{id}/implants 响应：当前器械置顶，其余按植入日期新在前。 */
export interface ImplantListResult {
  items: ImplantRecordView[];
}

/** 选项（编码 + 显示名）。 */
export interface ImplantOptionItem {
  code: string;
  label: string;
}

/** 电极部位按腔室分组（Q15 按腔室过滤）。 */
export interface ImplantLeadPositionGroup {
  chamber: string;
  positions: ImplantOptionItem[];
}

/** GET /implants/options 响应。 */
export interface ImplantOptions {
  brands: ImplantOptionItem[];
  deviceTypes: ImplantOptionItem[];
  implantTypes: ImplantOptionItem[];
  indications: ImplantOptionItem[];
  leadPositions: ImplantLeadPositionGroup[];
  leadStatuses: ImplantOptionItem[];
  pacingModes: ImplantOptionItem[];
}

/** 电极表单行（编辑时可带 id 复用既有电极行）。 */
export interface ImplantLeadForm {
  chamber?: null | string;
  id?: null | string;
  implantDate?: null | string;
  model?: null | string;
  position?: null | string;
  status?: null | string;
}

/** 植入记录表单（新建 / 编辑共用形态；状态由系统维护）。 */
export interface ImplantForm {
  brand?: null | string;
  deviceType?: null | string;
  doctor?: null | string;
  hospital?: null | string;
  implantDate?: null | string;
  implantType?: null | string;
  indication?: null | string;
  indicationNote?: null | string;
  leads?: ImplantLeadForm[] | null;
  model?: null | string;
  pacingMode?: null | string;
  serialNo?: null | string;
}

/** 新建请求体（与 ImplantForm 同构；日期 YYYY-MM-DD）。 */
export type ImplantPayload = ImplantForm;

/** 编辑请求体：baseVersion 乐观锁；leads 为 null 表示本次不动电极。 */
export interface ImplantUpdatePayload {
  baseVersion: number;
  implant: ImplantPayload;
}

/** 植入指征选「其他」须填补充说明（Q16；编码与后端 ImplantCodes 对齐）。 */
export const IMPLANT_INDICATION_OTHER = 'OTHER';

/** 无导线起搏器编码（不录电极，C-PAT-005；编码与后端 ImplantCodes 对齐）。 */
export const IMPLANT_DEVICE_LEADLESS = 'LEADLESS';

/** 电极状态缺省在用（编码与后端对齐）。 */
export const IMPLANT_LEAD_STATUS_ACTIVE = 'ACTIVE';

/** 该器械类型是否允许录电极（无导线起搏器不录电极，C-PAT-005）。 */
export function leadsAllowedForDevice(
  deviceType: null | string | undefined,
): boolean {
  return !!deviceType && deviceType !== IMPLANT_DEVICE_LEADLESS;
}

/** 某腔室的可选部位（Q15 按腔室过滤；未知腔室返回空）。 */
export function leadPositionOptions(
  options: ImplantOptions | null | undefined,
  chamber: null | string | undefined,
): ImplantOptionItem[] {
  if (!chamber) return [];
  return (
    options?.leadPositions.find((group) => group.chamber === chamber)
      ?.positions ?? []
  );
}

/** 电极行显示文案（表格 / 卡片共用）：部位（腔室 · 型号）。 */
export function leadSummary(lead: ImplantLeadView): string {
  const parts = [lead.positionLabel || lead.chamberLabel || lead.chamber];
  if (lead.model) parts.push(lead.model);
  return parts.join(' · ');
}

/** 表单校验错误（键 = 字段；leads 用 leads.N.field 定位行内错误）。 */
export type ImplantFormErrors = Record<string, string>;

/** 校验日期非空且不晚于今天（历史建档允许过去日期，C-PAT-008）。 */
function requirePastOrTodayDate(
  value: null | string | undefined,
  field: string,
  errors: ImplantFormErrors,
): boolean {
  if (!value) {
    errors[field] = '必填';
    return false;
  }
  if (value > new Date().toISOString().slice(0, 10)) {
    errors[field] = '不能晚于今天';
    return false;
  }
  return true;
}

/**
 * 植入记录表单校验（C-PAT-004 / C-PAT-005 口径，与后端 ImplantService 同向）：
 * 植入类型 / 植入日期 / 起搏器类型必填；植入日期不能晚于今天；无导线起搏器不录电极；
 * 指征选「其他」须填说明；电极腔室必填、部位按腔室取值。
 */
export function validateImplantForm(
  form: ImplantForm | null | undefined,
  options?: ImplantOptions | null,
): ImplantFormErrors {
  const errors: ImplantFormErrors = {};
  if (!form) return { implantType: '必填' };
  if (!form.implantType) errors.implantType = '必填';
  requirePastOrTodayDate(form.implantDate, 'implantDate', errors);
  if (!form.deviceType) errors.deviceType = '必填';
  if (
    form.indication === IMPLANT_INDICATION_OTHER &&
    !form.indicationNote?.trim()
  ) {
    errors.indicationNote = '指征选「其他」时须填写补充说明';
  }
  const leads = form.leads ?? [];
  const leadless = form.deviceType === IMPLANT_DEVICE_LEADLESS;
  if (leadless && leads.length > 0) {
    errors.leads = '无导线起搏器不录电极（C-PAT-005）';
    return errors;
  }
  const validChambers = new Set(
    options?.leadPositions.map((group) => group.chamber) ?? ['RA', 'RV', 'LV'],
  );
  leads.forEach((lead, index) => {
    const prefix = `leads.${index}`;
    if (!lead.chamber || !validChambers.has(lead.chamber)) {
      errors[`${prefix}.chamber`] = '必选（RA / RV / LV）';
      return;
    }
    if (lead.position) {
      const allowed = leadPositionOptions(options, lead.chamber).map(
        (item) => item.code,
      );
      if (allowed.length > 0 && !allowed.includes(lead.position)) {
        errors[`${prefix}.position`] = `不属于${lead.chamber}的可选部位`;
      }
    }
    if (
      lead.implantDate &&
      lead.implantDate > new Date().toISOString().slice(0, 10)
    ) {
      errors[`${prefix}.implantDate`] = '不能晚于今天';
    }
  });
  return errors;
}

/** 组装新建请求体（电极植入日期缺省同植入记录日期，附录 A 电极 #5）。 */
export function buildImplantPayload(form: ImplantForm): ImplantPayload {
  return {
    ...form,
    implantDate: form.implantDate || null,
    leads: (form.leads ?? []).map((lead) => ({
      ...lead,
      implantDate: lead.implantDate || form.implantDate || null,
      status: lead.status || IMPLANT_LEAD_STATUS_ACTIVE,
    })),
  };
}

/** 编辑请求体（带 baseVersion；不提供 leads 时后端不动电极）。 */
export function buildImplantUpdatePayload(
  baseVersion: number,
  form: ImplantForm,
): ImplantUpdatePayload {
  return { baseVersion, implant: buildImplantPayload(form) };
}

/** 新建表单缺省值（植入类型缺省首次植入；电极清空）。 */
export function emptyImplantForm(): ImplantForm {
  return {
    implantType: 'FIRST',
    implantDate: null,
    deviceType: null,
    brand: null,
    model: null,
    serialNo: null,
    pacingMode: null,
    hospital: null,
    doctor: null,
    indication: null,
    indicationNote: null,
    leads: [],
  };
}

/** 编辑表单初值（既有行 → 表单形态；电极行带 id 与版本随行携带）。 */
export function implantFormOf(record: ImplantRecordView): ImplantForm {
  return {
    implantType: record.implantType,
    implantDate: record.implantDate,
    deviceType: record.deviceType,
    brand: record.brand ?? null,
    model: record.model ?? null,
    serialNo: record.serialNo ?? null,
    pacingMode: record.pacingMode ?? null,
    hospital: record.hospital ?? null,
    doctor: record.doctor ?? null,
    indication: record.indication ?? null,
    indicationNote: record.indicationNote ?? null,
    leads: record.leads.map((lead) => ({
      id: lead.id,
      chamber: lead.chamber,
      position: lead.position ?? null,
      model: lead.model ?? null,
      implantDate: lead.implantDate ?? null,
      status: lead.status,
    })),
  };
}

/**
 * C 登记校验（C-PAT-001 最小必填，OQ-71①）：姓名、性别、出生日期、病历号、科室、病区 +
 * 首条植入记录（植入表单校验复用 {@link validateImplantForm}）；错误键带 `patient.` /
 * `implant.` 前缀供页面分区展示。
 */
export function validateCRegistration(
  patient: {
    birthDate?: null | string;
    deptId?: null | number;
    gender?: null | string;
    medicalRecordNo?: null | string;
    name?: null | string;
    wardId?: null | number;
  },
  implant: ImplantForm | null | undefined,
  options?: ImplantOptions | null,
): ImplantFormErrors {
  const errors: ImplantFormErrors = {};
  if (!patient.medicalRecordNo?.trim())
    errors['patient.medicalRecordNo'] = '必填';
  if (!patient.name?.trim()) errors['patient.name'] = '必填';
  if (!patient.gender) errors['patient.gender'] = '必选';
  if (!patient.birthDate) errors['patient.birthDate'] = '必填';
  if (!patient.deptId) errors['patient.deptId'] = '必选';
  if (!patient.wardId) errors['patient.wardId'] = '必选';
  if (!implant) {
    errors['patient.implant'] = '请同时填写首条植入记录（C-PAT-001）';
    return errors;
  }
  for (const [key, value] of Object.entries(
    validateImplantForm(implant, options),
  )) {
    errors[`implant.${key}`] = value;
  }
  return errors;
}

/** 手机卡片行（pages.md §4.10：植入记录与电极表格在手机转卡片）。 */
export function implantCardLines(record: ImplantRecordView): string[][] {
  const lines: string[][] = [
    ['植入类型', record.implantTypeLabel || record.implantType],
    [
      '器械',
      `${record.deviceTypeLabel || record.deviceType}${record.brandLabel ? ` · ${record.brandLabel}` : ''}`,
    ],
    ['植入日期', record.implantDate],
    ['状态', record.statusLabel || record.status],
  ];
  if (record.serialNo) lines.push(['序列号', record.serialNo]);
  if (record.pacingModeLabel) lines.push(['起搏模式', record.pacingModeLabel]);
  if (record.hospital) lines.push(['植入医院', record.hospital]);
  if (record.doctor) lines.push(['植入医生', record.doctor]);
  for (const lead of record.leads) {
    lines.push([
      `电极 ${lead.chamber}`,
      `${lead.positionLabel || '-'} · ${lead.implantDate || '-'} · ${lead.statusLabel || lead.status}`,
    ]);
  }
  return lines;
}
