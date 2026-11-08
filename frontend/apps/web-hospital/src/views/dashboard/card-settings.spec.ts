// E19-T06 / A-DASH-014（P1，A 规格 §9「用户在首页右上角『卡片设置』中隐藏或显示本角色允许的
// 卡片」）：选项只来自布局响应（角色允许范围，不能加入未允许的卡片）；allowUserHide=false
// 的卡片不可取消；保存 = 全量隐藏清单；恢复默认 = 空清单。测试数据全部虚构。
import type { DashboardLayoutView } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import CardSettings from './card-settings.vue';

const saveDashboardUserPref = vi.fn();

vi.mock('#/api/core/dashboard', async (importOriginal) => ({
  ...(await importOriginal<typeof import('#/api/core/dashboard')>()),
  saveDashboardUserPref: (...args: unknown[]) => saveDashboardUserPref(...args),
}));

const layout: DashboardLayoutView = {
  cards: [
    {
      allowUserHide: true,
      code: 'kpi',
      feature: null,
      order: 1,
      permission: 'followup:dashboard:view',
      title: '指标卡',
      userHidden: false,
      visible: true,
      width: 'FULL',
    },
    {
      allowUserHide: true,
      code: 'trend',
      feature: null,
      order: 2,
      permission: 'followup:dashboard:view',
      title: '趋势图',
      userHidden: true, // 用户此前隐藏过：打开面板时不勾选
      visible: false,
      width: 'FULL',
    },
    {
      allowUserHide: false,
      code: 'task_board',
      feature: null,
      order: 3,
      permission: 'followup:dashboard:view',
      title: '任务看板',
      userHidden: false,
      visible: true,
      width: 'FULL',
    },
  ],
  source: 'ROLE_CONFIG',
};

const mounted: Array<ReturnType<typeof mount>> = [];

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 60));
}

/**
 * 浮层内容挂在 body 门户里（ant Popover 默认 teleport），用原生 DOM 查询与事件操作。
 * Checkbox 的 data-testid 落在 input 本身或其包裹 label 上，两处都查。
 */
function panelInput(code: string): HTMLInputElement | undefined {
  const selector = `[data-testid="card-setting-${code}"]`;
  return (
    document.querySelector<HTMLInputElement>(selector) ??
    document.querySelector<HTMLInputElement>(`${selector} input`) ??
    undefined
  );
}

function clickPanelButton(text: string) {
  // ant Button 对两字文案自动插入空格（「保 存」），比较前去掉空白
  const button = [...document.querySelectorAll('button')].find((item) =>
    item.textContent?.replaceAll(/\s/g, '').includes(text),
  );
  button?.dispatchEvent(new Event('click', { bubbles: true }));
}

async function openPanel() {
  const wrapper = mount(CardSettings, {
    attachTo: document.body,
    props: { layout },
  });
  mounted.push(wrapper);
  await wrapper
    .find('[data-testid="dashboard-card-settings"]')
    .trigger('click');
  await flush();
  return wrapper;
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

describe('e19-t06 卡片设置（A-DASH-014 用户隐藏 / 显示）', () => {
  it('选项只来自角色允许的卡片；已隐藏的不勾选、不可隐藏的禁用', async () => {
    await openPanel();

    expect(
      document.querySelector('[data-testid="dashboard-card-settings-panel"]'),
    ).not.toBeNull();
    // 选项 = 布局响应的卡片（角色允许范围），不含角色隐藏 / 开关关闭的卡片
    expect(panelInput('kpi')).toBeDefined();
    expect(panelInput('node_rate')).toBeUndefined();
    expect(panelInput('kpi')?.checked).toBe(true);
    expect(panelInput('trend')?.checked).toBe(
      false,
      '用户此前隐藏的趋势图默认不勾选',
    );
    expect(panelInput('task_board')?.disabled).toBe(
      true,
      'allowUserHide=false 的卡片不可取消',
    );
  });

  it('取消勾选后保存：提交全量隐藏清单并回传新布局', async () => {
    saveDashboardUserPref.mockResolvedValue({
      ...layout,
      source: 'ROLE_CONFIG',
    });
    const wrapper = await openPanel();

    // jsdom 不实现 checkbox 的原生激活行为：手动置 checked 再派发 click / change
    const input = panelInput('kpi');
    if (input) {
      input.checked = false;
      input.dispatchEvent(new Event('click', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
    await flush();
    clickPanelButton('保存');
    await flush();

    // 全量清单语义：取消指标卡 + 之前已隐藏的趋势图都保持隐藏
    expect(saveDashboardUserPref).toHaveBeenCalledWith(['kpi', 'trend']);
    expect(wrapper.emitted('saved')).toHaveLength(1);
  });

  it('恢复默认：提交空清单（回到角色配置的卡片与顺序）', async () => {
    saveDashboardUserPref.mockResolvedValue(layout);
    const wrapper = await openPanel();

    clickPanelButton('恢复默认');
    await flush();

    expect(saveDashboardUserPref).toHaveBeenCalledWith([]);
    expect(wrapper.emitted('saved')).toHaveLength(1);
  });
});
