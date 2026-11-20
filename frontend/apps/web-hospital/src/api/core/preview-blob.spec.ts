import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  previewBlob,
  releasePreviewUrls,
  trackPreviewUrls,
} from './preview-blob';

const state = vi.hoisted(() => ({
  access: null as null | { accessToken: string },
  user: null as null | { userInfo: { permissionVersion: number } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => state.user,
}));
vi.mock('@crdp/viewer', () => ({
  PREVIEW_REVOKED_EVENT: 'crdp:file-preview-revoked',
}));
const NativeURL = URL;
const created: string[] = [];
const revoke = vi.fn();
const grant = () => ({
  url: '/api/hospital/files/preview/opaque-grant',
  expiresAtEpochSecond: Math.floor(Date.now() / 1000) + 60,
  format: 'pdf',
  mime: 'application/pdf',
  originalName: '报告（示例）.pdf',
  inlinePlayable: true,
});
beforeEach(() => {
  state.access = reactive({ accessToken: 'session-example' });
  state.user = reactive({ userInfo: { permissionVersion: 1 } });
  vi.stubGlobal(
    'URL',
    class extends NativeURL {
      static override createObjectURL() {
        const url = `blob:preview-${created.length}`;
        created.push(url);
        return url;
      }
      static override revokeObjectURL(url: string) {
        revoke(url);
      }
    },
  );
});
afterEach(() => {
  releasePreviewUrls([...created]);
  created.length = 0;
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  vi.useRealTimers();
});
describe('oqr-07 鉴权预览内存生命周期', () => {
  it('只暴露内存 URL，关闭后撤销且不缓存服务端授权', async () => {
    const input = grant();
    const load = vi.fn(async () => new Blob(['watermarked']));
    const result = await previewBlob(input, load);
    expect(load).toHaveBeenCalledWith(input.url);
    expect(result.url).toMatch(/^blob:/);
    expect(result.url).not.toContain('opaque-grant');
    releasePreviewUrls([result.url]);
    expect(revoke).toHaveBeenCalledWith(result.url);
  });
  it('账号在请求期间变化，晚返回的内容不得显示', async () => {
    let complete: ((blob: Blob) => void) | undefined;
    const pending = previewBlob(
      grant(),
      () =>
        new Promise<Blob>((resolve) => {
          complete = resolve;
        }),
    );
    if (state.access) state.access.accessToken = 'another-session';
    complete?.(new Blob(['old-view']));
    await expect(pending).rejects.toThrow('会话已变化');
    expect(created).toHaveLength(0);
  });
  it('权限版本变化同时清除缩略图和已加载内存 URL', async () => {
    const result = await previewBlob(
      grant(),
      async () => new Blob(['watermarked']),
    );
    const widget = defineComponent({
      setup() {
        const urls = ref({ example: result.url });
        trackPreviewUrls(urls);
        return { urls };
      },
      template: '<div><img v-for="url in urls" :key="url" :src="url" /></div>',
    });
    const wrapper = mount(widget);
    expect(wrapper.find('img').exists()).toBe(true);
    if (state.user) state.user.userInfo.permissionVersion = 2;
    await flushPromises();
    expect(revoke).toHaveBeenCalledWith(result.url);
    expect(wrapper.find('img').exists()).toBe(false);
    wrapper.unmount();
  });
  it('到期撤销，过期响应不会创建新对象 URL', async () => {
    vi.useFakeTimers();
    const result = await previewBlob(
      grant(),
      async () => new Blob(['watermarked']),
    );
    await vi.advanceTimersByTimeAsync(61_000);
    expect(revoke).toHaveBeenCalledWith(result.url);
    const expired = { ...grant(), expiresAtEpochSecond: 0 };
    await expect(
      previewBlob(expired, async () => new Blob(['old'])),
    ).rejects.toThrow('过期');
  });
});
