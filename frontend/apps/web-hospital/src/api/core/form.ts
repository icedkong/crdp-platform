/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：字段字典接口（docs/api/form.md §3，页面 /system/field）。
 * 表单清单登录即可；字段查看与维护用 followup:field:edit。维护只上送可改列
 * （显示名、帮助、单位、软范围、必填、选项、状态），键与编码在后端不可改。
 */
import type {
  FieldDictLogView,
  FormFieldDef,
  FormFieldUpdateRequest,
  FormSchema,
  FormSummary,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 表单清单（模板选择下拉）。 */
export function listForms() {
  return requestClient.get<FormSummary[]>(apiPath('/form/forms'));
}

/**
 * 表单模板（components.md §10.1 FormSchema；E15-T02 随访工作台使用）：不传 version
 * 取当前发布版；已有记录按其保存时固化的版本取（旧版本快照可读，E11-T02）。
 */
export function fetchFormSchema(formCode: string, version?: number) {
  const query = version ? `?version=${version}` : '';
  return requestClient.get<FormSchema>(
    apiPath(`/form/forms/${formCode}${query}`),
  );
}

/** 字段清单（按表单、可再按分类锚点键过滤）。 */
export function listFormFields(formCode: string, categoryKey?: string) {
  const query = categoryKey
    ? `?formCode=${formCode}&categoryKey=${categoryKey}`
    : `?formCode=${formCode}`;
  return requestClient.get<FormFieldDef[]>(apiPath(`/form/fields${query}`));
}

/** 维护字段（显示名 / 帮助 / 单位 / 软范围 / 必填 / 选项 / 启停）。 */
export function updateFormField(
  formCode: string,
  fieldKey: string,
  data: FormFieldUpdateRequest,
) {
  return requestClient.put<FormFieldDef>(
    apiPath(`/form/fields/${fieldKey}?formCode=${formCode}`),
    data,
  );
}

/** 字段字典变更记录（A-FLD-009、C-FLD-009：倒序最近 100 条，前后值、操作人、时间）。 */
export function listFormFieldLogs(formCode: string, fieldKey: string) {
  return requestClient.get<FieldDictLogView[]>(
    apiPath(`/form/fields/${fieldKey}/logs?formCode=${formCode}`),
  );
}
