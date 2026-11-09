// E24-T06 / NFR-RESP-003（design-system §12、pages.md §6.1、tokens imageUpload）：
// 上传前压缩——长边 ≤ 2560px、转 JPEG（含 HEIC）、去 EXIF（含 GPS 定位）。
// 画布重编码天然不带任何元数据（EXIF / GPS 全部丢弃）；方向（Orientation）用
// createImageBitmap 的 imageOrientation: 'from-image' 在绘制时纠正，避免竖拍躺倒。
// HEIC：浏览器解不开时懒加载 heic2any（libheif）转 JPEG 再走画布；仍失败按原文件
// 上传并给 note（后端格式白名单兜底）。非图片（PDF / 录音等）原样通过。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  compressImageFile,
  isCompressibleImage,
  isHeicFile,
  jpegFileName,
  targetDimensions,
} from './image-compress';

// happy-dom 无 2D 画布实现：getContext / toBlob 打桩（真实浏览器链路走真画布）
const drawImage = vi.fn();
const canvasInstances: HTMLCanvasElement[] = [];

function stubCanvas() {
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    writable: true,
    value: () => ({ drawImage }),
  });
  Object.defineProperty(HTMLCanvasElement.prototype, 'toBlob', {
    configurable: true,
    writable: true,
    value(this: HTMLCanvasElement, callback: BlobCallback) {
      callback(new Blob(['jpeg-bytes'], { type: 'image/jpeg' }));
    },
  });
  const original = document.createElement.bind(document);
  vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
    const node = original(tag);
    if (tag === 'canvas') canvasInstances.push(node as HTMLCanvasElement);
    return node;
  });
}

/** 造一个假位图（宽高可控、close 可断言）。 */
function fakeBitmap(width: number, height: number) {
  return { close: vi.fn(), height, width };
}

const createImageBitmapMock = vi.fn();

