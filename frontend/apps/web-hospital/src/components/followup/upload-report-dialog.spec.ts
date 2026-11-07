// E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010（pages.md §6.1）：
// 添加报告单对话框——类型→名称→指标带出、按类型显示所见/结论、位置必填（空时「请至少选择
// 1 个位置」且不提交）、预选位置随载荷回传、附件上传后 fileId 一并提交、成功提示并 emit created
// E16-T03 / A-ATT-004、C-SCAN-006：附件超过每份上限（A 默认 10 个）直接拦截不上传，
// 计数提示「已选 x / 10 个附件」，可上移 / 下移调整顺序
import type { ReportLocationInput, ReportLocationNode } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { createPinia, setActivePinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';

import UploadReportDialog from './upload-report-dialog.vue';

const fetchCatalogReportTypes = vi.fn();
const fetchCatalogReportNames = vi.fn();
const fetchReportLocations = vi.fn();
const createReport = vi.fn();
const uploadFile = vi.fn();
const fetchUploadPolicy = vi.fn();
const getFilePreviewUrl = vi.fn();
const fetchPatientContacts = vi.fn();

vi.mock('#/api/core/report', () => ({
  createReport: (...args: unknown[]) => createReport(...args),
  fetchCatalogReportNames: (...args: unknown[]) =>
    fetchCatalogReportNames(...args),
  fetchCatalogReportTypes: (...args: unknown[]) =>
    fetchCatalogReportTypes(...args),
  fetchReportLocations: (...args: unknown[]) => fetchReportLocations(...args),
}));
vi.mock('#/api/core/contact', () => ({
  fetchPatientContacts: (...args: unknown[]) => fetchPatientContacts(...args),
}));
vi.mock('#/api/core/file', () => ({
  fetchUploadPolicy: (...args: unknown[]) => fetchUploadPolicy(...args),
  getFilePreviewUrl: (...args: unknown[]) => getFilePreviewUrl(...args),
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
    code: 'CALL',
    defaultDigitizeStatus: null,
    fields: [
      {
        code: 'call_time',
        key: 'f001',
        label: '通话时间',
        required: true,
        sortNo: 10,
        type: 'datetime',
        unit: null,
      },
    ],
    name: '通话记录',
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
  // E16-T06：对话框内 WatermarkPreview 需要 userStore（水印查看人来自 me）
  setActivePinia(createPinia());
  fetchCatalogReportTypes.mockResolvedValue(TYPES);
  fetchReportLocations.mockResolvedValue(TREE);
  fetchCatalogReportNames.mockResolvedValue(NAMES_LAB);
  fetchPatientContacts.mockResolvedValue({
    items: [
      {
        audios: [
          {
            fileId: '6001',
            format: 'mp3',
            inlinePlayable: true,
            originalName: '通话录音（示例）.mp3',
            sizeBytes: 2048,
          },
        ],
        contactTime: '2026-11-05T09:00:00',
        createdAt: '2026-11-05T09:00:00',
        creatorName: '王医生（示例）',
        id: '5001',
        method: 'PHONE',
        note: null,
        patientId: '301',
        result: 'REACHED',
        version: 0,
      },
    ],
    pageNo: 1,
    pageSize: 100,
    total: 1,
  });
  fetchUploadPolicy.mockResolvedValue({
    formats: [],
    maxAudioMb: 50,
    maxDocumentMb: 20,
    maxFilesPerReport: 10,
  });
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

  // --------------------------- E16-T03 多附件（A-ATT-004 超限拦截、C-SCAN-006 顺序）

  it('附件到每份上限（10 个）后，第 11 个直接拦截不上传并提示', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      customRequest: (options: {
        file: File;
        onError: (error: Error) => void;
        onSuccess: (body: unknown) => void;
      }) => Promise<void>;
      files: Array<{ fileId: string; name: string }>;
    };
    uploadFile.mockImplementation(async (file: File) => ({
      fileId: `f-${file.name}`,
      format: 'png',
      mime: 'image/png',
      originalName: file.name,
      pages: 1,
      sha256: 'hash',
      size: file.size,
    }));
    for (let i = 0; i < 10; i++) {
      const file = new File(['x'], `扫描件-${i}.png`);
      await vm.customRequest({
        file,
        onError: () => {},
        onSuccess: () => {},
      });
    }
    expect(vm.files).toHaveLength(10);
    expect(bodyText()).toContain('已选 10 / 10 个附件');

    const calls = uploadFile.mock.calls.length;
    let rejected: null | string = null;
    await vm.customRequest({
      file: new File(['x'], '扫描件-10.png'),
      onError: (error) => {
        rejected = error.message;
      },
      onSuccess: () => {},
    });
    expect(uploadFile).toHaveBeenCalledTimes(calls, '第 11 个不再调用上传接口');
    expect(vm.files).toHaveLength(10);
    // 拦截文案与后端 40001 同口径（纯函数 attachmentOverLimit 单测锁定）
    expect(rejected).toBe('附件数量超过上限（每份报告单最多 10 个）');
  });

  it('附件可上移 / 下移调整顺序（顺序随提交落 sortNo）', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      customRequest: (options: {
        file: File;
        onError: (error: Error) => void;
        onSuccess: (body: unknown) => void;
      }) => Promise<void>;
      files: Array<{ fileId: string; name: string }>;
      moveFile: (fileId: string, delta: -1 | 1) => void;
    };
    uploadFile.mockImplementation(async (file: File) => ({
      fileId: `f-${file.name}`,
      format: 'png',
      mime: 'image/png',
      originalName: file.name,
      pages: 1,
      sha256: 'hash',
      size: file.size,
    }));
    for (const name of ['a.png', 'b.png', 'c.png']) {
      await vm.customRequest({
        file: new File(['x'], name),
        onError: () => {},
        onSuccess: () => {},
      });
    }
    expect(vm.files.map((item) => item.name)).toEqual([
      'a.png',
      'b.png',
      'c.png',
    ]);

    vm.moveFile('f-c.png', -1);
    expect(vm.files.map((item) => item.name)).toEqual(
      ['a.png', 'c.png', 'b.png'],
      '下移 / 上移交换相邻顺序',
    );

    vm.moveFile('f-a.png', -1);
    expect(vm.files.map((item) => item.name)).toEqual(
      ['a.png', 'c.png', 'b.png'],
      '首个再上移不变',
    );
  });

  // --------------------------- E16-T06 全屏查看器（A-ATT-005 / C-SCAN-006 旋转）

  it('附件「预览」→ 全屏查看器打开（图片直出、水印层常在、多份可翻页）', async () => {
    getFilePreviewUrl.mockImplementation(async (fileId: string) => ({
      expiresAt: 4_102_444_800,
      format: 'png',
      inlinePlayable: true,
      mime: 'image/png',
      originalName: `${fileId}.png`,
      url: `/api/hospital/files/preview/token-${fileId}`,
    }));
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      customRequest: (options: {
        file: File;
        onError: (error: Error) => void;
        onSuccess: (body: unknown) => void;
      }) => Promise<void>;
    };
    uploadFile.mockImplementation(async (file: File) => ({
      fileId: `f-${file.name}`,
      format: 'png',
      mime: 'image/png',
      originalName: file.name,
      pages: 1,
      sha256: 'hash',
      size: file.size,
    }));
    for (const name of ['a.png', 'b.png']) {
      await vm.customRequest({
        file: new File(['x'], name),
        onError: () => {},
        onSuccess: () => {},
      });
    }

    const previewButtons = [...document.body.querySelectorAll('button')].filter(
      (item) => (item.textContent ?? '').replaceAll(' ', '').includes('预览'),
    );
    expect(previewButtons).toHaveLength(2);
    previewButtons[0]?.dispatchEvent(
      new MouseEvent('click', { bubbles: true }),
    );
    await flush();

    const fullscreen = document.body.querySelector(
      '[data-testid="fullscreen-viewer"]',
    );
    expect(fullscreen).toBeTruthy();
    expect(fullscreen?.querySelector('img')?.getAttribute('src')).toBe(
      '/api/hospital/files/preview/token-f-a.png',
    );
    // 水印不可关闭（遮罩根上恒有水印层）
    expect(fullscreen?.querySelector('[data-watermark]')).toBeTruthy();
    // 两份附件 → 翻页控件可见
    expect(fullscreen?.textContent).toContain('第 1 / 2 份');
  });
});

