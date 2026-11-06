import type {
  ReportConfigStatus,
  ReportIndicatorItemRequest,
  ReportIndicatorTplView,
  ReportNameCreateRequest,
  ReportNameUpdateRequest,
  ReportNameView,
  ReportTplCreateRequest,
  ReportTplUpdateRequest,
  ReportTypeCreateRequest,
  ReportTypeUpdateRequest,
  ReportTypeView,
} from '@crdp/api';

import { validateReportTypeCode } from '@crdp/api';

/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012 · 报告单设置页逻辑（纯函数）。
 * 编辑模型只含可维护列：类型（名称、字段集、名称必填、默认电子化状态、排序、启停、说明——
 * 编码创建后不可改）、名称（名称、绑定模板、电子化状态覆盖、排序、启停——所属类型不可改）、
 * 指标模板（名称、说明、启停、项目全量维护）。校验与后端 ReportConfigService 同口径
 * （40001 的前端第一道拦截）；40901 / 40902 业务拦截以后端 message 为准。
 */

// ---------------------------------------------------------------- 资料类型（A-ATT-003）

export interface TypeEditModel {
  code: string;
  defaultDigitizeStatus: null | string;
  formCode: string;
  name: string;
  nameRequired: boolean;
  remark: string;
  sortNo: number;
  status: ReportConfigStatus;
}

export function emptyTypeEditModel(): TypeEditModel {
  return {
    code: '',
    defaultDigitizeStatus: null,
    formCode: '',
    name: '',
    nameRequired: true,
    remark: '',
    sortNo: 0,
    status: 'ENABLED',
  };
}

export function toTypeEditModel(type: null | ReportTypeView): TypeEditModel {
  if (!type) {
    return emptyTypeEditModel();
  }
  return {
    code: type.code,
    defaultDigitizeStatus: type.defaultDigitizeStatus,
    formCode: type.formCode,
    name: type.name,
    nameRequired: type.nameRequired,
    remark: type.remark ?? '',
    sortNo: type.sortNo,
    status: type.status,
  };
}

/** 类型编码只在新建时可填（codeLocked = 编辑态）。 */
export function validateTypeEditModel(
  model: TypeEditModel,
  options: { codeLocked: boolean },
): Partial<Record<keyof TypeEditModel, string>> {
  const errors: Partial<Record<keyof TypeEditModel, string>> = {};
  if (!model.name.trim()) {
    errors.name = '请填写类型名称';
  } else if (model.name.trim().length > 128) {
    errors.name = '类型名称最多 128 个字符';
  }
  if (!options.codeLocked) {
    const codeError = validateReportTypeCode(model.code);
    if (codeError) {
      errors.code = codeError;
    }
  }
  if (!model.formCode) {
    errors.formCode = '请选择字段集（报告单类型的表单）';
  }
  if (model.sortNo < 0 || model.sortNo > 9999) {
    errors.sortNo = '排序取值 0–9999';
  }
  if (model.remark.length > 500) {
    errors.remark = '说明最多 500 个字符';
  }
  return errors;
}

export function buildTypeCreatePayload(
  model: TypeEditModel,
): ReportTypeCreateRequest {
  return {
    code: model.code.trim(),
    defaultDigitizeStatus: model.defaultDigitizeStatus as null | undefined,
    formCode: model.formCode,
    name: model.name.trim(),
    nameRequired: model.nameRequired,
    remark: model.remark.trim() || null,
    sortNo: model.sortNo,
  };
}

export function buildTypeUpdatePayload(
  model: TypeEditModel,
): ReportTypeUpdateRequest {
  return {
    defaultDigitizeStatus: model.defaultDigitizeStatus as null | undefined,
    formCode: model.formCode,
    name: model.name.trim(),
    nameRequired: model.nameRequired,
    remark: model.remark.trim() || null,
    sortNo: model.sortNo,
    status: model.status,
  };
}

// ---------------------------------------------------------------- 名称字典（A-ATT-011 / C-SCAN-003）

export interface NameEditModel {
  indicatorTplId: null | string;
  name: string;
  sortNo: number;
  status: ReportConfigStatus;
}

export function emptyNameEditModel(): NameEditModel {
  return { indicatorTplId: null, name: '', sortNo: 0, status: 'ENABLED' };
}

export function toNameEditModel(name: null | ReportNameView): NameEditModel {
  if (!name) {
    return emptyNameEditModel();
  }
  return {
    indicatorTplId: name.indicatorTplId,
    name: name.name,
    sortNo: name.sortNo,
    status: name.status,
  };
}

export function validateNameEditModel(
  model: NameEditModel,
): Partial<Record<keyof NameEditModel, string>> {
  const errors: Partial<Record<keyof NameEditModel, string>> = {};
  if (!model.name.trim()) {
    errors.name = '请填写名称';
  } else if (model.name.trim().length > 128) {
    errors.name = '名称最多 128 个字符';
  }
  if (model.sortNo < 0 || model.sortNo > 9999) {
    errors.sortNo = '排序取值 0–9999';
  }
  return errors;
}

export function buildNameCreatePayload(
  model: NameEditModel,
  typeCode: string,
): ReportNameCreateRequest {
  return {
    indicatorTplId: model.indicatorTplId,
    name: model.name.trim(),
    sortNo: model.sortNo,
    typeCode,
  };
}

export function buildNameUpdatePayload(
  model: NameEditModel,
): ReportNameUpdateRequest {
  return {
    indicatorTplId: model.indicatorTplId,
    name: model.name.trim(),
    sortNo: model.sortNo,
    status: model.status,
  };
}

