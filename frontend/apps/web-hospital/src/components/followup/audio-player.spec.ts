// A-FU-025（E15-T04 · components.md §13 AudioPlayer：原生 audio、nodownload、AMR 只下载）
import type { ContactAudioFile } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import AudioPlayer from './audio-player.vue';

vi.mock('#/api/core/file', () => ({
  getFileDownloadUrl: vi.fn().mockResolvedValue({
    url: '/api/hospital/files/download/signed-token',
  }),
  getFilePreviewUrl: vi.fn().mockResolvedValue({
    expiresAt: Date.now() / 1000 + 300,
    format: 'mp3',
    inlinePlayable: true,
    mime: 'audio/mpeg',
    originalName: '通话录音（示例）.mp3',
    url: '/api/hospital/files/preview/token-1',
  }),
}));

function mp3(): ContactAudioFile {
  return {
    fileId: '9',
    format: 'mp3',
    inlinePlayable: true,
    mimeType: 'audio/mpeg',
    originalName: '通话录音（示例）.mp3',
    sizeBytes: 2048,
  };
}

function amr(): ContactAudioFile {
  return {
    fileId: '10',
    format: 'amr',
    inlinePlayable: false,
    mimeType: 'audio/amr',
    originalName: '通话录音（示例）.amr',
    sizeBytes: 1024,
  };
}

describe('e15-t04 AudioPlayer（MP3 原生播放；播放器里不放下载按钮）', () => {
  it('mP3：点击「播放录音」换短时链接后渲染原生 audio，controlsList=nodownload、无下载按钮', async () => {
    const wrapper = mount(AudioPlayer, { props: { audio: mp3() } });
    const button = wrapper.find('button');
    expect(button.text()).toContain('播放录音');
    await button.trigger('click');
    await vi.waitFor(() => {
      expect(wrapper.find('audio').exists()).toBe(true);
    });
    const audio = wrapper.find('audio');
    expect(audio.attributes('src')).toBe('/api/hospital/files/preview/token-1');
    expect(audio.attributes('controlslist')).toBe('nodownload');
    expect(audio.attributes('controls')).toBeDefined();
    // 播放器里不放下载按钮（components.md §13：下载走 followup:scan:download）
    expect(wrapper.findAll('button').length).toBe(0);
  });

  it('aMR：不渲染播放器，提示不能在线播放；无下载权限时不出现下载按钮', () => {
    const wrapper = mount(AudioPlayer, {
      props: { audio: amr(), canDownload: false },
    });
    expect(wrapper.find('audio').exists()).toBe(false);
    expect(wrapper.text()).toContain('AMR，不能在线播放');
    expect(wrapper.find('[data-testid="audio-download-10"]').exists()).toBe(
      false,
    );
  });

  it('aMR + 下载权限：出现下载按钮，点击走签名下载链接', async () => {
    const wrapper = mount(AudioPlayer, {
      props: { audio: amr(), canDownload: true },
    });
    const download = wrapper.find('[data-testid="audio-download-10"]');
    expect(download.exists()).toBe(true);
    const assign = vi.fn();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, assign },
    });
    await download.trigger('click');
    await vi.waitFor(() => {
      expect(assign).toHaveBeenCalledWith(
        '/api/hospital/files/download/signed-token',
      );
    });
  });
});
