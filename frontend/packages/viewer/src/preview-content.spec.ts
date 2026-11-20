import { flushPromises, mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import PreviewContent from './preview-content.vue';
import { PREVIEW_REVOKED_EVENT } from './preview-events';
const render = vi.hoisted(() => vi.fn());
vi.mock('./renderers', () => ({
  renderPdfDocument: (...args: unknown[]) => render(...args),
  renderDocxDocument: (...args: unknown[]) => render(...args),
}));
describe('oqr-07 已加载内容的撤销与过时响应', () => {
  it('撤销图片内存地址时立即移除图像', async () => {
    const wrapper = mount(PreviewContent, {
      props: { file: { fileId: '1', format: 'png' }, url: 'blob:example' },
    });
    expect(wrapper.find('img').exists()).toBe(true);
    window.dispatchEvent(
      new CustomEvent(PREVIEW_REVOKED_EVENT, { detail: 'blob:example' }),
    );
    await flushPromises();
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.text()).toContain('预览已失效');
    wrapper.unmount();
  });
  it('旧文档晚完成不能覆盖新文件，撤销后清空画布', async () => {
    const pending = new Map<string, () => void>();
    render.mockImplementation(
      (host: HTMLElement, url: string) =>
        new Promise<void>((resolve) => {
          pending.set(url, () => {
            host.textContent = url;
            resolve();
          });
        }),
    );
    const wrapper = mount(PreviewContent, {
      props: { file: { fileId: '1', format: 'pdf' }, url: 'blob:old' },
    });
    await wrapper.setProps({ url: 'blob:new' });
    pending.get('blob:new')?.();
    await flushPromises();
    expect(wrapper.text()).toContain('blob:new');
    pending.get('blob:old')?.();
    await flushPromises();
    expect(wrapper.text()).not.toContain('blob:old');
    window.dispatchEvent(
      new CustomEvent(PREVIEW_REVOKED_EVENT, { detail: 'blob:new' }),
    );
    await flushPromises();
    expect(wrapper.text()).not.toContain('blob:new');
    expect(wrapper.text()).toContain('预览已失效');
    wrapper.unmount();
  });
});
