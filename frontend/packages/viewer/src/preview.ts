/**
 * E10-T04 / A-FU-025、A-ATT-005：文件的浏览器预览方式判定（纯函数，组件与 E16 全屏查看器共用）。
 *
 * 口径（医院 A 规格 §5 / §8）：MP3 / M4A / WAV 用浏览器原生 audio 在线播放、不转码； AMR
 * 浏览器不能直接播放，只保存与下载；图片 / PDF / DOCX 页内预览（pdf.js、docx-preview）； XLSX 只能下载查看。 优先级：format（后端真实类型目录编码）> MIME
 * > 文件名扩展名；都识别不出按「只下载」处理（不猜测可预览）。
 */

import type { PreviewMode } from './typings';

export type { PreviewMode };

export interface PreviewDecision {
  mode: PreviewMode;
  /** download-only 的原因文案（可预览模式为空串）。 */
  reason: string;
}

const FORMAT_MODES: Record<string, PreviewMode> = {
  docx: 'docx',
  jpg: 'image',
  m4a: 'audio',
  mp3: 'audio',
  pdf: 'pdf',
  png: 'image',
  wav: 'audio',
};

const MIME_MODES: Record<string, PreviewMode> = {
  'application/pdf': 'pdf',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':
    'download-only',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
    'docx',
  'audio/amr': 'download-only',
  'audio/mpeg': 'audio',
  'audio/mp4': 'audio',
  'audio/vnd.wave': 'audio',
  'audio/x-m4a': 'audio',
  'audio/x-wav': 'audio',
  'image/jpeg': 'image',
  'image/png': 'image',
};

/** 只下载的原因文案（components.md §12、§13；列表与查看器共用单一来源）。 */
const DOWNLOAD_ONLY_REASONS: Record<string, string> = {
  amr: 'AMR 格式浏览器不能直接播放，请下载后收听',
  unknown: '该格式暂不支持在线预览，请下载查看',
  xlsx: 'Excel 工作簿暂不支持在线预览，请下载查看',
};

export function downloadOnlyReason(format?: null | string): string {
  const known = format ? DOWNLOAD_ONLY_REASONS[format] : undefined;
  return known ?? '该格式暂不支持在线预览，请下载查看';
}

/** 判定一个文件的预览方式。 */
export function previewModeOf(file: PreviewableFile): PreviewDecision {
  const mode = byFormat(file.format) ?? byMime(file.mime) ?? byName(file.name);
  if (mode && mode !== 'download-only') {
    return { mode, reason: '' };
  }
  const format =
    file.format ?? formatOfName(file.name) ?? byMimeFormat(file.mime);
  return { mode: 'download-only', reason: downloadOnlyReason(format) };
}

function byFormat(format?: null | string): null | PreviewMode {
  if (!format) {
    return null;
  }
  const normalized = format.toLowerCase();
  if (normalized === 'amr' || normalized === 'xlsx') {
    return 'download-only';
  }
  return FORMAT_MODES[normalized] ?? null;
}

function byMime(mime?: null | string): null | PreviewMode {
  if (!mime) {
    return null;
  }
  return MIME_MODES[mime.toLowerCase()] ?? null;
}

function byName(name?: null | string): null | PreviewMode {
  const format = formatOfName(name);
  return format ? byFormat(format) : null;
}

function formatOfName(name?: null | string): null | string {
  if (!name) {
    return null;
  }
  const dot = name.lastIndexOf('.');
  if (dot === -1 || dot === name.length - 1) {
    return null;
  }
  return name.slice(dot + 1).toLowerCase();
}

function byMimeFormat(mime?: null | string): null | string {
  if (!mime) {
    return null;
  }
  if (mime.toLowerCase() === 'audio/amr') {
    return 'amr';
  }
  if (
    mime.toLowerCase() ===
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  ) {
    return 'xlsx';
  }
  return null;
}
