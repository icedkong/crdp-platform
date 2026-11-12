/**
 * E21-T01 / C-PAT-004、C-PAT-005：植入记录（一对多）与电极记录的类型与纯逻辑
 * （接口契约 docs/api/implant.md §1，后端 `com.crdp.followup.implant.api`）。
 *
 * 植入记录与电极的显示名由后端随行下发（编码 + 显示名一并返回，页面不硬编码文案）；
 * 新建 / 编辑表单的选项清单来自 GET /implants/options（暂行预置，业务字典值随 E21-T04）。
 */

import { visitNoError } from './patient-registration';

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

// ---------------------------------------------------------------- E21-T02 / C-PAT-006 更换流程

/** 植入类型编码（编码与后端 ImplantCodes 对齐）。 */
export const IMPLANT_TYPE_FIRST = 'FIRST';
export const IMPLANT_TYPE_REPLACE = 'REPLACE';
export const IMPLANT_TYPE_UPGRADE = 'UPGRADE';
export const IMPLANT_TYPE_LEAD_REVISION = 'LEAD_REVISION';
export const IMPLANT_TYPE_OTHER = 'OTHER';

/** 电极处置编码（与后端对齐）：沿用 / 废弃留置 / 已拔除。 */
export const IMPLANT_LEAD_ACTION_KEEP = 'KEEP';
export const IMPLANT_LEAD_ACTION_ABANDON = 'ABANDON';
export const IMPLANT_LEAD_ACTION_EXTRACT = 'EXTRACT';

/** 更换 / 升级弹窗可选的植入类型（首次植入走登记 / 新建，pages.md §4.4）。 */
export const IMPLANT_REPLACE_TYPES = [
  IMPLANT_TYPE_REPLACE,
  IMPLANT_TYPE_UPGRADE,
  IMPLANT_TYPE_LEAD_REVISION,
  IMPLANT_TYPE_OTHER,
] as const;

/** 旧电极处置行（目标植入记录下的电极逐根显式选择）。 */
export interface ImplantLeadActionForm {
  action?: null | string;
  leadId: string;
}

/** 新植电极行（植入日期缺省 = 手术日期）。 */
export type ImplantNewLeadForm = ImplantLeadForm;

/** 更换 / 升级 / 电极修正表单（C-PAT-006 器械更换流程）。 */
export interface ImplantReplaceForm {
  /** 手术日期。 */
  date?: null | string;
  /** 新器械字段（电极修正时与目标一致，不改脉冲发生器）。 */
  device: {
    brand?: null | string;
    deviceType?: null | string;
    doctor?: null | string;
    hospital?: null | string;
    indication?: null | string;
    indicationNote?: null | string;
    model?: null | string;
    pacingMode?: null | string;
    serialNo?: null | string;
  };
  /** 目标记录下每根电极的处置。 */
  leadActions: ImplantLeadActionForm[];
  /** 新植电极。 */
  newLeads: ImplantNewLeadForm[];
  /** 植入说明（类型选「其他」时必填）。 */
  note?: null | string;
  /** 目标（当前器械）植入记录。 */
  target: ImplantRecordView;
  /** 新植入类型（REPLACE / UPGRADE / LEAD_REVISION / OTHER）。 */
  type?: null | string;
}

/** POST /implants/{id}/replace 请求体。 */
export interface ImplantReplacePayload {
  baseVersion: number;
  implant: {
    brand?: null | string;
    deviceType?: null | string;
    doctor?: null | string;
    hospital?: null | string;
    implantDate: string;
    indication?: null | string;
    indicationNote?: null | string;
    model?: null | string;
    pacingMode?: null | string;
    serialNo?: null | string;
  };
  leadActions: Array<{ action: string; leadId: string }>;
  newLeads: Array<{
    chamber?: null | string;
    implantDate?: null | string;
    model?: null | string;
    position?: null | string;
  }>;
  note?: null | string;
  type: string;
}

/** 取出表单。 */
export interface ImplantExplantForm {
  date?: null | string;
  /** 目标记录下每根在用电极的处置（ABANDON / EXTRACT，取出不产生沿用电极）。 */
  leadActions: ImplantLeadActionForm[];
  reason?: null | string;
}

/** POST /implants/{id}/explant 请求体。 */
export interface ImplantExplantPayload {
  baseVersion: number;
  date: string;
  leadActions: Array<{ action: string; leadId: string }>;
  reason?: null | string;
}

