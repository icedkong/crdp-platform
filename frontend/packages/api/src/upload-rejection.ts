/**
 * E22-T16 / 等保 D-01 前端面：上传失败的分类与行级文案（E22-T15 后端落地的前端配合）。
 *
 * 后端契约（`FileUploadService` / docs/api/report.md §1、docs/api/README.md §2.3）：
 * - 病毒检出：HTTP 400 + code 40001，message「文件未通过病毒检测（检出 …），已拒绝上传」
 *   （40001 与参数校验共用，前端按 message 中的「病毒检测」字样区分——后端该文案为
 *   固定字面量，仅用于分类标记；即便失配也只是回落通用文案，不影响展示）；
 * - 扫描不可用：HTTP 503 + code 50300，message「病毒扫描服务暂不可用，请稍后重试」
 *   （fail closed 不放行，服务恢复后重试可成功）。
 *
 * 用途：上传失败时「行 / 队列」级文案——病毒类不引导对同一文件重试、扫描不可用带
 * 稍后重试指引、其余维持调用方通用提示（格式与大小口径不变）。
 */

import { errorBody } from './login-rejection';

/** 上传失败分类：病毒检出 / 扫描不可用 / 其他（格式、大小、网络等）。 */
export type UploadFailureKind = 'other' | 'scan-unavailable' | 'virus';

export interface UploadFailureInfo {
  kind: UploadFailureKind;
  /** 后端 message（无响应体或网络错误为 null）。 */
  message: null | string;
}

/** 后端病毒检出文案的固定标记（FileUploadService 字面量）。 */
const VIRUS_MARKER = '病毒检测';

/** 解析上传失败：50300 = 扫描不可用；40001 + message 含「病毒检测」= 病毒检出；其余 other。 */
export function uploadFailureInfo(error: unknown): UploadFailureInfo {
  const body = errorBody(error as never);
  const code = Number(body.code);
  const message = typeof body.message === 'string' ? body.message : null;
  if (code === 50_300) {
    return { kind: 'scan-unavailable', message };
  }
  if (code === 40_001 && message?.includes(VIRUS_MARKER)) {
    return { kind: 'virus', message };
  }
  return { kind: 'other', message };
}

/**
 * 上传失败的行级文案：病毒 / 扫描不可用显示后端原文（无 message 回落 fallback），
 * 其他维持调用方通用文案（如「上传失败，可重试（格式与大小按租户配置）」）。
 */
export function uploadFailureText(error: unknown, fallback: string): string {
  const { kind, message } = uploadFailureInfo(error);
  if (kind === 'other' || !message) {
    return fallback;
  }
  return message;
}
