import type { ImplantRecordView } from '@crdp/api';

import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchPatientImplants } from '#/api/core/implant';

import ImplantSection from './implant-section.vue';

/**
 * E24-T10 / pages.md §4.4「手机：植入记录与电极表格改卡片」与 §4.10 手机行
 * （验收「手机可查看植入记录」）：手机形态不渲染表格，卡片带整条锚点 id
 * （E21-T32 左栏跳转目标）、植入类型 / 器械 / 电极逐行（implantCardLines）、
 * 当前器械标记；操作按钮按 followup:implant:edit 显隐（AGENTS §5.9 前端隐藏
 * 不代替后端校验）。桌面形态仍为表格（当前器械置顶 + 电极展开行）。
 */

const breakpointState = { isMobile: false };
const permissionState = { implantEdit: false };

vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({ isMobile: ref(breakpointState.isMobile) }),
}));
vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({
    hasPermission: (code: string) =>
      code === 'followup:implant:edit' && permissionState.implantEdit,
  }),
}));
vi.mock('#/api/core/implant', () => ({
  createPatientImplant: vi.fn(),
  explantPatientImplant: vi.fn(),
  fetchImplantOptions: vi.fn(),
  fetchPatientImplants: vi.fn(),
  replacePatientImplant: vi.fn(),
  updatePatientImplant: vi.fn(),
}));

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  breakpointState.isMobile = false;
  permissionState.implantEdit = false;
  vi.clearAllMocks();
});

function record(id: string, overrides: Partial<ImplantRecordView> = {}) {
  return {
    brand: '示例品牌X',
    brandLabel: '示例品牌X',
    current: false,
    deviceType: 'CRT_D',
    deviceTypeLabel: 'CRT-D',
    endDate: null,
    endReason: null,
    hospital: '示例医院',
    id,
    implantDate: '2025-06-01',
    implantNo: 2,
    implantType: 'UPGRADE',
    implantTypeLabel: '升级',
    leads: [
      {
        chamber: 'RV',
        chamberLabel: '右心室',
        id: `${id}-lead-1`,
        implantDate: '2023-01-10',
        model: '示例型号',
        position: 'RV Apex',
        positionLabel: '右室心尖',
        status: 'ACTIVE',
        statusLabel: '沿用',
        version: 1,
      },
    ],
    patientId: '301',
    serialNo: 'DEMO-SN-0001',
    status: 'ACTIVE',
    statusLabel: '在用',
    version: 1,
    ...overrides,
  } satisfies ImplantRecordView;
}

async function mountSection(items: ImplantRecordView[]) {
  vi.mocked(fetchPatientImplants).mockResolvedValue({ items });
  const wrapper = mount(ImplantSection, {
    attachTo: document.body,
    props: { attachments: [], canUpload: false, patientId: '301' },
  });
  mounted.push(wrapper);
  await flushPromises();
  return wrapper;
}

describe('implantSection 植入记录区块（基线资料页签，C）', () => {
  it('桌面：渲染表格（当前器械置顶 + 电极展开行）', async () => {
    const wrapper = await mountSection([
      record('12', { current: true }),
      record('11', {
        current: false,
        endDate: '2025-06-01',
        implantDate: '2023-01-10',
        implantNo: 1,
        implantType: 'FIRST',
        implantTypeLabel: '首次植入',
        status: 'REPLACED',
        statusLabel: '已更换',
      }),
    ]);
    expect(wrapper.find('[data-testid="implant-table"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="implant-cards"]').exists()).toBe(false);
    // 行锚点（E21-T32 左栏跳转目标）
    expect(wrapper.find('#crdp-anchor-implant-12').exists()).toBe(true);
  });

  it('手机：表格改卡片——锚点 id、当前器械标记、植入类型 / 器械 / 电极逐行', async () => {
    breakpointState.isMobile = true;
    const wrapper = await mountSection([
      record('12', { current: true }),
      record('11', {
        current: false,
        endDate: '2025-06-01',
        implantDate: '2023-01-10',
        implantNo: 1,
        implantType: 'FIRST',
        implantTypeLabel: '首次植入',
        status: 'REPLACED',
        statusLabel: '已更换',
      }),
    ]);

    expect(wrapper.find('[data-testid="implant-table"]').exists()).toBe(false);
    const cards = wrapper.findAll('[data-testid="implant-card"]');
    expect(cards).toHaveLength(2);

    // 第一张卡（当前器械置顶）：整条锚点 id + 当前器械标记 + 字段行
    expect(cards[0]?.attributes('id')).toBe('crdp-anchor-implant-12');
    expect(cards[0]?.text()).toContain('当前器械');
    expect(cards[0]?.text()).toContain('第 2 次 · 2025-06-01');
    expect(cards[0]?.text()).toContain('升级');
    expect(cards[0]?.text()).toContain('CRT-D · 示例品牌X');
    // 电极行（implantCardLines：腔室 / 部位 / 日期 / 状态）
    expect(cards[0]?.text()).toContain('电极 右心室');
    expect(cards[0]?.text()).toContain('右室心尖 · 2023-01-10 · 沿用');

    // 第二张卡：历史器械状态标记
    expect(cards[1]?.attributes('id')).toBe('crdp-anchor-implant-11');
    expect(cards[1]?.text()).toContain('已更换');
  });

  it('手机：无 followup:implant:edit 权限时卡片不出现操作按钮', async () => {
    breakpointState.isMobile = true;
    permissionState.implantEdit = false;
    const wrapper = await mountSection([record('12', { current: true })]);

    expect(wrapper.find('[data-testid="implant-create"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('编辑');
  });

  it('手机：有权限时保留操作（当前器械取出 / 编辑、头部新建与更换升级）', async () => {
    breakpointState.isMobile = true;
    permissionState.implantEdit = true;
    const wrapper = await mountSection([record('12', { current: true })]);

    expect(wrapper.find('[data-testid="implant-create"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="implant-replace"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="implant-lead-revision"]').exists()).toBe(
      true,
    );
    const card = wrapper.find('[data-testid="implant-card"]');
    // antd Button 在两字中文间自动插空格（取 出 / 编 辑）
    expect(card.text()).toContain('取 出');
    expect(card.text()).toContain('编 辑');
  });
});
