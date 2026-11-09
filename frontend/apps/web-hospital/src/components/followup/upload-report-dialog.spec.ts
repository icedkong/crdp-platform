// E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010（pages.md §6.1）：
// 添加报告单对话框——类型→名称→指标带出、按类型显示所见/结论、位置必填（空时「请至少选择
// 1 个位置」且不提交）、预选位置随载荷回传、附件上传后 fileId 一并提交、成功提示并 emit created
// E16-T03 / A-ATT-004、C-SCAN-006：附件超过每份上限（A 默认 10 个）直接拦截不上传，
// 计数提示「已选 x / 10 个附件」，可上移 / 下移调整顺序
import type { ReportLocationInput, ReportLocationNode } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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

// E24-T06 拍照压缩链路：默认按原文件通过（与 happy-dom 无解码器时的真实降级一致），
// 需要断言压缩的用例单独 mockImplementation
const compressImageFile = vi.fn();
vi.mock('@crdp/photo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@crdp/photo')>();
  return {
    ...actual,
    compressImageFile: (...args: unknown[]) => compressImageFile(...args),
  };
});

// E24-T06 手机形态：断点可切换（默认桌面，既有用例不受影响）
const breakpointState = { isMobile: false };
vi.mock('#/reference/use-breakpoint', async () => {
  const { ref } = await import('vue');
  return {
    useBreakpoint: () => ({
      isDesktop: ref(true),
      isMobile: ref(breakpointState.isMobile),
    }),
  };
});

// 压缩默认按原文件通过（用例各自覆盖为「压缩成功」/「undecodable」）
beforeEach(() => {
  compressImageFile.mockImplementation(async (file: File) => ({
    compressed: false,
    file,
  }));
});

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
    expect(uploadFile).toHaveBeenCalledTimes(calls);
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

// ------------------------------------------------- E24-T06 / NFR-RESP-003 拍照上传与手机形态
// 拍照（capture=environment，可连拍多页）与相册多选：图片先压缩（长边 ≤ 2560、
// 转 JPEG 含 HEIC、去 EXIF 定位）再上传；进度按字节百分比展示，弱网失败可重试；
// 对话框在手机全宽展示（crdp-form 手机输入 36/16）。

function queueText(): string {
  return (
    document.body.querySelector('[data-testid="photo-queue"]')?.textContent ??
    ''
  );
}

function pickFiles(testid: string, files: File[]) {
  const input = document.body.querySelector(
    `input[data-testid="${testid}"]`,
  ) as HTMLInputElement | null;
  expect(input).toBeTruthy();
  Object.defineProperty(input, 'files', { configurable: true, value: files });
  input.dispatchEvent(new Event('change', { bubbles: true }));
}

