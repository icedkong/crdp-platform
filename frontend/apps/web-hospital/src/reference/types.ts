/** 样板页用的虚构数据结构，对齐 components.md 的契约。 */

export interface ReportLocation {
  adjusted?: boolean;
  anchorKey?: string;
  anchorLabel?: string;
  anchorType?: 'category' | 'section' | null;
  linkRecordId?: string;
  linkType: 'baseline' | 'followup_record' | 'implant_record';
  recordDate?: string;
  recordLabel?: string;
}

export interface ReportLocationInput {
  anchorKey?: string;
  anchorTemplateVersion: number;
  anchorType?: 'category' | 'section' | null;
  linkRecordId?: string;
  linkType: ReportLocation['linkType'];
}

export interface AttachmentNavItem {
  digitizeStatus?: '已电子化' | '待录入' | '无需录入';
  fileCount: number;
  /** 没有任何位置时，出现在哪些页签的「未指定位置」。 */
  home: 'baseline' | 'both' | 'followup';
  locations: ReportLocation[];
  name: string;
  reportDate: string;
  reportId: string;
  thumbnailUrl?: string;
  typeCode: string;
  typeName: string;
}

export interface AttachmentGroup {
  children?: AttachmentGroup[];
  items: AttachmentNavItem[];
  key: string;
  title: string;
  warning?: boolean;
}

export interface CategoryProgress {
  attachmentCount?: number;
  filled: number;
  highlightCount?: number;
  key: string;
  label: string;
  total: number;
}

export interface ReadonlyField {
  derived?: boolean;
  label: string;
  sensitive?: boolean;
  value?: string;
}

export interface ReadonlyCategory {
  fields: ReadonlyField[];
  key: string;
  label: string;
}

export interface LocationTreeNode {
  children?: LocationTreeNode[];
  key: string;
  selectable: boolean;
  title: string;
  value?: ReportLocationInput;
}

export interface TimelineNode {
  canOpen: boolean;
  categories: ReadonlyCategory[];
  doneDate?: string;
  highlightCount?: number;
  label: string;
  note?: string;
  planDate?: string;
  progress: CategoryProgress[];
  recordId?: string;
  sections: CategoryProgress[];
  status: string;
  taskId?: string;
  windowEnd?: string;
  windowStart?: string;
}

export interface DeviceEvent {
  date: string;
  detail?: string;
  kind: 'first' | 'lead_fix' | 'other' | 'replace' | 'upgrade';
  title: string;
}

export interface ImplantRow {
  date: string;
  device: string;
  id: string;
  kind: string;
  trigger: string;
}

export interface LeadRow {
  date: string;
  site: string;
  status: string;
}

export interface ReferenceFeatures {
  eriCycle: boolean;
  implant: boolean;
  paramTrend: boolean;
  report: boolean;
  scanProgress: boolean;
}

export interface ReferencePatient {
  age: number;
  baselineCompleteness: number;
  categories: ReadonlyCategory[];
  device?: {
    eri: '已达ERI(EOI)' | '接近ERI' | '正常';
    implantDuration: string;
    typeLabel: string;
  } | null;
  dischargeDate?: string;
  features: ReferenceFeatures;
  followupCompleteness: number;
  gender: '女' | '未知' | '男';
  id: string;
  implants?: ImplantRow[];
  leads?: LeadRow[];
  legacyNo?: string;
  nameMasked: string;
  namePlain: string;
  nextVisit?: null | { label: string; planDate: string };
  patientNo: string;
  phoneMasked: string;
  phonePlain: string;
  progress: CategoryProgress[];
  responsibleName: string;
  status: string;
  variant: 'a' | 'c';
  wardName: string;
}
