/**
 * E24-T06 / NFR-RESP-003（design-system §12 拍照上传、pages.md §6.1、tokens
 * imageUpload）：图片上传前压缩——长边 ≤ 2560px、转 JPEG（含 HEIC）、去掉
 * EXIF（含 GPS 定位）。
 *
 * 去元数据的方式是**画布重编码**：canvas 编码出的 JPEG 不携带任何 EXIF（含
 * GPS / 方向 / 拍摄信息），无需逐段解析；竖拍方向（Orientation）用
 * createImageBitmap 的 imageOrientation: 'from-image' 在解码时摆正，避免重编码
 * 后躺倒。HEIC：浏览器解不开时懒加载 heic2any（libheif）转 JPEG 再走画布；
 * 仍解不开按原文件返回并给 note（后端真实类型白名单兜底，AGENTS §5 前端预检
 * 不替代服务端校验）。非图片（PDF / 录音等）原样通过。
 */
import { imageUpload } from '@crdp/tokens';

export interface ImageCompressResult {
  /** true = 走了画布重编码（长边压限 + JPEG + 去 EXIF）。 */
  compressed: boolean;
  file: File;
  /** 'undecodable' = 当前浏览器解不开该图片（HEIC 转换器也失败或文件损坏）。 */
  note?: 'undecodable';
}

/** 参与压缩的图片扩展名（相机 / 相册常见；GIF 动图重编码会丢动画，不处理）。 */
const IMAGE_EXTENSIONS = new Set([
  'bmp',
  'heic',
  'heif',
  'jpeg',
  'jpg',
  'png',
  'webp',
]);

const HEIC_EXTENSIONS = new Set(['heic', 'heif']);
const HEIC_MIME_PREFIX = 'image/heic';

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot === -1 ? '' : name.slice(dot + 1).toLowerCase();
}

/** 是否参与上传前压缩：MIME 是 image/*（gif 除外）或扩展名命中。 */
export function isCompressibleImage(file: File): boolean {
  if (file.type === 'image/gif') return false;
  if (file.type.startsWith('image/')) return true;
  return IMAGE_EXTENSIONS.has(extensionOf(file.name));
}

/** HEIC / HEIF 判定（相机导出常见 type 为空，扩展名兜底）。 */
export function isHeicFile(file: File): boolean {
  if (file.type.toLowerCase().startsWith(HEIC_MIME_PREFIX)) return true;
  if (file.type === 'image/heif') return true;
  return HEIC_EXTENSIONS.has(extensionOf(file.name));
}

/** 输出文件名换成 .jpg（保留主名与多处圆点；无扩展名直接补）。 */
export function jpegFileName(name: string): string {
  const dot = name.lastIndexOf('.');
  const base = dot > 0 ? name.slice(0, dot) : name;
  return `${base}.jpg`;
}

/** 目标尺寸：长边压到 maxLongEdge、短边按比例（四舍五入）；未超限原样（只缩不放）。 */
export function targetDimensions(
  width: number,
  height: number,
  maxLongEdge: number,
): { height: number; width: number } {
  const longEdge = Math.max(width, height);
  if (longEdge <= maxLongEdge || longEdge === 0) {
    return { height, width };
  }
  const ratio = maxLongEdge / longEdge;
  return {
    height: Math.round(height * ratio),
    width: Math.round(width * ratio),
  };
}

/** 画布编码 JPEG（toBlob 的 Promise 包装）。 */
function canvasToJpeg(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob(
      (blob) => resolve(blob),
      imageUpload.mimeType,
      imageUpload.quality,
    );
  });
}

/** 解码为位图；HEIC 解不开时懒加载 heic2any 先转 JPEG（libheif 打包为独立懒加载块）。 */
async function decodeBitmap(file: File): Promise<ImageBitmap | undefined> {
  try {
    return await createBitmap(file);
  } catch {
    if (!isHeicFile(file)) return undefined;
  }
  try {
    const { default: heic2any } = await import('heic2any');
    const converted = await heic2any(file, { toType: 'image/jpeg' });
    const blob = Array.isArray(converted) ? converted[0] : converted;
    return await createBitmap(blob);
  } catch {
    return undefined;
  }
}

async function createBitmap(source: Blob): Promise<ImageBitmap> {
  return await createImageBitmap(source, {
    imageOrientation: 'from-image',
  });
}

/**
 * 压缩一张图片：非图片原样通过；图片画布重编码为 JPEG（长边 ≤ 2560、质量 0.85、
 * EXIF 全部丢弃）。解码失败（浏览器不支持且 HEIC 转换器也失败 / 文件损坏）按
 * 原文件返回并标 note='undecodable'——调用方提示用户，后端真实类型校验兜底。
 */
export async function compressImageFile(
  file: File,
): Promise<ImageCompressResult> {
  if (!isCompressibleImage(file)) {
    return { compressed: false, file };
  }
  const bitmap = await decodeBitmap(file);
  if (!bitmap) {
    return { compressed: false, file, note: 'undecodable' };
  }
  try {
    const { width, height } = targetDimensions(
      bitmap.width,
      bitmap.height,
      imageUpload.maxLongEdge,
    );
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) {
      return { compressed: false, file, note: 'undecodable' };
    }
    context.drawImage(bitmap, 0, 0, width, height);
    const blob = await canvasToJpeg(canvas);
    if (!blob) {
      return { compressed: false, file, note: 'undecodable' };
    }
    const jpeg = new File([blob], jpegFileName(file.name), {
      lastModified: file.lastModified,
      type: imageUpload.mimeType,
    });
    return { compressed: true, file: jpeg };
  } finally {
    bitmap.close?.();
  }
}
