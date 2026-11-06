import { afterEach, describe, expect, it, vi } from 'vitest';

// E16-T06：下载文件——换 5 分钟签名链接（POST download-url，每次签发写 DOWNLOAD
// 审计）后交给浏览器下载；失败返回 false 由调用方提示（与 AudioPlayer 同口径）。
import { downloadFileById } from './file-download';

const getFileDownloadUrl = vi.fn();

vi.mock('#/api/core/file', () => ({
  getFileDownloadUrl: (...args: unknown[]) => getFileDownloadUrl(...args),
}));

const assign = vi.fn();

afterEach(() => {
  vi.clearAllMocks();
});

describe('e16-t06 downloadFileById', () => {
  it('换签名链接后交给浏览器下载', async () => {
    getFileDownloadUrl.mockResolvedValue({
      url: '/api/hospital/files/download/signed-token',
    });
    vi.stubGlobal('window', { location: { assign } });

    const ok = await downloadFileById('11');

    expect(getFileDownloadUrl).toHaveBeenCalledWith('11');
    expect(assign).toHaveBeenCalledWith(
      '/api/hospital/files/download/signed-token',
    );
    expect(ok).toBe(true);
    vi.unstubAllGlobals();
  });

  it('签发失败返回 false（不跳转），由调用方提示', async () => {
    getFileDownloadUrl.mockRejectedValue(new Error('40300'));
    vi.stubGlobal('window', { location: { assign } });

    const ok = await downloadFileById('11');

    expect(ok).toBe(false);
    expect(assign).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});