/**
 * 更换表单初值：新器械字段预填目标（当前器械）的值（更换常只换脉冲发生器，品牌 / 型号可改）； 电极修正（LEAD_REVISION）锁定器械字段；在用电极处置缺省「沿用」
 * （取出表单另行用 {@link emptyExplantForm}，缺省不选）。
 */
export function emptyReplaceForm(
  target: ImplantRecordView,
  initialType: string = IMPLANT_TYPE_REPLACE,
): ImplantReplaceForm {
  return {
    target,
    date: null,
    type: initialType,
    note: null,
    device: {
      deviceType: target.deviceType,
      brand: target.brand ?? null,
      model:
        initialType === IMPLANT_TYPE_LEAD_REVISION
          ? (target.model ?? null)
          : null,
      serialNo:
        initialType === IMPLANT_TYPE_LEAD_REVISION
          ? (target.serialNo ?? null)
          : null,
      pacingMode: target.pacingMode ?? null,
      hospital: target.hospital ?? null,
      doctor: target.doctor ?? null,
      indication: target.indication ?? null,
      indicationNote: null,
    },
    leadActions: activeLeadsOf(target).map((lead) => ({
      leadId: lead.id,
      action: IMPLANT_LEAD_ACTION_KEEP,
    })),
    newLeads: [],
  };
}

/** 取出表单初值：在用电极处置不预选（废弃留置 / 已拔除须显式选择）。 */
export function emptyExplantForm(
  target: ImplantRecordView,
): ImplantExplantForm {
  return {
    date: null,
    reason: null,
    leadActions: activeLeadsOf(target).map((lead) => ({
      leadId: lead.id,
      action: null,
    })),
  };
}

/** 目标记录下的在用电极（废弃留置 / 已拔除的历史电极不进入处置清单）。 */
export function activeLeadsOf(record: ImplantRecordView): ImplantLeadView[] {
  return record.leads.filter(
    (lead) => lead.status === IMPLANT_LEAD_STATUS_ACTIVE,
  );
}

/** 电极修正不改变脉冲发生器：类型 / 品牌 / 型号 / 序列号须与目标一致（起搏模式为程控设置可变）。 */
export function generatorUnchanged(form: ImplantReplaceForm): boolean {
  const { device, target } = form;
  return (
    device.deviceType === target.deviceType &&
    (device.brand ?? null) === (target.brand ?? null) &&
    (device.model ?? null) === (target.model ?? null) &&
    (device.serialNo ?? null) === (target.serialNo ?? null)
  );
}

/**
 * 更换 / 升级 / 电极修正表单校验（与后端 ImplantService.replace 同向）：类型 / 日期 / 起搏器类型必填；
 * 日期不晚于今天且不早于目标植入日期；「其他」须填说明；电极修正须保持脉冲发生器；在用电极逐根有处置；
 * 无导线新器械不可携带电极；新电极腔室必填、部位按腔室取值。
 */
