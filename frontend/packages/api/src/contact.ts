/**
 * E15-T04 / A-FU-006、A-FU-025、C-FU-019：沟通记录与录音附件
 * （docs/api/followup.md §6，后端 ContactLogController；页面 pages.md §4.8 沟通记录页签，
 * 组件 ContactLogModal / AudioPlayer，components.md §2 contact 域、§13）。
 *
 * 联系结果字典完整（六值，后端 ContactResult 枚举 + 平台库 V45 预置字典）：
 * REACHED 已联系 / NOT_REACHED 未接通（未联系到）/ WRONG_NUMBER 号码错误 / REFUSED 拒访 /
 * FAMILY 家属代答 / DECEASED 告知已故。结果 = DECEASED 时后端同事务触发死亡终止（A-FU-007）。
 * 录音（A-FU-025）：MP3 / M4A / WAV 浏览器原生播放不转码；AMR 只保存与下载。
 */

/** fu_contact_log.result（六值；写入按后端枚举校验，40001 提示完整字典）。 */
export type ContactResult =
  | 'DECEASED'
  | 'FAMILY'
  | 'NOT_REACHED'
  | 'REACHED'
  | 'REFUSED'
  | 'WRONG_NUMBER';

/** fu_contact_log.method（A-FU-006 功能清单「电话 / 微信 / 面谈」+ 关键字段表「其他」）。 */
export type ContactMethod = 'FACE_TO_FACE' | 'OTHER' | 'PHONE' | 'WECHAT';

/** 联系结果 → 中文（components.md §2 contact 域；StatusTag 传中文，颜色由 tokens statusTone 决定）。 */
export const CONTACT_RESULT_TEXT: Record<ContactResult, string> = {
  REACHED: '已联系',
  NOT_REACHED: '未接通',
  WRONG_NUMBER: '号码错误',
  REFUSED: '拒访',
  FAMILY: '家属代答',
  DECEASED: '告知已故',
};

/** 联系结果下拉（顺序即展示顺序 = A-FU-006 列举顺序）。 */
export const CONTACT_RESULT_OPTIONS: Array<{
  label: string;
  value: ContactResult;
}> = (
  [
    'REACHED',
    'NOT_REACHED',
    'WRONG_NUMBER',
    'REFUSED',
    'FAMILY',
    'DECEASED',
  ] as const
).map((value) => ({ label: CONTACT_RESULT_TEXT[value], value }));

/** 沟通方式 → 中文。 */
export const CONTACT_METHOD_TEXT: Record<ContactMethod, string> = {
  PHONE: '电话',
  WECHAT: '微信',
  FACE_TO_FACE: '面谈',
  OTHER: '其他',
};

/** 沟通方式下拉（顺序即展示顺序）。 */
export const CONTACT_METHOD_OPTIONS: Array<{
  label: string;
  value: ContactMethod;
}> = (['PHONE', 'WECHAT', 'FACE_TO_FACE', 'OTHER'] as const).map((value) => ({
  label: CONTACT_METHOD_TEXT[value],
  value,
}));

export function contactResultText(result: null | string | undefined): string {
  if (result === null || result === undefined || result === '') return '—';
  return CONTACT_RESULT_TEXT[result as ContactResult] ?? result;
}

export function contactMethodText(method: null | string | undefined): string {
  if (method === null || method === undefined || method === '') return '—';
  return CONTACT_METHOD_TEXT[method as ContactMethod] ?? method;
}

/** 结果 = 未接通时 ContactLogModal 的提示（components.md §13 原文；自动跟进任务随 E15-T05 生成）。 */
export function contactFollowupHint(result: null | string | undefined): string {
  if (result === 'NOT_REACHED' || result === 'WRONG_NUMBER') {
    return '系统次日自动生成跟进';
  }
  if (result === 'DECEASED') {
    return '保存后患者标记为死亡，未完成任务自动终止（A-FU-007）';
  }
  return '';
}

/** 录音展示行（列表内嵌；AMR 的 inlinePlayable=false：只保存与下载）。 */
export interface ContactAudioFile {
  fileId: string;
  /** 真实类型（Tika 按内容识别）：mp3 / m4a / wav / amr。 */
  format: string;
  inlinePlayable: boolean;
  mimeType?: null | string;
  originalName: string;
  sizeBytes: number;
}

/** 沟通记录行（pages.md §4.8 表格：时间 / 方式 / 结果 / 约定下次联系 / 备注 / 录音 / 操作人）。 */
export interface ContactLogRow {
  /** 录音（可多个；本任务接口按每条沟通记录配置的上限校验）。 */
  audios: ContactAudioFile[];
  contactTime: string;
  contactVersionId?: null | string;
  createdAt: string;
  creatorName?: null | string;
  id: string;
  method: ContactMethod | string;
  nextContactTime?: null | string;
  note?: null | string;
  patientId: string;
  result: ContactResult | string;
  taskId?: null | string;
  /** 乐观锁版本（修改带 baseVersion，冲突 409）。 */
  version: number;
}

/** 患者沟通记录分页（统一分页口径）。 */
export interface ContactLogPage {
  items: ContactLogRow[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 记录沟通请求（docs/api/followup.md §6；录音须先经 POST /files 上传拿 fileId）。 */
export interface ContactLogCreateRequest {
  /** 录音 fileId 清单（只收 MP3 / M4A / WAV / AMR 且未挂他处）。 */
  audioFileIds?: Array<number | string>;
  /** 沟通时间（缺省 = 当前时间）。 */
  contactTime?: null | string;
  /** 所用联系方式版本 id（可选，引用 pt_contact 版本）。 */
  contactVersionId?: null | number | string;
  method: ContactMethod;
  nextContactTime?: null | string;
  note?: null | string;
  result: ContactResult;
  /** 关联任务（随访工作台入口带；患者页签可不带）。 */
  taskId?: null | number | string;
}

/** 修改请求（乐观锁 baseVersion；contactTime 缺省保持原值，可空字段传 null 即清空）。 */
export interface ContactLogUpdateRequest {
  baseVersion?: number;
  contactTime?: null | string;
  contactVersionId?: null | number | string;
  method: ContactMethod;
  nextContactTime?: null | string;
  note?: null | string;
  result: ContactResult;
}

/**
 * 创建结果（terminatedTasks = 结果「告知已故」时本次终止的未完成任务数，其余 null；
 * callReportId = 带录音时同事务自动生成的「通话记录」报告单 id，A-ATT-017 / E16-T08）。
 */
export interface ContactLogCreateResult {
  callReportId?: null | string;
  followupTaskId?: null | string;
  log: ContactLogRow;
  terminatedTasks?: null | number;
}
