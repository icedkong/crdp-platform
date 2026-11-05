// NFR-SAVE-003 · ConflictBanner 单测（components.md §13、pages.md §5.1 冲突条文案照抄）
import type { AutoSaveConflict } from '@crdp/form-engine';

import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import ConflictBanner from './conflict-banner.vue';

/**
 * 锁定：显示「版本冲突：{更新人} 于 {时间} 修改了 {字段}」（字段以、连接）；
 * 按钮「保留我的并覆盖」「载入服务器版本」分别 emit keep-mine / load-server；
 * 处理中（pending）对应按钮 loading、另一个禁用。
 */
const conflict = (over: Partial<AutoSaveConflict> = {}): AutoSaveConflict => ({
  fields: ['EF 射血分数', 'q03_la'],
  serverVersion: 9,
  updatedAt: '2026-11-05T13:58:00+08:00',
  updatedBy: '王医生（示例）',
  ...over,
});

function mountBanner(props: {
  conflict: AutoSaveConflict;
  pending?: 'keepMine' | 'loadServer' | null;
}) {
  return mount(ConflictBanner, { props });
}

describe('conflictBanner（409 冲突条）', () => {
  it('显示更新人、时间与不同字段（文案照抄）', () => {
    const wrapper = mountBanner({ conflict: conflict() });
    expect(wrapper.text()).toContain(
      '版本冲突：王医生（示例） 于 2026-11-05 13:58 修改了 EF 射血分数、q03_la',
    );
    expect(wrapper.find('[data-testid="conflict-banner"]').exists()).toBe(true);
  });

  it('更新人缺失时显示「他人」', () => {
    const wrapper = mountBanner({ conflict: conflict({ updatedBy: '' }) });
    expect(wrapper.text()).toContain('版本冲突：他人 于');
  });

  it('按钮「保留我的并覆盖」emit keep-mine；「载入服务器版本」emit load-server', async () => {
    const wrapper = mountBanner({ conflict: conflict() });
    await wrapper.find('[data-testid="conflict-keep-mine"]').trigger('click');
    expect(wrapper.emitted('keepMine')).toHaveLength(1);
    await wrapper.find('[data-testid="conflict-load-server"]').trigger('click');
    expect(wrapper.emitted('loadServer')).toHaveLength(1);
  });

  it('处理中按钮 loading 且互斥禁用', () => {
    const wrapper = mountBanner({
      conflict: conflict(),
      pending: 'keepMine',
    });
    expect(
      wrapper.find('[data-testid="conflict-keep-mine"]').classes(),
    ).toContain('ant-btn-loading');
    expect(
      wrapper
        .find('[data-testid="conflict-load-server"]')
        .attributes('disabled'),
    ).toBeDefined();
  });
});
