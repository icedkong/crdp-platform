/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010（docs/api/report.md §2）：
 * 报告单 / 资料页签与添加报告单对话框的运行时接口。
 * 创建 followup:scan:upload；位置树与患者内清单 followup:patient:view。
 * 类型 / 名称目录（report-types / report-names）读取见本文件，配置维护见 reportConfig.ts。
 */
import type {
  PatientReportsView,
  ReportBatchCreatedView,
  ReportBatchCreateRequest,
  ReportCatalogName,
  ReportCatalogType,
  ReportCreatedView,
  ReportCreateRequest,
  ReportDeletedView,
  ReportLocationNode,
  ReportLocationsChangedView,
  ReportLocationsChangeRequest,
  ReportSearchPage,
  ReportSearchRequest,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E16-T05 / A-ATT-016、C-SCAN-014：全院报告单列表检索（/followup/report，pages.md §6.4）。
 * 病区隔离由后端数据范围承担（越范围患者的报告单不可见）；行内姓名 / 病案号为脱敏值。
 * 权限 followup:report:view（菜单 plt_menu 23，OQ-12）。
 */
export function searchReports(payload: ReportSearchRequest) {
  return requestClient.post<ReportSearchPage>(
    apiPath('/followup/reports/search'),
    payload,
  );
}

/** 生效类型与字段集（只含启用类型，选类型后对话框字段集随之变化）。 */
export function fetchCatalogReportTypes() {
  return requestClient.get<ReportCatalogType[]>(
    apiPath('/followup/report-types'),
  );
}

/** 某类型下生效名称（含绑定的指标模板项目；typeCode 必填）。 */
export function fetchCatalogReportNames(typeCode: string) {
  return requestClient.get<ReportCatalogName[]>(
    apiPath(`/followup/report-names?typeCode=${encodeURIComponent(typeCode)}`),
  );
}

/** 报告单位置树（基线资料分类 + 随访记录整条 / 分组；components.md §8 options）。 */
export function fetchReportLocations(patientId: number | string) {
  return requestClient.get<ReportLocationNode[]>(
    apiPath(`/followup/patients/${patientId}/report-locations`),
  );
}

/** 患者内报告单清单（报告单页签；scope=all 默认 / baseline / followup）。 */
export function fetchPatientReports(
  patientId: number | string,
  scope?: 'all' | 'baseline' | 'followup',
) {
  const suffix = scope && scope !== 'all' ? `?scope=${scope}` : '';
  return requestClient.get<PatientReportsView>(
    apiPath(`/followup/patients/${patientId}/reports${suffix}`),
  );
}

/** 创建报告单（位置 ≥ 1，空 40001；附件上限与归属校验在后端）。 */
export function createReport(payload: ReportCreateRequest) {
  return requestClient.post<ReportCreatedView>(
    apiPath('/followup/reports'),
    payload,
  );
}

/**
 * 批量上传（E16-T03 / A-ATT-014、C-SCAN-007）：先统一类型与位置（≥ 1），可逐份调整
 * 名称与报告时间；ONE_PER_FILE 每个文件一份、MERGE 合并为一份多页资料；一次 ≤ 50 个
 * 文件，单份 ≤ 每份上限（超限 40001，拦截在后端）。
 */
export function batchCreateReports(payload: ReportBatchCreateRequest) {
  return requestClient.post<ReportBatchCreatedView>(
    apiPath('/followup/reports/batch-create'),
    payload,
  );
}

// ---------------------------------------------------------------- E16-T09 删除（A-ATT-007 / C-SCAN-016）

/**
 * 删除整份报告单（软删除）：reason 必填（40001）；附件台账一并软删（预览 / 下载随即
 * 40400），数据保留并写 RPT_DELETE 审计；越范围 40400、重复删除 40400。权限
 * followup:scan:delete（按钮 plt_menu 233）。
 */
export function deleteReport(reportId: number | string, reason: string) {
  return requestClient.delete<ReportDeletedView>(
    apiPath(`/followup/reports/${reportId}`),
    { data: { reason } },
  );
}

/**
 * 删除报告单中的单个文件（软删除）：报告单保留、fileCount 递减，写 RPT_FILE_DELETE
 * 审计；文件不在该报告单 / 已删 40400。逐文件入口随报告单详情面板（E16-T14 附件导航
 * ⋮ 菜单）开放，接口先行。
 */
export function deleteReportFile(
  reportId: number | string,
  fileId: number | string,
  reason: string,
) {
  return requestClient.delete<ReportDeletedView>(
    apiPath(`/followup/reports/${reportId}/files/${fileId}`),
    { data: { reason } },
  );
}

/**
 * E16-T13 / E16-T14 / A-ATT-020、C-SCAN-020：修改位置（PUT，followup:scan:upload）。
 * { add[], remove[] } 至少一项非空；结果至少保留 1 个位置（40001）；add 锚点按当前
 * 模板版本校验。无位置报告单（「未指定位置」组）经 add 补指定。
 */
export function updateReportLocations(
  reportId: number | string,
  payload: ReportLocationsChangeRequest,
) {
  return requestClient.put<ReportLocationsChangedView>(
    apiPath(`/followup/reports/${reportId}/locations`),
    payload,
  );
}
