/**
 * E10-T04 / A-FU-025、tech-plan §10「预览」：文件预览短时链接契约
 * （后端 `com.crdp.followup.file.api.FileController` / `FilePreviewController`，docs/api/report.md §1）。
 *
 * 内容经应用代理按流返回、不缓存：登录态换 `preview-url`（≤ 5 分钟、每次预览写审计），
 * 浏览器子资源（pdf.js、docx-preview、原生 audio 带不了登录令牌）凭返回的短时链接取流。 前端不缓存该链接，每次预览重新获取。
 */

/** GET /api/hospital/files/{fileId}/preview-url 的返回。 */
export interface FilePreviewGrant {
  /** 链接过期时刻（epoch 秒）。 */
  expiresAt: number;
  /** 后端真实类型目录编码（pdf/jpg/png/docx/xlsx/mp3/m4a/wav/amr）。 */
  format: string;
  /** 浏览器原生可播 / 可预览：false = AMR（不能直接播放）或 XLSX（只能下载）。 */
  inlinePlayable: boolean;
  mime?: null | string;
  originalName: string;
  /** 短时有效（≤ 5 分钟）的取流路径（相对路径，拼 API 基地址后直用，如 <audio :src>）。 */
  url: string;
}

/** 预览链接是否已过期（本地时钟粗判；过期重新取链接，不用旧链接硬拉）。 */
export function previewGrantExpired(
  grant: FilePreviewGrant,
  now = Date.now(),
): boolean {
  return grant.expiresAt * 1000 <= now;
}

/**
 * POST /api/hospital/files/{fileId}/download-url 的返回（E10-T05，followup:scan:download
 * 敏感权限；5 分钟 HMAC 签名链接，每次签发写 DOWNLOAD 审计）。AMR 录音与 XLSX 只走下载。
 */
export interface FileDownloadGrant {
  expiresAtEpochSecond: number;
  format: string;
  mime: string;
  originalName: string;
  sizeBytes: number;
  /** 签名下载路径（相对路径，拼 API 基地址后 window.location 打开）。 */
  url: string;
}
