// E16-T06 / A-ATT-005、C-SCAN-015：全屏查看器外壳——深色全屏遮罩 + 顶部工具栏
// （下载 / 旋转 / 翻转 / 缩小 / 放大 / 关闭 + 多文件翻页），水印不可关闭；
// 内容渲染复用 PreviewContent（图片 / PDF / DOCX / 音频 / 仅下载）。
import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import FullscreenViewer from './fullscreen-viewer.vue';

// pdf.js / docx-preview 懒加载渲染在单测里以替身注入（真实链路由 E10-T04 测试覆盖）
vi.mock('./renderers', () => ({
  renderDocxDocument: vi.fn().mockResolvedValue(undefined),
  renderPdfDocument: vi.fn().mockResolvedValue(1),
}));

const viewer = {
  account: 'doctor.demo',
  displayName: '王医生（示例）',
  tenantName: '医院 A（示例租户）',
};

const IMAGE = {
  fileId: 11,
  format: 'jpg',
  mime: 'image/jpeg',
  name: '超声（示例）.jpg',
};
const AUDIO = {
  fileId: 12,
  format: 'mp3',
  mime: 'audio/mpeg',
  name: '通话录音（示例）.mp3',
};

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

function mountViewer(props: Record<string, unknown> = {}) {
  const wrapper = mount(FullscreenViewer, {
    attachTo: document.body,
    props: {
      file: IMAGE,
      url: '/api/hospital/files/preview/token-1',
      viewer,
      ...props,
    },
  });
  mounted.push(wrapper);
  return wrapper;
}

function toolbarButton(label: string) {
  // antd Button 在两个汉字间自动插入空格（旋 转），匹配前先去掉全部空白
  return [...document.body.querySelectorAll('button')].find((item) =>
    (item.textContent ?? '').replaceAll(' ', '').includes(label),
  );
}

async function click(label: string) {
  toolbarButton(label)?.dispatchEvent(
    new MouseEvent('click', { bubbles: true }),
  );
  await Promise.resolve();
}

function stageStyle(): string {
  const node = document.body.querySelector('[data-testid="viewer-stage"]');
  return (node as HTMLElement | null)?.style.transform ?? '';
}

