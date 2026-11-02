import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import FilePreview from './file-preview.vue';

const viewer = {
  account: 'doctor.demo',
  displayName: '王医生（示例）',
  tenantName: '医院 A（示例租户）',
};

function mountPreview(props: Record<string, unknown>) {
  return mount(FilePreview, {
    props: {
      url: '/api/hospital/files/preview/token-1',
      viewer,
      ...props,
    },
  });
}

describe('e10-t04 FilePreview（水印不可关闭；MP3 原生播放；AMR 仅下载）', () => {
  it('mP3：原生 audio 播放器，控件里没有下载按钮（下载走权限按钮）', () => {
    const wrapper = mountPreview({
      file: {
        fileId: 9,
        format: 'mp3',
        mime: 'audio/mpeg',
        name: '通话录音（示例）.mp3',
      },
    });
    const audio = wrapper.find('audio');
    expect(audio.exists()).toBe(true);
    expect(audio.attributes('controls')).toBeDefined();
    expect(audio.attributes('controlslist')).toBe('nodownload');
    expect(audio.attributes('src')).toBe('/api/hospital/files/preview/token-1');
    // 播放器内不放下载按钮（components.md §13 AudioPlayer）
    expect(wrapper.find('button').exists()).toBe(false);
  });

  it('aMR：不渲染播放器，只提示下载；无下载权限时不出现下载按钮', () => {
    const wrapper = mountPreview({
      canDownload: false,
      file: {
        fileId: 9,
        format: 'amr',
        mime: 'audio/amr',
        name: '通话录音（示例）.amr',
      },
    });
    expect(wrapper.find('audio').exists()).toBe(false);
    expect(wrapper.text()).toContain('AMR');
    expect(wrapper.text()).toContain('下载');
    expect(wrapper.find('button').exists()).toBe(false);
  });

  it('aMR：有下载权限时显示下载按钮并抛出 download 事件', async () => {
    const wrapper = mountPreview({
      canDownload: true,
      file: { fileId: 9, format: 'amr', name: '通话录音（示例）.amr' },
    });
    const button = wrapper.find('button');
    expect(button.exists()).toBe(true);
    await button.trigger('click');
    expect(wrapper.emitted('download')?.[0]?.[0]).toMatchObject({ fileId: 9 });
  });

  it('图片：img 直出预览代理链接', () => {
    const wrapper = mountPreview({
      file: {
        fileId: 9,
        format: 'jpg',
        mime: 'image/jpeg',
        name: '超声（示例）.jpg',
      },
    });
    const img = wrapper.find('img');
    expect(img.exists()).toBe(true);
    expect(img.attributes('src')).toBe('/api/hospital/files/preview/token-1');
    expect(img.attributes('alt')).toBe('超声（示例）.jpg');
  });

  it('任何模式都带水印层（不可关闭），且不响应点击', () => {
    const wrapper = mountPreview({
      file: { fileId: 9, format: 'mp3', name: '通话录音（示例）.mp3' },
    });
    const overlay = wrapper.find('[data-watermark]');
    expect(overlay.exists()).toBe(true);
    expect(overlay.attributes('style')).toContain('pointer-events: none');
  });

  it('水印节点被删后重绘（组件内防篡改）', async () => {
    const wrapper = mountPreview({
      file: { fileId: 9, format: 'mp3', name: '通话录音（示例）.mp3' },
    });
    (wrapper.element.querySelector('[data-watermark]') as HTMLElement).remove();
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(wrapper.find('[data-watermark]').exists()).toBe(true);
  });
});