// ---------------------------------------------------------------- E16-T08 / A-ATT-017 通话记录关联

describe('e16-t08 UploadReportDialog（通话记录关联沟通记录）', () => {
  it('选「通话记录」显示关联沟通记录下拉（只列带录音的记录）；关联后位置可空提交', async () => {
    const wrapper = await mountDialog();
    await pickTypeAndName(wrapper, 'CALL', '41');

    expect(fetchPatientContacts).toHaveBeenCalledWith('301', {
      pageNo: 1,
      pageSize: 100,
    });
    expect(bodyText()).toContain('关联沟通记录');

    // 选定关联沟通记录（Select 经 teleport，经组件状态驱动）
    const vm = wrapper.vm as unknown as { contactLogId: null | string };
    vm.contactLogId = '5001';
    await flush(1);

    // 位置留空提交：不再拦截（后端按该沟通关联任务的随访记录推导 / 未指定位置）
    await clickSubmit(wrapper);

    expect(createReport).toHaveBeenCalledTimes(1);
    const payload = createReport.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(payload.contactLogId).toBe('5001');
    expect(payload.typeCode).toBe('CALL');
    expect(payload.locations).toEqual([]);
  });

  it('「通话记录」未关联沟通记录时位置仍必填', async () => {
    const wrapper = await mountDialog();
    await pickTypeAndName(wrapper, 'CALL', '41');

    await clickSubmit(wrapper);

    expect(bodyText()).toContain('请至少选择 1 个位置');
    expect(createReport).not.toHaveBeenCalled();
  });

  it('其他类型不显示关联沟通记录', async () => {
    const wrapper = await mountDialog();
    await pickTypeAndName(wrapper, 'LAB', '12');

    expect(bodyText()).not.toContain('关联沟通记录');
    expect(fetchPatientContacts).not.toHaveBeenCalled();
  });
});
