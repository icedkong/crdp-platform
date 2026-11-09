// E16-T11 / A-ATT-019、C-FU-017（pages.md §5「（可选）对照报告单分屏」）：
// 对照录入右侧分屏面板——一侧表单一侧报告单：报告单名称 / 类型 / 报告时间、
// 首附件带水印页内预览（短时链接每次签发、不缓存）；「全屏查看」交给父级打开
// E16-T06 全屏查看器（缩放 / 旋转 / 翻转）、「关闭对照」收起分屏；无附件给空态；
// 多附件提示当前只显示第 1 份（逐份清单随报告单详情接口开放）。
import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import CompareReportPane from './compare-report-pane.vue';

const getFilePreviewUrl = vi.fn();

vi.mock('#/api/core/file', () => ({
  getFilePreviewUrl: (...args: unknown[]) => getFilePreviewUrl(...args),
}));

const viewer = {
  account: 'doctor.demo',
  displayName: '王医生（示例）',
  tenantName: '医院 A（示例租户）',
};

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

function flush(frames = 2) {
  return new Promise((resolve) => setTimeout(resolve, 25 * frames));
}

async function mountPane(
  props: Record<string, unknown> = {},
  options: { keepMock?: boolean } = {},
) {
  if (!options.keepMock) {
    getFilePreviewUrl.mockImplementation(async (fileId: string) => ({
      expiresAt: 4_102_444_800,
      format: 'jpg',
      inlinePlayable: true,
      mime: 'image/jpeg',
      originalName: '超声（示例）.jpg',
      url: `/api/hospital/files/preview/token-${fileId}`,
    }));
  }
  const wrapper = mount(CompareReportPane, {
    props: {
      report: {
        fileCount: 1,
        firstFile: { fileId: '901', format: 'jpg' },
        id: 'r1',
        name: '经胸超声心动图',
        reportDate: '2026-10-28',
        typeName: '心脏超声',
      },
      viewer,
      ...props,
    },
  });
  mounted.push(wrapper);
  await flush();
  return wrapper;
}

function textOf(wrapper: ReturnType<typeof mount>, testid: string) {
  const node = wrapper.find(`[data-testid="${testid}"]`);
  return (node.text() ?? '').replaceAll(' ', '');
}

describe('e16-T11 CompareReportPane（对照录入分屏面板）', () => {
  it('显示报告单名称 / 类型 / 报告时间，并换首附件短时链接出图（带水印层）', async () => {
    const wrapper = await mountPane();
    expect(textOf(wrapper, 'compare-pane-title')).toContain('经胸超声心动图');
    expect(textOf(wrapper, 'compare-pane-meta')).toContain('心脏超声');
    expect(textOf(wrapper, 'compare-pane-meta')).toContain('2026-10-28');
    expect(getFilePreviewUrl).toHaveBeenCalledWith('901');
    expect(wrapper.find('img').attributes('src')).toBe(
      '/api/hospital/files/preview/token-901',
    );
    // 水印由 @crdp/viewer FilePreview 内置（防篡改 guard 管理的平铺层）
    expect(wrapper.find('[data-testid="compare-pane-file"]').exists()).toBe(
      true,
    );
  });

  it('名称为空时标题回落类型名（A-ATT-011 名称可空）', async () => {
    const wrapper = await mountPane({
      report: {
        fileCount: 1,
        firstFile: { fileId: '901', format: 'jpg' },
        id: 'r1',
        name: null,
        reportDate: '2026-10-28',
        typeName: '出院小结',
      },
    });
    expect(textOf(wrapper, 'compare-pane-title')).toContain('出院小结');
  });

  it('无附件 → 空态「该报告单暂无可预览的附件」，不签发链接', async () => {
    const wrapper = await mountPane({
      report: {
        fileCount: 0,
        firstFile: null,
        id: 'r1',
        name: '经胸超声心动图',
        reportDate: '2026-10-28',
        typeName: '心脏超声',
      },
    });
    expect(getFilePreviewUrl).not.toHaveBeenCalled();
    expect(wrapper.find('img').exists()).toBe(false);
    expect(textOf(wrapper, 'compare-pane-empty')).toContain(
      '该报告单暂无可预览的附件',
    );
  });

  it('多附件提示只显示第 1 份（逐份清单随报告单详情接口开放）', async () => {
    const wrapper = await mountPane({
      report: {
        fileCount: 3,
        firstFile: { fileId: '901', format: 'jpg' },
        id: 'r1',
        name: '经胸超声心动图',
        reportDate: '2026-10-28',
        typeName: '心脏超声',
      },
    });
    expect(textOf(wrapper, 'compare-pane-files-note')).toContain('共3个附件');
  });

  it('「全屏查看」与「关闭对照」事件透传（全屏交给父级复用 E16-T06 查看器）', async () => {
    const wrapper = await mountPane();
    await wrapper
      .find('[data-testid="compare-pane-fullscreen"]')
      .trigger('click');
    await wrapper.find('[data-testid="compare-pane-close"]').trigger('click');
    expect(wrapper.emitted('fullscreen')).toHaveLength(1);
    expect(wrapper.emitted('close')).toHaveLength(1);
  });

  it('链接签发失败给出提示与「重试」', async () => {
    getFilePreviewUrl.mockImplementation(async () => {
      throw new Error('preview failed');
    });
    const wrapper = await mountPane({}, { keepMock: true });
    await flush();
    expect(wrapper.find('img').exists()).toBe(false);
    expect(textOf(wrapper, 'compare-pane-error')).toContain('预览链接获取失败');
    getFilePreviewUrl.mockImplementation(async (fileId: string) => ({
      expiresAt: 4_102_444_800,
      format: 'jpg',
      inlinePlayable: true,
      mime: 'image/jpeg',
      originalName: '超声（示例）.jpg',
      url: `/api/hospital/files/preview/token-${fileId}`,
    }));
    await wrapper.find('[data-testid="compare-pane-retry"]').trigger('click');
    await flush();
    expect(wrapper.find('img').exists()).toBe(true);
  });
});