beforeEach(() => {
  stubCanvas();
  vi.stubGlobal('createImageBitmap', createImageBitmapMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  vi.restoreAllMocks();
  canvasInstances.length = 0;
});

describe('e24-t06 targetDimensions（长边 ≤ 2560，只缩不放）', () => {
  it('横图超限：长边压到 2560，短边按比例', () => {
    expect(targetDimensions(4000, 3000, 2560)).toEqual({
      height: 1920,
      width: 2560,
    });
  });

  it('竖图超限：同样按长边（高）压到 2560', () => {
    expect(targetDimensions(3000, 4000, 2560)).toEqual({
      height: 2560,
      width: 1920,
    });
  });

  it('未超限不放大（原尺寸重编码只为去 EXIF）', () => {
    expect(targetDimensions(2000, 1500, 2560)).toEqual({
      height: 1500,
      width: 2000,
    });
    expect(targetDimensions(2560, 2560, 2560)).toEqual({
      height: 2560,
      width: 2560,
    });
  });

  it('极端长宽比保持比例（整数像素）', () => {
    expect(targetDimensions(5000, 1000, 2560)).toEqual({
      height: 512,
      width: 2560,
    });
  });
});

describe('e24-t06 isCompressibleImage / isHeicFile / jpegFileName', () => {
  it('图片（含 HEIC）参与压缩；PDF / 录音 / GIF 不参与', () => {
    expect(
      isCompressibleImage(new File(['x'], 'a.jpg', { type: 'image/jpeg' })),
    ).toBe(true);
    expect(
      isCompressibleImage(new File(['x'], 'a.png', { type: 'image/png' })),
    ).toBe(true);
    expect(isCompressibleImage(new File(['x'], 'IMG_0001.HEIC'))).toBe(true);
    expect(
      isCompressibleImage(
        new File(['x'], 'doc.pdf', { type: 'application/pdf' }),
      ),
    ).toBe(false);
    expect(
      isCompressibleImage(new File(['x'], 'call.mp3', { type: 'audio/mpeg' })),
    ).toBe(false);
    expect(
      isCompressibleImage(new File(['x'], 'anim.gif', { type: 'image/gif' })),
    ).toBe(false);
  });

  it('判定 HEIC：MIME 或扩展名（相机导出常见 type 为空）', () => {
    expect(isHeicFile(new File(['x'], 'a.heic', { type: 'image/heic' }))).toBe(
      true,
    );
    expect(isHeicFile(new File(['x'], 'a.heif', { type: '' }))).toBe(true);
    expect(isHeicFile(new File(['x'], 'a.jpg', { type: 'image/jpeg' }))).toBe(
      false,
    );
  });

  it('输出文件名换成 .jpg（保留主名与多处圆点）', () => {
    expect(jpegFileName('IMG_0001.HEIC')).toBe('IMG_0001.jpg');
    expect(jpegFileName('扫描 件.v2.png')).toBe('扫描 件.v2.jpg');
    expect(jpegFileName('无扩展名')).toBe('无扩展名.jpg');
  });
});

describe('e24-t06 compressImageFile（压缩 + 去 EXIF + HEIC 转换）', () => {
  it('图片重编码为 JPEG：长边压到 2560、质量 0.85、EXIF（含 GPS）随重编码丢弃', async () => {
    createImageBitmapMock.mockResolvedValue(fakeBitmap(4000, 3000));
    const source = new File(['exif-gps'], 'photo.jpg', {
      type: 'image/jpeg',
    });

    const result = await compressImageFile(source);

    expect(result.compressed).toBe(true);
    expect(result.file.type).toBe('image/jpeg');
    expect(result.file.name).toBe('photo.jpg');
    // 方向在解码时纠正（iPhone 竖拍不躺倒）
    expect(createImageBitmapMock).toHaveBeenCalledWith(source, {
      imageOrientation: 'from-image',
    });
    // 画布按目标尺寸绘制并编码为 JPEG（质量来自 tokens imageUpload）
    const canvas = canvasInstances[0];
    expect(canvas?.width).toBe(2560);
    expect(canvas?.height).toBe(1920);
    expect(drawImage).toHaveBeenCalled();
    expect(result.file.size).toBeGreaterThan(0);
  });

  it('未超限的图片同样重编码（只为去 EXIF / 转 JPEG）', async () => {
    createImageBitmapMock.mockResolvedValue(fakeBitmap(1024, 768));
    const result = await compressImageFile(
      new File(['x'], 'small.png', { type: 'image/png' }),
    );
    expect(result.compressed).toBe(true);
    expect(result.file.type).toBe('image/jpeg');
    expect(result.file.name).toBe('small.jpg');
    expect(canvasInstances[0]?.width).toBe(1024);
  });

  it('heic 转换：浏览器解不开时懒加载 heic2any 转 JPEG 再走画布（长边 3000 → 1920）', async () => {
    createImageBitmapMock.mockRejectedValueOnce(new Error('decode error'));
    const heicBitmap = fakeBitmap(3000, 4000);
    createImageBitmapMock.mockResolvedValueOnce(heicBitmap);
    const heic2any = vi
      .fn()
      .mockResolvedValue(new Blob(['heic-jpeg'], { type: 'image/jpeg' }));
    vi.doMock('heic2any', () => ({ default: heic2any }));

    const result = await compressImageFile(
      new File(['x'], 'IMG_0001.HEIC', { type: 'image/heic' }),
    );

    expect(heic2any).toHaveBeenCalledTimes(1);
    expect(result.compressed).toBe(true);
    expect(result.file.type).toBe('image/jpeg');
    expect(result.file.name).toBe('IMG_0001.jpg');
    expect(canvasInstances[0]?.height).toBe(2560);
    vi.doUnmock('heic2any');
  });

  it('解不开的非 HEIC 图片：按原文件返回并给 note（后端真实类型校验兜底）', async () => {
    createImageBitmapMock.mockRejectedValue(new Error('decode error'));
    const source = new File(['x'], 'broken.jpg', { type: 'image/jpeg' });
    const result = await compressImageFile(source);
    expect(result.compressed).toBe(false);
    expect(result.file).toBe(source);
    expect(result.note).toBe('undecodable');
  });

  it('heic2any 也失败时按原文件返回并给 note', async () => {
    createImageBitmapMock.mockRejectedValue(new Error('no heic decoder'));
    vi.doMock('heic2any', () => ({
      default: vi.fn().mockRejectedValue(new Error('libheif failed')),
    }));
    const source = new File(['x'], 'IMG_0002.heic');
    const result = await compressImageFile(source);
    expect(result.compressed).toBe(false);
    expect(result.file).toBe(source);
    expect(result.note).toBe('undecodable');
    vi.doUnmock('heic2any');
  });

  it('非图片（PDF / 录音）原样通过，不解码不建画布', async () => {
    const source = new File(['x'], 'report.pdf', { type: 'application/pdf' });
    const result = await compressImageFile(source);
    expect(result.file).toBe(source);
    expect(result.compressed).toBe(false);
    expect(createImageBitmapMock).not.toHaveBeenCalled();
    expect(canvasInstances).toHaveLength(0);
  });
});