describe('e24-t06 UploadReportDialog（拍照多页上传：压缩 / 进度 / 重试）', () => {
  it('拍照与相册入口：capture=environment 单张连拍、相册多选，选完清空可重选', async () => {
    await mountDialog();
    const capture = document.body.querySelector(
      'input[data-testid="photo-capture-input"]',
    ) as HTMLInputElement | null;
    expect(capture?.getAttribute('accept')).toBe('image/*');
    expect(capture?.getAttribute('capture')).toBe('environment');
    expect(capture?.hasAttribute('multiple')).toBe(false);

    const gallery = document.body.querySelector(
      'input[data-testid="photo-gallery-input"]',
    ) as HTMLInputElement | null;
    expect(gallery?.getAttribute('accept')).toBe('image/*');
    expect(gallery?.hasAttribute('multiple')).toBe(true);
  });

  it('拍照一页：压缩 → 上传进度（按字节）→ 完成进入附件清单（顺序随选入）', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      files: Array<{ fileId: string; name: string }>;
    };
    const jpeg = new File(['jpeg-bytes'], 'IMG_0001.jpg', {
      type: 'image/jpeg',
    });
    compressImageFile.mockResolvedValue({ compressed: true, file: jpeg });
    let finishUpload: (value: {
      fileId: string;
      format: string;
      mime: string;
      originalName: string;
    }) => void = () => {};
    uploadFile.mockImplementation(
      (
        file: File,
        config?: {
          onUploadProgress?: (event: {
            loaded: number;
            total?: number;
          }) => void;
        },
      ) => {
        config?.onUploadProgress?.({ loaded: 456_789, total: 1_000_000 });
        return new Promise((resolve) => {
          finishUpload = resolve;
        });
      },
    );

    pickFiles('photo-capture-input', [
      new File(['heic-bytes'], 'IMG_0001.HEIC', { type: 'image/heic' }),
    ]);
    await flush(2);

    // 上传中：进度文案可见（456789 / 1000000 → 46%）
    expect(queueText()).toContain('上传中 46%');
    expect(compressImageFile).toHaveBeenCalledOnce();
    expect(uploadFile).toHaveBeenCalledOnce();
    expect(uploadFile.mock.calls[0]?.[0]).toBe(jpeg);

    finishUpload({
      fileId: 'f-9001',
      format: 'jpg',
      mime: 'image/jpeg',
      originalName: 'IMG_0001.jpg',
    });
    await flush(2);
    expect(vm.files).toHaveLength(1);
    expect(vm.files[0]).toMatchObject({ fileId: 'f-9001' });
    expect(queueText()).not.toContain('上传中');
  });

  it('相册连选两页：逐张顺序处理（页序即附件顺序）', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      files: Array<{ fileId: string; name: string }>;
    };
    compressImageFile.mockImplementation(async (file: File) => ({
      compressed: true,
      file,
    }));
    const uploaded: string[] = [];
    uploadFile.mockImplementation(async (file: File) => {
      uploaded.push(file.name);
      return {
        fileId: `f-${file.name}`,
        format: 'jpg',
        mime: 'image/jpeg',
        originalName: file.name,
      };
    });

    pickFiles('photo-gallery-input', [
      new File(['a'], 'page-1.jpg', { type: 'image/jpeg' }),
      new File(['b'], 'page-2.jpg', { type: 'image/jpeg' }),
    ]);
    await flush(4);

    expect(uploaded).toEqual(['page-1.jpg', 'page-2.jpg']);
    expect(vm.files.map((item) => item.name)).toEqual([
      'page-1.jpg',
      'page-2.jpg',
    ]);
  });

  it('弱网失败：提示可重试，重试成功后进入附件清单', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      files: Array<{ fileId: string }>;
    };
    compressImageFile.mockImplementation(async (file: File) => ({
      compressed: true,
      file,
    }));
    uploadFile
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({
        fileId: 'f-retry',
        format: 'jpg',
        mime: 'image/jpeg',
        originalName: 'page-1.jpg',
      });

    pickFiles('photo-gallery-input', [
      new File(['a'], 'page-1.jpg', { type: 'image/jpeg' }),
    ]);
    await flush(3);
    expect(queueText()).toContain('上传失败');
    expect(queueText()).toContain('重试');
    expect(vm.files).toHaveLength(0);

    const retry = [...document.body.querySelectorAll('button')].find((item) =>
      (item.textContent ?? '').replaceAll(' ', '').includes('重试'),
    );
    retry?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flush(3);
    expect(uploadFile).toHaveBeenCalledTimes(2);
    expect(vm.files).toHaveLength(1);
    expect(vm.files[0]?.fileId).toBe('f-retry');
  });

  it('拍照也受每份上限拦截（已传 9 + 在途 1 + 再拍 1 > 10）', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      customRequest: (options: {
        file: File;
        onError: (error: Error) => void;
        onSuccess: (body: unknown) => void;
      }) => Promise<void>;
      files: Array<{ fileId: string }>;
    };
    uploadFile.mockImplementation(async (file: File) => ({
      fileId: `f-${file.name}`,
      format: 'png',
      mime: 'image/png',
      originalName: file.name,
    }));
    for (let i = 0; i < 9; i++) {
      await vm.customRequest({
        file: new File(['x'], `s-${i}.png`),
        onError: () => {},
        onSuccess: () => {},
      });
    }
    expect(vm.files).toHaveLength(9);

    // 队列先挂 1 张在途（不 resolve），再拍一张应整体拦截
    compressImageFile.mockImplementation(async (file: File) => ({
      compressed: true,
      file,
    }));
    uploadFile.mockImplementation(
      () => new Promise(() => {}) as Promise<never>,
    );
    pickFiles('photo-gallery-input', [
      new File(['a'], 'in-flight.jpg', { type: 'image/jpeg' }),
    ]);
    await flush(2);

    const calls = uploadFile.mock.calls.length;
    pickFiles('photo-capture-input', [
      new File(['b'], 'one-more.jpg', { type: 'image/jpeg' }),
    ]);
    await flush(2);
    expect(uploadFile.mock.calls.length).toBe(calls);
    // 拦截后不进队列（提示文案与 attachmentOverLimit 单测同口径）
    const queueItems = document.body.querySelectorAll(
      '[data-testid="photo-queue-item"]',
    );
    expect([...queueItems].map((item) => item.textContent)).toEqual([
      expect.stringContaining('in-flight.jpg'),
    ]);
  });

  it('「选择文件」里的图片同样先压缩再上传；非图片原样上传', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      customRequest: (options: {
        file: File;
        onError: (error: Error) => void;
        onSuccess: (body: unknown) => void;
      }) => Promise<void>;
    };
    const jpeg = new File(['jpeg'], 'scan.jpg', { type: 'image/jpeg' });
    compressImageFile.mockResolvedValue({ compressed: true, file: jpeg });
    uploadFile.mockResolvedValue({
      fileId: 'f-scan',
      format: 'jpg',
      mime: 'image/jpeg',
      originalName: 'scan.jpg',
    });

    await vm.customRequest({
      file: new File(['png'], 'scan.png', { type: 'image/png' }),
      onError: () => {},
      onSuccess: () => {},
    });
    expect(compressImageFile).toHaveBeenCalledOnce();
    expect(uploadFile.mock.calls[0]?.[0]).toBe(jpeg);

    const pdf = new File(['%PDF'], 'report.pdf', { type: 'application/pdf' });
    compressImageFile.mockResolvedValueOnce({
      compressed: false,
      file: pdf,
    });
    await vm.customRequest({
      file: pdf,
      onError: () => {},
      onSuccess: () => {},
    });
    // 非图片由 compressImageFile 判定原样通过（真实实现的 isCompassibleImage 单测锁定）
    expect(uploadFile.mock.calls[1]?.[0]).toBe(pdf);
  });

  it('解不开的 HEIC 按原文件上传（提示文案见 undecodableNoteText 单测）', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as unknown as {
      files: Array<{ fileId: string; name: string }>;
    };
    const source = new File(['heic'], 'IMG_0002.HEIC', { type: 'image/heic' });
    compressImageFile.mockResolvedValue({
      compressed: false,
      file: source,
      note: 'undecodable',
    });
    uploadFile.mockResolvedValue({
      fileId: 'f-raw',
      format: 'heic',
      mime: 'image/heic',
      originalName: 'IMG_0002.HEIC',
    });

    pickFiles('photo-capture-input', [source]);
    await flush(3);
    expect(uploadFile).toHaveBeenCalledOnce();
    expect(uploadFile.mock.calls[0]?.[0]).toBe(source);
    expect(vm.files[0]).toMatchObject({ fileId: 'f-raw' });
  });

  it('手机形态：对话框全宽、表单带 crdp-form（手机输入 36/16 全局样式入口）', async () => {
    breakpointState.isMobile = true;
    try {
      await mountDialog();
      const modal = document.body.querySelector('.ant-modal') as HTMLElement;
      expect(modal?.style.width).toBe('100%');
      expect(document.body.querySelector('form.crdp-form')).toBeTruthy();
    } finally {
      breakpointState.isMobile = false;
    }
  });

  it('桌面形态：对话框保持 880 定宽', async () => {
    await mountDialog();
    const modal = document.body.querySelector('.ant-modal') as HTMLElement;
    expect(modal?.style.width).toBe('880px');
  });
});
