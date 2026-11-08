/**
 * E19-T02 / A-DASH-001、C-DASH-001：首页统计卡 StatCard（components.md §13：
 * a-statistic 24–28/600 + 右上线性图标 + 「较昨日 +n / −n」12px；点击钻取）。
 */
import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it } from 'vitest';

import StatCard from './stat-card.vue';

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

function mountCard(props: Record<string, unknown> = {}) {
  const wrapper = mount(StatCard, {
    props: { metricCode: 'active', title: '在随访', ...props },
  });
  mounted.push(wrapper);
  return wrapper;
}

describe('e19-t02 StatCard：指标卡展示与钻取', () => {
  it('渲染指标名与数字（a-statistic 24/600）', () => {
    const wrapper = mountCard({ value: 1234 });
    expect(wrapper.find('[data-testid="stat-card-active"]').exists()).toBe(
      true,
    );
    expect(wrapper.text()).toContain('在随访');
    // a-statistic 千分位分组（pages.md §2 线框「1,234」）
    expect(wrapper.find('[data-testid="stat-value-active"]').text()).toContain(
      '1,234',
    );
  });

  it('value 为 null 显示「—」（数据源未落地）', () => {
    const wrapper = mountCard({ value: null });
    expect(wrapper.find('[data-testid="stat-value-active"]').text()).toContain(
      '—',
    );
  });

  it('点击卡片发出 drill 事件（父级按后端下发的钻取条件换路由）', async () => {
    const wrapper = mountCard({ value: 5 });
    await wrapper.find('[data-testid="stat-card-active"]').trigger('click');
    expect(wrapper.emitted('drill')?.[0]).toEqual(['active']);
  });

  it('较昨日变化：正数 +n / 负数 −n（数据源随后续任务接入）', () => {
    const up = mountCard({ value: 5, delta: 3 });
    expect(up.find('.stat-card-delta').text()).toContain('较昨日');
    expect(up.find('.stat-card-delta').text()).toContain('+3');
    const down = mountCard({ value: 5, delta: -2 });
    expect(down.find('.stat-card-delta').text()).toContain('-2');
    const none = mountCard({ value: 5 });
    expect(none.find('.stat-card-delta').exists()).toBe(false);
  });

  it('右上线性图标可选（不传不渲染）', () => {
    const withIcon = mountCard({ value: 5, icon: 'ant-design:team-outlined' });
    expect(withIcon.find('.stat-card-icon').exists()).toBe(true);
    const withoutIcon = mountCard({ value: 5 });
    expect(withoutIcon.find('.stat-card-icon').exists()).toBe(false);
  });
});
