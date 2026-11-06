// E16-T06 / A-ATT-005、C-SCAN-015（components.md §12 WatermarkPreview）：
// 全屏查看器业务组件——打开时换当前文件的预览短时链接（每次签发写审计，会话内
// 不重复签发）、翻页换链接、关闭 / 下载事件透传、签发失败给重试。
import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import WatermarkPreview from './watermark-preview.vue';

const getFilePreviewUrl = vi.fn();

vi.mock('#/api/core/file', () => ({
  getFilePreviewUrl: (...args: unknown[]) => getFilePreviewUrl(...args),
}));

const viewer = {
  account: 'doctor.demo',
  displayName: '王医生（示例）',
  tenantName: '医院 A（示例租户）',
};

const FILES = [
  { fileId: '11', format: 'jpg', name: '超声-第1页（示例）.jpg' },
  { fileId: '12', format: 'pdf', name: '超声报告（示例）.pdf' },
  { fileId: '13', format: 'mp3', name: '通话录音（示例）.mp3' },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

function flush(frames = 2) {
  return new Promise((resolve) => setTimeout(resolve, 25 * frames));
}

async function mountPreview(props: Record<string, unknown> = {}) {
  getFilePreviewUrl.mockImplementation(async (fileId: string) => ({
    expiresAt: 4_102_444_800,
    format: 'jpg',
    inlinePlayable: true,
    mime: 'image/jpeg',
    originalName: '超声（示例）.jpg',
    url: `/api/hospital/files/preview/token-${fileId}`,
  }));
  const wrapper = mount(WatermarkPreview, {
    attachTo: document.body,
    props: {
      canDownload: true,
      files: FILES,
      open: true,
      viewer,
      ...props,
    },
  });
  mounted.push(wrapper);
  await flush();
  return wrapper;
}

function fullscreen(): HTMLElement | null {
  return document.body.querySelector('[data-testid="fullscreen-viewer"]');
}

function clickButton(label: string) {
  const button = [...document.body.querySelectorAll('button')].find((item) =>
    (item.textContent ?? '').replaceAll(' ', '').includes(label),
  );
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  return button;
}

describe('e16-t06 WatermarkPreview（components.md §12 契约）', () => {
  it('打开：换当前文件短时链接并进入全屏查看器（图片直出）', async () => {
    await mountPreview();
    expect(getFilePreviewUrl).toHaveBeenCalledWith('11');
    const img = fullscreen()?.querySelector('img');
    expect(img?.getAttribute('src')).toBe(
      '/api/hospital/files/preview/token-11',
    );
    // 水印不可关闭（遮罩根上恒有水印层）
    expect(fullscreen()?.querySelector('[data-watermark]')).toBeTruthy();
  });

  it('翻页：update:index 后换下一份的短时链接（会话内回到已看过的份不重复签发）', async () => {
    const wrapper = await mountPreview();
    clickButton('下一份');
    await wrapper.setProps({ index: 1 });
    await flush();
    expect(getFilePreviewUrl).toHaveBeenNthCalledWith(2, '12');

    clickButton('上一份');
    await wrapper.setProps({ index: 0 });
    await flush();
    // 回到第 1 份复用会话内链接，不再次签发（审计只在签发时写一次）
    expect(getFilePreviewUrl).toHaveBeenCalledTimes(2);
    const img = fullscreen()?.querySelector('img');
    expect(img?.getAttribute('src')).toBe(
      '/api/hospital/files/preview/token-11',
    );
  });

  it('关闭：close 透传为 update:open=false', async () => {
    const wrapper = await mountPreview();
    clickButton('关闭');
    expect(wrapper.emitted('update:open')).toEqual([[false]]);
  });

  it('下载：透传 download（带 fileId，父级换签名链接）', async () => {
    const wrapper = await mountPreview();
    clickButton('下载');
    expect(wrapper.emitted('download')?.[0]).toEqual(['11']);
  });

  it('短时链接签发失败：提示并可重试', async () => {
    getFilePreviewUrl.mockImplementation(async () => {
      throw new Error('boom');
    });
    const wrapper = mount(WatermarkPreview, {
      attachTo: document.body,
      props: { canDownload: false, files: FILES, open: true, viewer },
    });
    mounted.push(wrapper);
    await flush();
    expect(wrapper.text()).toContain('预览链接获取失败');

    getFilePreviewUrl.mockImplementation(async (fileId: string) => ({
      expiresAt: 4_102_444_800,
      format: 'jpg',
      inlinePlayable: true,
      mime: 'image/jpeg',
      originalName: '超声（示例）.jpg',
      url: `/api/hospital/files/preview/token-${fileId}`,
    }));
    clickButton('重试');
    await flush();
    expect(getFilePreviewUrl).toHaveBeenCalledTimes(2);
    expect(fullscreen()?.querySelector('img')).toBeTruthy();
  });

  it('未打开时不渲染全屏查看器', async () => {
    await mountPreview({ open: false });
    expect(fullscreen()).toBeNull();
    expect(getFilePreviewUrl).not.toHaveBeenCalled();
  });
});
