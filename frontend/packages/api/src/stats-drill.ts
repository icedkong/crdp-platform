/** OQR-05B：统计单位与明细匹配；请求不保存患者集合，服务端每次复核权限范围。 */
export interface StatsDrillQuery {
  cohortYear?: number;
  doctorId?: number | string;
  expectedCount?: number;
  fieldKey?: string;
  from?: string;
  kind:
    | 'BASELINE_ALL'
    | 'BASELINE_MISSING'
    | 'FOLLOWUP_ALL'
    | 'FOLLOWUP_MISSING'
    | 'MONTH'
    | 'NODE'
    | 'YEAR';
  metric?: 'DEATH' | 'DUE' | 'FOLLOWED' | 'IN_WINDOW' | 'LOST';
  nodeId?: number | string;
  occurrence?: number;
  pageNo?: number;
  pageSize?: number;
  patientType?: string;
  planId?: number | string;
  timeField?: string;
  to?: string;
  wardId?: number | string;
  year?: number;
}
export interface StatsDrillContext {
  enrollmentId?: null | string;
  nodeId?: null | string;
  nodeName?: null | string;
  occurrence?: null | number;
  planDate?: null | string;
  planId?: null | string;
  planName?: null | string;
  recordId?: null | string;
  taskId?: null | string;
  virtual: boolean;
  year?: null | number;
}
export interface StatsDrillPage {
  changed: boolean;
  items: Array<{
    contexts: StatsDrillContext[];
    key: string;
    name?: null | string;
    patientId: string;
  }>;
  pageNo: number;
  pageSize: number;
  refreshedAt?: null | string;
  timeBasis: 'DAILY' | 'LIVE';
  total: number;
  unit: string;
}
