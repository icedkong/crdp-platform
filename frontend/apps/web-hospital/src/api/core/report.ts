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
  ReportLocationNode,
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