// ---------------------------------------------------------------- 指标模板（A-ATT-012 / C-SCAN-004）

export interface TplItemEditModel {
  itemCode: string;
  itemName: string;
  refRange: string;
  refRangeFemale: string;
  refRangeMale: string;
  sortNo: number;
  unit: string;
}

export interface TplEditModel {
  items: TplItemEditModel[];
  name: string;
  remark: string;
  status: ReportConfigStatus;
}

export function emptyTplItem(sortNo = 0): TplItemEditModel {
  return {
    itemCode: '',
    itemName: '',
    refRange: '',
    refRangeFemale: '',
    refRangeMale: '',
    sortNo,
    unit: '',
  };
}

export function toTplEditModel(
  tpl: null | ReportIndicatorTplView,
): TplEditModel {
  if (!tpl) {
    return {
      items: [emptyTplItem(10)],
      name: '',
      remark: '',
      status: 'ENABLED',
    };
  }
  return {
    items:
      tpl.items.length > 0
        ? tpl.items.map((item, index) => toTplItemEditModel(item, index))
        : [emptyTplItem(10)],
    name: tpl.name,
    remark: tpl.remark ?? '',
    status: tpl.status,
  };
}

function toTplItemEditModel(
  item: ReportIndicatorTplView['items'][number],
  index: number,
): TplItemEditModel {
  return {
    itemCode: item.itemCode,
    itemName: item.itemName,
    refRange: item.refRange ?? '',
    refRangeFemale: item.refRangeFemale ?? '',
    refRangeMale: item.refRangeMale ?? '',
    sortNo: item.sortNo || (index + 1) * 10,
    unit: item.unit ?? '',
  };
}

export function validateTplEditModel(model: TplEditModel): {
  errors: Partial<Record<keyof TplEditModel, string>>;
  itemErrors: string[];
} {
  const errors: Partial<Record<keyof TplEditModel, string>> = {};
  const itemErrors: string[] = [];
  if (!model.name.trim()) {
    errors.name = '请填写模板名称';
  } else if (model.name.trim().length > 128) {
    errors.name = '模板名称最多 128 个字符';
  }
  if (model.remark.length > 500) {
    errors.remark = '说明最多 500 个字符';
  }
  const rows = model.items.filter(
    (item) => item.itemCode.trim() || item.itemName.trim(),
  );
  if (rows.length === 0) {
    errors.items = '指标模板至少需要一行项目（项目名称与代码都填写）';
  }
  const codes = new Set<string>();
  rows.forEach((item, index) => {
    const row = index + 1;
    if (!item.itemName.trim()) {
      itemErrors.push(`第 ${row} 行：请填写项目名称`);
    }
    const code = item.itemCode.trim();
    if (!code) {
      itemErrors.push(`第 ${row} 行：请填写项目代码`);
    } else if (codes.has(code)) {
      itemErrors.push(`第 ${row} 行：项目代码「${code}」重复`);
    } else {
      codes.add(code);
    }
    if (item.unit.length > 32) {
      itemErrors.push(`第 ${row} 行：单位最多 32 个字符`);
    }
    if (
      item.refRange.length > 64 ||
      item.refRangeMale.length > 64 ||
      item.refRangeFemale.length > 64
    ) {
      itemErrors.push(`第 ${row} 行：参考范围最多 64 个字符`);
    }
  });
  return { errors, itemErrors };
}

export function buildTplCreatePayload(
  model: TplEditModel,
): ReportTplCreateRequest {
  return {
    items: tplItemPayloads(model),
    name: model.name.trim(),
    remark: model.remark.trim() || null,
  };
}

export function buildTplUpdatePayload(
  model: TplEditModel,
): ReportTplUpdateRequest {
  return {
    items: tplItemPayloads(model),
    name: model.name.trim(),
    remark: model.remark.trim() || null,
    status: model.status,
  };
}

function tplItemPayloads(model: TplEditModel): ReportIndicatorItemRequest[] {
  return model.items
    .filter((item) => item.itemCode.trim() || item.itemName.trim())
    .map((item) => ({
      itemCode: item.itemCode.trim(),
      itemName: item.itemName.trim(),
      refRange: item.refRange.trim() || null,
      refRangeFemale: item.refRangeFemale.trim() || null,
      refRangeMale: item.refRangeMale.trim() || null,
      sortNo: item.sortNo,
      unit: item.unit.trim() || null,
    }));
}

// ---------------------------------------------------------------- 列表辅助

/** 名称字典按类型过滤 + 关键词（名称 / 绑定模板名）。 */
export function matchNameKeyword(
  name: ReportNameView,
  keyword: string,
): boolean {
  const query = keyword.trim().toLowerCase();
  if (!query) return true;
  return (
    name.name.toLowerCase().includes(query) ||
    (name.indicatorTplName ?? '').toLowerCase().includes(query)
  );
}

/** 删除确认文案（类型下有名称时后端 40901，前端提前给出指引）。 */
export function deleteTypeConfirmText(type: ReportTypeView): string {
  return type.nameCount > 0
    ? `「${type.name}」下仍有 ${type.nameCount} 个名称，删除前请先删除名称。`
    : `删除后「${type.name}」不可恢复（历史报告单不受影响），确定删除？`;
}

/** 模板删除确认文案（被绑定时后端 40901）。 */
export function deleteTplConfirmText(tpl: ReportIndicatorTplView): string {
  return tpl.boundNameCount > 0
    ? `「${tpl.name}」仍被 ${tpl.boundNameCount} 个名称绑定，删除前请先在名称字典中解绑。`
    : `删除后「${tpl.name}」不可恢复（已保存报告单的指标行不变），确定删除？`;
}
