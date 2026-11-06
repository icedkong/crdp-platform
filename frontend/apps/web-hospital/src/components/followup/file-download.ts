/**
 * E16-T06：报告单文件下载动作（A-ATT-006 / C-SCAN-016 的前端侧）——换 5 分钟
 * HMAC 签名链接（每次签发写 DOWNLOAD 审计，E10-T05）后交给浏览器下载。
 * WatermarkPreview 只抛 download 事件，各入口（全院列表 / 上传对话框）共用本函数；
 * 失败返回 false，调用方按提示文案提示（与 AudioPlayer 口径一致）。
 */
import { getFileDownloadUrl } from '#/api/core/file';

export async function downloadFileById(
  fileId: number | string,
): Promise<boolean> {
  try {
    const grant = await getFileDownloadUrl(fileId);
    window.location.assign(grant.url);
    return true;
  } catch {
    return false;
  }
}