describe('e16-t06 FullscreenViewer（全屏查看器）', () => {
  it('图片：页内预览 + 工具栏齐全；无下载权限不显示「下载」', () => {
    mountViewer();
    const img = document.body.querySelector('.viewer-canvas img');
    expect(img?.getAttribute('src')).toBe(
      '/api/hospital/files/preview/token-1',
    );
    for (const label of ['旋转', '翻转', '缩小', '放大', '关闭']) {
      expect(toolbarButton(label)).toBeTruthy();
    }
    expect(toolbarButton('下载')).toBeFalsy();
  });

  it('有下载权限时「下载」可见并抛出 download（带当前文件）', async () => {
    const wrapper = mountViewer({ canDownload: true });
    await click('下载');
    expect(wrapper.emitted('download')?.[0]?.[0]).toMatchObject({
      fileId: 11,
    });
  });

  it('缩放 / 旋转 / 翻转：点击后舞台 transform 变化，百分比随动', async () => {
    mountViewer();
    expect(stageStyle()).toBe('none');

    await click('放大');
    expect(stageStyle()).toContain('scale(1.25)');
    expect(document.body.textContent).toContain('125%');

    await click('旋转');
    expect(stageStyle()).toContain('rotate(90deg)');

    await click('翻转');
    // 镜像在最左（最后应用）：对当前画面做水平翻转
    expect(stageStyle().startsWith('scaleX(-1)')).toBe(true);
  });

  it('缩放有上下限：放大封顶 500% 后按钮禁用，缩小触底 50% 后禁用', async () => {
    mountViewer();
    for (let i = 0; i < 20; i++) await click('放大');
    expect(document.body.textContent).toContain('500%');
    expect(toolbarButton('放大')?.hasAttribute('disabled')).toBe(true);

    for (let i = 0; i < 20; i++) await click('缩小');
    expect(document.body.textContent).toContain('50%');
    expect(toolbarButton('缩小')?.hasAttribute('disabled')).toBe(true);
  });

  it('音频：原生 audio 播放，缩放 / 旋转 / 翻转按钮禁用（不参与变换）', () => {
    mountViewer({ file: AUDIO });
    expect(document.body.querySelector('audio')).toBeTruthy();
    for (const label of ['旋转', '翻转', '缩小', '放大']) {
      expect(toolbarButton(label)?.hasAttribute('disabled')).toBe(true);
    }
  });

  // E16-T12 补：A-ATT-005「XLSX 需下载查看」在全屏外壳的形态（格式矩阵的只下载一端）
  it('xLSX：只下载提示与原因文案，变换按钮禁用；有下载权限时可从内容面下载', async () => {
    const xlsx = {
      fileId: 13,
      format: 'xlsx',
      mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      name: '检验汇总（示例）.xlsx',
    };
    mountViewer({ file: xlsx });
    expect(document.body.textContent).toContain(
      'Excel 工作簿暂不支持在线预览，请下载查看',
    );
    expect(document.body.textContent).toContain('无下载权限时无法获取该文件');
    for (const label of ['旋转', '翻转', '缩小', '放大']) {
      expect(toolbarButton(label)?.hasAttribute('disabled')).toBe(true);
    }
    expect(toolbarButton('下载')).toBeFalsy();
    for (const wrapper of mounted.splice(0)) wrapper.unmount();

    const allowed = mountViewer({ file: xlsx, canDownload: true });
    expect(allowed.emitted('download')).toBeUndefined();
    const contentDownload = [...document.body.querySelectorAll('button')].find(
      (item) => (item.textContent ?? '').replaceAll(' ', '').includes('下载'),
    );
    contentDownload?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await Promise.resolve();
    expect(allowed.emitted('download')?.[0]?.[0]).toMatchObject({ fileId: 13 });
  });

  it('翻页：上一份 / 下一份与指示文案，边界禁用；单份不显示翻页', async () => {
    const wrapper = mountViewer({ fileCount: 3, index: 0 });
    expect(document.body.textContent).toContain('第 1 / 3 份');
    expect(toolbarButton('上一份')?.hasAttribute('disabled')).toBe(true);

    await click('下一份');
    expect(wrapper.emitted('update:index')?.[0]).toEqual([1]);

    await wrapper.setProps({ index: 2 });
    expect(document.body.textContent).toContain('第 3 / 3 份');
    expect(toolbarButton('下一份')?.hasAttribute('disabled')).toBe(true);

    // 单份：先卸掉多份查看器再挂载，避免 document.body 里两份并存
    for (const wrapper of mounted.splice(0)) wrapper.unmount();
    const single = mountViewer();
    expect(toolbarButton('上一份')).toBeFalsy();
    expect(toolbarButton('下一份')).toBeFalsy();
    expect(single.text()).not.toContain('第 1 / 1 份');
  });

  it('切换文件后变换复位（每份从 100% 开始）', async () => {
    const wrapper = mountViewer({ fileCount: 2, index: 0 });
    await click('旋转');
    expect(stageStyle()).toContain('rotate(90deg)');
    await wrapper.setProps({
      file: { ...AUDIO, fileId: 13, name: '第二份（示例）.mp3' },
      index: 1,
    });
    expect(stageStyle()).toBe('none');
  });

  it('esc 与「关闭」抛出 close（由父级收起遮罩）', async () => {
    const wrapper = mountViewer();
    await click('关闭');
    expect(wrapper.emitted('close')).toHaveLength(1);

    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await Promise.resolve();
    expect(wrapper.emitted('close')).toHaveLength(2);
  });

  it('水印不可关闭：遮罩上恒有水印层且不拦截点击', () => {
    mountViewer();
    const overlay = document.body.querySelector('[data-watermark]');
    expect(overlay).toBeTruthy();
    expect((overlay as HTMLElement).style.pointerEvents).toBe('none');
  });

  it('链接解析中（url=null）显示加载态', () => {
    mountViewer({ url: null });
    expect(document.body.textContent).toContain('正在加载预览');
  });

  // ------------------- E24-T06 双指缩放（NFR-RESP-003，components.md §12 手机 / 平板）
  // happy-dom 无 TouchEvent 构造器：用基础 Event 挂 touches 触点表驱动同一处理函数
  function touchEvent(type: string, points: Array<{ x: number; y: number }>) {
    const event = new Event(type, { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'touches', {
      value: points.map((point) => ({
        clientX: point.x,
        clientY: point.y,
      })),
    });
    return event;
  }

  function viewerStage(): HTMLElement {
    return document.body.querySelector(
      '[data-testid="viewer-stage"]',
    ) as HTMLElement;
  }

  async function pinch(from: number, to: number) {
    const half = from / 2;
    viewerStage().dispatchEvent(
      touchEvent('touchstart', [
        { x: 100 - half, y: 200 },
        { x: 100 + half, y: 200 },
      ]),
    );
    const halfTo = to / 2;
    viewerStage().dispatchEvent(
      touchEvent('touchmove', [
        { x: 100 - halfTo, y: 200 },
        { x: 100 + halfTo, y: 200 },
      ]),
    );
    await Promise.resolve();
  }

  it('图片：双指捏开放大舞台（scale 随距离比例），水印层不受影响', async () => {
    mountViewer();
    await pinch(100, 200);
    expect(stageStyle()).toContain('scale(2)');
    expect(document.body.textContent).toContain('200%');
    // 水印挂在遮罩根上、不随内容变换（E16-T06 口径在双指缩放下保持）
    const overlay = document.body.querySelector('[data-watermark]');
    expect(overlay).toBeTruthy();
    expect((overlay as HTMLElement).style.pointerEvents).toBe('none');
  });

  it('图片：双指捏拢缩小，触底 50% 夹住；单指触摸不触发缩放', async () => {
    mountViewer();
    await pinch(200, 100);
    expect(stageStyle()).toContain('scale(0.5)');
    expect(document.body.textContent).toContain('50%');

    viewerStage().dispatchEvent(touchEvent('touchmove', [{ x: 10, y: 10 }]));
    await Promise.resolve();
    expect(stageStyle()).toContain('scale(0.5)');
  });

  it('音频不参与双指缩放（只对图片启用，PDF / DOCX 用工具栏按钮）', async () => {
    mountViewer({ file: AUDIO });
    await pinch(100, 300);
    expect(stageStyle()).toBe('none');
  });

  it('双指结束后再捏开：从当前 scale 继续比例缩放（非从 100% 重算）', async () => {
    mountViewer();
    await pinch(100, 200); // → 200%
    await pinch(100, 150); // 起手重新取样，×1.5 → 300%
    expect(document.body.textContent).toContain('300%');
    expect(stageStyle()).toContain('scale(3)');
  });
});

// ------------------- E24-T07 真机兼容：iOS Safari 页面级双指缩放不干扰查看器
// iOS Safari 自 10 起忽略 user-scalable=no（无障碍），双指捏合仍会缩放整页；
// 查看器双指缩放图片时页面也被缩放（遮罩、水印错位）属真机阻断。WebKit 专属的
// gesturestart/gesturechange 在 document 上 preventDefault（passive:false）是
// 标准做法；其它内核不派发这两个事件，等同无操作。
describe('e24-T07 iOS Safari gesture 事件：打开图片查看器时页面级捏合被阻止', () => {
  function gestureEvent(type: 'gesturechange' | 'gesturestart') {
    return new Event(type, { bubbles: true, cancelable: true });
  }

  it('图片模式打开时 gesturestart / gesturechange 均被 preventDefault', async () => {
    mountViewer();
    await Promise.resolve();
    for (const type of ['gesturestart', 'gesturechange'] as const) {
      const event = gestureEvent(type);
      document.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(true);
    }
  });

  it('音频（非图片）不启用：gesture 事件不拦截，保留页面自身行为', async () => {
    mountViewer({ file: AUDIO });
    await Promise.resolve();
    const event = gestureEvent('gesturestart');
    document.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
  });

  it('关闭查看器后摘除监听：gesture 事件不再被拦截', async () => {
    const wrapper = mountViewer();
    await Promise.resolve();
    wrapper.unmount();
    const event = gestureEvent('gesturestart');
    document.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
  });
});
