/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：字段字典接口（docs/api/form.md §3，页面 /system/field）。
 * 表单清单登录即可；字段查看与维护用 followup:field:edit。维护只上送可改列
 * （显示名、帮助、单位、软范围、必填、选项、状态），键与编码在后端不可改。
 */
import type {
  FieldDictLogView,
  FormFieldDef,
  FormFieldUpdateRequest,
  FormSummary,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 表单清单（模板选择下拉）。 */
export function listForms() {
  return requestClient.get<FormSummary[]>(apiPath('/form/forms'));
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
