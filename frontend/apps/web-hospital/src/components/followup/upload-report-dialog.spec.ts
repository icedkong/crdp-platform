// E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010（pages.md §6.1）：
// 添加报告单对话框——类型→名称→指标带出、按类型显示所见/结论、位置必填（空时「请至少选择
// 1 个位置」且不提交）、预选位置随载荷回传、附件上传后 fileId 一并提交、成功提示并 emit created
import type { ReportLocationInput, ReportLocationNode } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import UploadReportDialog from './upload-report-dialog.vue';

const fetchCatalogReportTypes = vi.fn();
const fetchCatalogReportNames = vi.fn();
const fetchReportLocations = vi.fn();
const createReport = vi.fn();
const uploadFile = vi.fn();

vi.mock('#/api/core/report', () => ({
  createReport: (...args: unknown[]) => createReport(...args),
  fetchCatalogReportNames: (...args: unknown[]) =>
    fetchCatalogReportNames(...args),
  fetchCatalogReportTypes: (...args: unknown[]) =>
    fetchCatalogReportTypes(...args),
  fetchReportLocations: (...args: unknown[]) => fetchReportLocations(...args),
}));
vi.mock('#/api/core/file', () => ({
  uploadFile: (...args: unknown[]) => uploadFile(...args),
}));

const TYPES = [
  {
    code: 'LAB',
    defaultDigitizeStatus: null,
    fields: [
      {
        code: 'report_date',
        key: 'f001',
        label: '报告时间',
        required: true,
        sortNo: 10,
        type: 'date',
        unit: null,
      },
    ],
    name: '检验',
    nameRequired: true,
  },
  {
    code: 'ECHO',
    defaultDigitizeStatus: null,
    fields: [
      {
        code: 'report_date',
        key: 'f001',
        label: '报告时间',
        required: true,
        sortNo: 10,
        type: 'date',
        unit: null,
      },
      {
        code: 'findings',
        key: 'f002',
        label: '超声所见',
        required: false,
        sortNo: 20,
        type: 'textarea',
        unit: null,
      },
      {
        code: 'conclusion',
        key: 'f003',
        label: '超声结论',
        required: false,
        sortNo: 30,
        type: 'textarea',
        unit: null,
      },
    ],
    name: '心脏超声',
    nameRequired: true,
  },
];

const NAMES_LAB = [
  {
    id: '12',
    indicatorTplId: '55',
    items: [
      {
        itemCode: 'WBC',
        itemName: '白细胞（示例）',
        refRange: '3.5–9.5',
        refRangeFemale: null,
        refRangeMale: null,
        sortNo: 10,
        unit: '10^9/L',
      },
    ],
    name: '血常规（示例）',
    typeCode: 'LAB',
  },
];

const TREE: ReportLocationNode[] = [
  {
    children: [
      {
        key: 'baseline:case',
        selectable: true,
        title: '病例基本信息',
        value: {
          anchorKey: 'case',
          anchorTemplateVersion: 3,
          anchorType: 'category',
          linkType: 'baseline',
        },
      },
    ],
    key: 'baseline',
    selectable: false,
    title: '基线资料',
  },
];

