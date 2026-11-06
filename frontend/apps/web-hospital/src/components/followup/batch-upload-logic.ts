/**
 * E16-T03 / A-ATT-014、C-SCAN-007（pages.md §6.3 批量上传）：批量上传对话框的纯逻辑。
 * 组件保持薄壳，可测口径集中在这里：一次 ≤ 50 个文件、MERGE 模式受每份上限约束
 * （A-ATT-004）、ONE_PER_FILE 逐份校验（名称按类型必填、报告时间）与提交载荷组装。
 */
import type {
  ReportBatchCreateRequest,
  ReportBatchItemOverride,
  ReportBatchMode,
  ReportLocationInput,
} from '@crdp/api';

/** 一次批量上传的文件数上限（A-ATT-014「一次最多 50 个文件」；C-SCAN-007 同口径）。 */
export const MAX_BATCH_FILES = 50;

/** 批量文件行状态（C-SCAN-007 显示进度，失败可重试）。 */
export type BatchFileStatus = 'done' | 'failed' | 'pending' | 'uploading';

export interface BatchFileRow {
  error?: null | string;
  fileId?: null | string;
  name: string;
  /** 行级位置覆盖（ONE_PER_FILE 逐份调整；空 = 沿用公共位置，C-SCAN-007「后可逐份调整」） */
  locations?: ReportLocationInput[];
  nameId?: null | string;
  reportDate?: null | string;
  size: number;
  status: BatchFileStatus;
  uid: string;
}

/**
 * 选择文件入队拦截：单批累计 ≤ {@link MAX_BATCH_FILES}；MERGE 模式（合并为一份）还受
 * 每份报告单上限约束（A-ATT-004 / C-SCAN-006）。超出的文件不入队并给出文案。
 */
export function acceptBatchFiles(
  existing: number,
  incoming: File[],
  mode: ReportBatchMode,
  maxFilesPerReport: number,
): { accepted: File[]; error: null | string } {
  const limit =
    mode === 'MERGE' && maxFilesPerReport > 0
      ? Math.min(MAX_BATCH_FILES, maxFilesPerReport)
      : MAX_BATCH_FILES;
  const room = limit - existing;
  if (room <= 0) {
    return { accepted: [], error: batchLimitText(mode, maxFilesPerReport) };
  }
  const accepted = incoming.slice(0, room);
  const error =
    incoming.length > room ? batchLimitText(mode, maxFilesPerReport) : null;
  return { accepted, error };
}

/** 数量上限文案（MERGE = 每份上限；ONE_PER_FILE = 单批 50）。 */
export function batchLimitText(
  mode: ReportBatchMode,
  maxFilesPerReport: number,
): string {
  return mode === 'MERGE' && maxFilesPerReport > 0
    ? `合并为一份时最多 ${maxFilesPerReport} 个文件（每份报告单上限）`
    : `一次最多上传 ${MAX_BATCH_FILES} 个文件`;
}

/** 公共表单值（类型 / 名称 / 报告时间 / 位置 / 模式）。 */
export interface BatchFormValues {
  fileCount: number;
  filesReady: boolean;
  locations: ReportLocationInput[];
  maxFilesPerReport: number;
  mode: ReportBatchMode;
  nameId: null | string;
  nameRequired: boolean;
  reportDate: null | string;
  typeCode: null | string;
}

/** 公共表单校验：键 → 错误文案（逐项独立显示，与服务端同口径）。 */
export function validateBatchForm(values: BatchFormValues): {
  files: null | string;
  locations: null | string;
  mode: null | string;
  nameId: null | string;
  reportDate: null | string;
  typeCode: null | string;
} {
  return {
    files: filesError(values),
    locations: values.locations.length === 0 ? '请至少选择 1 个位置' : null,
    mode: null,
    nameId: values.nameRequired && !values.nameId ? '请选择名称' : null,
    reportDate: values.reportDate ? null : '请填写报告时间',
    typeCode: values.typeCode ? null : '请选择类型',
  };
}

/** 文件区校验：还有未上传成功的文件时不许提交；一个文件都没选也提示。 */
function filesError(values: BatchFormValues): null | string {
  if (!values.filesReady) {
    return '还有文件未上传成功，请重试或移除后再提交';
  }
  return values.fileCount === 0 ? '请选择文件' : null;
}

/** 是否存在任一公共错误（提交前置）。 */
export function batchFormHasError(errors: {
  files: null | string;
  locations: null | string;
  mode: null | string;
  nameId: null | string;
  reportDate: null | string;
  typeCode: null | string;
}): boolean {
  return Boolean(
    errors.typeCode ||
    errors.nameId ||
    errors.reportDate ||
    errors.locations ||
    errors.files ||
    errors.mode,
  );
}

/**
 * ONE_PER_FILE 逐份校验（A-ATT-014「再逐份补充名称与报告时间」）：按生效值校验
 * （行覆盖优先、空值回落公共值，与后端同口径）——名称按类型必填、报告时间必填。
 * 返回行 uid → 错误文案；MERGE 返回空表。
 */
export function batchItemErrors(
  rows: BatchFileRow[],
  mode: ReportBatchMode,
  nameRequired: boolean,
  common: { nameId: null | string; reportDate: null | string },
): Map<string, string> {
  const errors = new Map<string, string>();
  if (mode !== 'ONE_PER_FILE') return errors;
  for (const row of rows) {
    const effective = effectiveItemValue(row, common);
    if (nameRequired && !effective.nameId) {
      errors.set(row.uid, '请选择名称');
    } else if (!effective.reportDate) {
      errors.set(row.uid, '请填写报告时间');
    }
  }
  return errors;
}

/** 行生效值（行覆盖优先，空值回落公共值；与后端 batchCreate 同口径）。 */
export function effectiveItemValue(
  row: BatchFileRow,
  common: { nameId: null | string; reportDate: null | string },
): { nameId: null | string; reportDate: null | string } {
  return {
    nameId: row.nameId ?? common.nameId,
    reportDate: row.reportDate ?? common.reportDate,
  };
}

/** 组装提交载荷：fileIds 按行顺序；items 只带与公共值不同的行（名称 / 日期 / 位置覆盖）。 */
export function buildBatchPayload(args: {
  commonNameId: null | string;
  commonReportDate: string;
  locations: ReportLocationInput[];
  mode: ReportBatchMode;
  patientId: string;
  rows: BatchFileRow[];
  typeCode: string;
}): ReportBatchCreateRequest {
  const ready = args.rows.filter((row) => row.status === 'done' && row.fileId);
  const items: ReportBatchItemOverride[] = [];
  for (const row of ready) {
    const effective = effectiveItemValue(row, {
      nameId: args.commonNameId,
      reportDate: args.commonReportDate,
    });
    const hasLocationOverride =
      row.locations !== undefined && row.locations.length > 0;
    const changed =
      effective.nameId !== args.commonNameId ||
      effective.reportDate !== args.commonReportDate ||
      hasLocationOverride;
    if (!changed) continue;
    items.push({
      fileId: row.fileId as string,
      locations: hasLocationOverride ? row.locations : undefined,
      nameId:
        effective.nameId === args.commonNameId ? undefined : effective.nameId,
      reportDate:
        effective.reportDate === args.commonReportDate
          ? undefined
          : effective.reportDate,
    });
  }
  return {
    fileIds: ready.map((row) => row.fileId as string),
    items: items.length > 0 ? items : undefined,
    locations: args.locations,
    mode: args.mode,
    nameId: args.commonNameId,
    patientId: args.patientId,
    reportDate: args.commonReportDate,
    typeCode: args.typeCode,
  };
}
