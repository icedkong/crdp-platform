/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、003、004：
 * 报告单设置（docs/api/report.md §6，权限 followup:scan:config，页面 /system/report-config）。
 * 类型 / 名称 / 模板均由后端写审计；运行时目录（report-types / report-names）供添加报告单
 * 对话框（E16-T02）读取，权限 followup:patient:view。
 */
import type {
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

import { apiPath, requestClient } from '#/api/request';

/** 类型清单（含停用，按排序号；含类型下名称数）。 */
export function fetchReportTypes() {
  return requestClient.get<ReportTypeView[]>(
    apiPath('/followup/report-config/types'),
  );
}

/** 新建类型（重复编码 40902、非 REPORT 字段集 40001）。 */
export function createReportType(payload: ReportTypeCreateRequest) {
  return requestClient.post<ReportTypeView>(
    apiPath('/followup/report-config/types'),
    payload,
  );
}

/** 编辑类型（编码不可改；启停走本接口）。 */
export function updateReportType(
  id: number | string,
  payload: ReportTypeUpdateRequest,
) {
  return requestClient.put<ReportTypeView>(
    apiPath(`/followup/report-config/types/${id}`),
    payload,
  );
}

/** 删除类型（类型下仍有名称时 40901）。 */
export function deleteReportType(id: number | string) {
  return requestClient.delete(apiPath(`/followup/report-config/types/${id}`));
}

/** 名称清单（typeCode 可空 = 全部，含停用）。 */
export function fetchReportNames(typeCode?: string) {
  const suffix = typeCode ? `?typeCode=${encodeURIComponent(typeCode)}` : '';
  return requestClient.get<ReportNameView[]>(
    apiPath(`/followup/report-config/names${suffix}`),
  );
}

/** 新建名称（同类型重名 40902）。 */
export function createReportName(payload: ReportNameCreateRequest) {
  return requestClient.post<ReportNameView>(
    apiPath('/followup/report-config/names'),
    payload,
  );
}

/** 编辑名称（所属类型不可改）。 */
export function updateReportName(
  id: number | string,
  payload: ReportNameUpdateRequest,
) {
  return requestClient.put<ReportNameView>(
    apiPath(`/followup/report-config/names/${id}`),
    payload,
  );
}

/** 删除名称。 */
export function deleteReportName(id: number | string) {
  return requestClient.delete(apiPath(`/followup/report-config/names/${id}`));
}

/** 指标模板清单（含项目行与绑定名称数）。 */
export function fetchReportTemplates() {
  return requestClient.get<ReportIndicatorTplView[]>(
    apiPath('/followup/report-config/indicator-templates'),
  );
}

/** 新建指标模板（项目全量给出）。 */
export function createReportTemplate(payload: ReportTplCreateRequest) {
  return requestClient.post<ReportIndicatorTplView>(
    apiPath('/followup/report-config/indicator-templates'),
    payload,
  );
}

/** 编辑指标模板（项目全量替换）。 */
export function updateReportTemplate(
  id: number | string,
  payload: ReportTplUpdateRequest,
) {
  return requestClient.put<ReportIndicatorTplView>(
    apiPath(`/followup/report-config/indicator-templates/${id}`),
    payload,
  );
}

/** 删除指标模板（仍被名称绑定时 40901）。 */
export function deleteReportTemplate(id: number | string) {
  return requestClient.delete(
    apiPath(`/followup/report-config/indicator-templates/${id}`),
  );
}