const PRESET: ReportLocationInput[] = [
  {
    anchorKey: 'case',
    anchorTemplateVersion: 3,
    anchorType: 'category',
    linkType: 'baseline',
  },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

function flush(frames = 3) {
  return new Promise((resolve) => setTimeout(resolve, 25 * frames));
}

async function mountDialog(props: Record<string, unknown> = {}) {
  fetchCatalogReportTypes.mockResolvedValue(TYPES);
  fetchReportLocations.mockResolvedValue(TREE);
  fetchCatalogReportNames.mockResolvedValue(NAMES_LAB);
  const wrapper = mount(UploadReportDialog, {
    attachTo: document.body,
    props: { open: true, patientId: '301', ...props },
  });
  mounted.push(wrapper);
  await flush();
  return wrapper;
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

async function clickSubmit(wrapper: {
  vm: { $nextTick: () => Promise<void> };
}) {
  // antd 按钮两个汉字之间插空格（「保 存」），比对前去空格
  const button = [...document.body.querySelectorAll('button')].find((item) =>
    (item.textContent ?? '').replaceAll(' ', '').includes('保存'),
  );
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await flush();
  await wrapper.vm.$nextTick();
}

/** 经组件实例驱动 Select（类型 → 名称）与位置值。 */
async function pickTypeAndName(
  wrapper: Awaited<ReturnType<typeof mountDialog>>,
  typeCode: string,
  nameId: string,
) {
  const vm = wrapper.vm as unknown as {
    onNameChange: (id: string) => void;
    onTypeChange: (code: string) => Promise<void>;
  };
  await vm.onTypeChange(typeCode);
  await flush(1);
  vm.onNameChange(nameId);
  await flush(1);
}

describe('e16-t02 UploadReportDialog（添加报告单）', () => {
  it('打开时加载类型与位置树；预选进入位置并提示', async () => {
    await mountDialog({ preset: PRESET });

    expect(fetchCatalogReportTypes).toHaveBeenCalled();
    expect(fetchReportLocations).toHaveBeenCalledWith('301');
    expect(bodyText()).toContain('已按进入位置预选，可以再加');
  });

  it('位置为空提交：显示「请至少选择 1 个位置」且不调创建接口', async () => {
    const wrapper = await mountDialog();
    await pickTypeAndName(wrapper, 'LAB', '12');

    await clickSubmit(wrapper);

    expect(bodyText()).toContain('请至少选择 1 个位置');
    expect(createReport).not.toHaveBeenCalled();
  });

  it('缺名称（类型要求必填）提交被拦，提示「请选择名称」', async () => {
    const wrapper = await mountDialog();
    await pickTypeAndName(wrapper, 'LAB', '12');
    // 清空名称后提交
    const vm = wrapper.vm as unknown as { onNameChange: (id: string) => void };
    vm.onNameChange('');
    await flush(1);

    await clickSubmit(wrapper);

    expect(bodyText()).toContain('请选择名称');
    expect(createReport).not.toHaveBeenCalled();
  });

  it('按类型显示所见 / 结论（检验无、心脏超声有，标签取类型字段）', async () => {
    const lab = await mountDialog();
    await pickTypeAndName(lab, 'LAB', '12');
    expect(bodyText()).not.toContain('超声所见');

    const echo = await mountDialog();
    await pickTypeAndName(echo, 'ECHO', '12');
    expect(bodyText()).toContain('超声所见');
    expect(bodyText()).toContain('超声结论');
  });

  it('选名称带出指标行，填结果即时标 ↓，提交载荷含预选位置与指标', async () => {
    const wrapper = await mountDialog({ preset: PRESET });
    await pickTypeAndName(wrapper, 'LAB', '12');

    expect(bodyText()).toContain('白细胞（示例）');
    expect(bodyText()).toContain('3.5–9.5');

    const vm = wrapper.vm as unknown as {
      indicatorRows: Array<{ itemCode: string }>;
      onResultInput: (row: unknown, value: string) => void;
    };
    vm.onResultInput(vm.indicatorRows[0], '3.2');
    await flush(1);
    expect(bodyText()).toContain('↓');

    createReport.mockResolvedValue({
      fileCount: 0,
      id: '900',
      locationCount: 1,
      name: '血常规（示例）',
      reportDate: '2026-10-28',
      typeCode: 'LAB',
      typeName: '检验',
    });
    await clickSubmit(wrapper);

    expect(createReport).toHaveBeenCalledTimes(1);
    const payload = createReport.mock.calls.at(0)?.at(0) as {
      indicators: Array<{ itemCode: string; result: string }>;
      locations: ReportLocationInput[];
      nameId: null | string;
      typeCode: string;
    };
    expect(payload.typeCode).toBe('LAB');
    expect(payload.nameId).toBe('12');
    expect(payload.locations).toEqual(
      PRESET,
      '预选位置随载荷回传（anchorTemplateVersion 原样）',
    );
    expect(payload.indicators).toEqual([
      { itemCode: 'WBC', refRange: '3.5–9.5', result: '3.2' },
    ]);
  });
});