export function validateReplaceForm(
  form: ImplantReplaceForm | null | undefined,
  options?: ImplantOptions | null,
): ImplantFormErrors {
  const errors: ImplantFormErrors = {};
  if (!form || !form.target) return { type: '请选择植入类型' };
  if (!form.type) errors.type = '必选';
  else if (!IMPLANT_REPLACE_TYPES.includes(form.type as never))
    errors.type = '首次植入请在登记或「新建植入记录」中录入';
  if (!form.date) errors.date = '必填';
  else if (form.date > new Date().toISOString().slice(0, 10))
    errors.date = '不能晚于今天';
  else if (form.date < form.target.implantDate)
    errors.date = `不能早于原植入日期（${form.target.implantDate}）`;
  if (form.type === IMPLANT_TYPE_OTHER && !form.note?.trim())
    errors.note = '类型选「其他」时须填写说明';
  if (!form.device.deviceType) errors.deviceType = '必选';
  if (
    form.device.indication === IMPLANT_INDICATION_OTHER &&
    !form.device.indicationNote?.trim()
  ) {
    errors.indicationNote = '指征选「其他」时须填写补充说明';
  }
  if (form.type === IMPLANT_TYPE_LEAD_REVISION && !generatorUnchanged(form)) {
    errors.deviceType =
      '电极修正不改变脉冲发生器，类型 / 品牌 / 型号 / 序列号须与原记录一致';
  }
  const leadless = form.device.deviceType === IMPLANT_DEVICE_LEADLESS;
  const kept = form.leadActions.filter(
    (item) => item.action === IMPLANT_LEAD_ACTION_KEEP,
  ).length;
  if (leadless && (kept > 0 || form.newLeads.length > 0)) {
    errors.leadActions = '无导线起搏器不录 / 不沿用电极（C-PAT-005）';
    return errors;
  }
  if (!leadless) {
    form.leadActions.forEach((item, index) => {
      if (!item.action) errors[`leadActions.${index}`] = '请选择处置';
    });
  }
  const validChambers = new Set(
    options?.leadPositions.map((group) => group.chamber) ?? ['RA', 'RV', 'LV'],
  );
  form.newLeads.forEach((lead, index) => {
    const prefix = `newLeads.${index}`;
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

/** 组装更换请求体（新电极植入日期缺省 = 手术日期；说明空串归一为 null）。 */
export function buildReplacePayload(
  form: ImplantReplaceForm,
): ImplantReplacePayload {
  return {
    baseVersion: form.target.version,
    type: form.type as string,
    note: form.note?.trim() ? form.note.trim() : null,
    implant: {
      implantDate: form.date as string,
      deviceType: form.device.deviceType ?? null,
      brand: form.device.brand ?? null,
      model: form.device.model ?? null,
      serialNo: form.device.serialNo ?? null,
      pacingMode: form.device.pacingMode ?? null,
      hospital: form.device.hospital ?? null,
      doctor: form.device.doctor ?? null,
      indication: form.device.indication ?? null,
      indicationNote: form.device.indicationNote?.trim()
        ? form.device.indicationNote.trim()
        : null,
    },
    leadActions: form.leadActions.map((item) => ({
      leadId: item.leadId,
      action: item.action as string,
    })),
    newLeads: form.newLeads.map((lead) => ({
      chamber: lead.chamber ?? null,
      position: lead.position ?? null,
      model: lead.model ?? null,
      implantDate: lead.implantDate || form.date || null,
    })),
  };
}

/** 取出表单校验：日期必填且在植入日期与今天之间；在用电极逐根选废弃留置 / 已拔除。 */
export function validateExplantForm(
  form: ImplantExplantForm | null | undefined,
  target: ImplantRecordView,
): ImplantFormErrors {
  const errors: ImplantFormErrors = {};
  if (!form) return { date: '必填' };
  if (!form.date) errors.date = '必填';
  else if (form.date < target.implantDate)
    errors.date = `不能早于植入日期（${target.implantDate}）`;
  else if (form.date > new Date().toISOString().slice(0, 10))
    errors.date = '不能晚于今天';
  form.leadActions.forEach((item, index) => {
    if (
      item.action !== IMPLANT_LEAD_ACTION_ABANDON &&
      item.action !== IMPLANT_LEAD_ACTION_EXTRACT
    ) {
      errors[`leadActions.${index}`] = '请选择废弃留置或已拔除';
    }
  });
  return errors;
}

/** 组装取出请求体（原因缺省由后端补「器械取出」）。 */
export function buildExplantPayload(
  form: ImplantExplantForm,
  target: ImplantRecordView,
): ImplantExplantPayload {
  return {
    baseVersion: target.version,
    date: form.date as string,
    reason: form.reason?.trim() ? form.reason.trim() : null,
    leadActions: form.leadActions.map((item) => ({
      leadId: item.leadId,
      action: item.action as string,
    })),
  };
}

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
 * 首条植入记录（植入表单校验复用 {@link validateImplantForm}）；visitNo 可空 = 自动生成
 * （C-PAT-003 / E21-T10），填写时限长 ≤64（与后端 40001 同口径）；错误键带 `patient.` /
 * `implant.` 前缀供页面分区展示。
 */
export function validateCRegistration(
  patient: {
    birthDate?: null | string;
    deptId?: null | number;
    gender?: null | string;
    medicalRecordNo?: null | string;
    name?: null | string;
    visitNo?: null | string;
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
  const visitNoIssue = visitNoError(patient.visitNo);
  if (visitNoIssue) errors['patient.visitNo'] = visitNoIssue;
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
