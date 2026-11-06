// E16-T03 / A-ATT-014、C-SCAN-007（pages.md §6.3 批量上传）：批量上传对话框——
// 选择文件入队（超 50 / MERGE 每份上限拦截）、逐个上传显示进度、失败可重试、
// 统一类型 / 名称 / 报告时间 / 位置（至少 1 个）、每个文件一份（逐份调整名称与
// 报告时间）或合并为一份、提交载荷与成功提示
import type { ReportLocationInput, ReportLocationNode } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import BatchUploadDialog from './batch-upload-dialog.vue';

const fetchCatalogReportTypes = vi.fn();
const fetchCatalogReportNames = vi.fn();
const fetchReportLocations = vi.fn();
const batchCreateReports = vi.fn();
const uploadFile = vi.fn();
const fetchUploadPolicy = vi.fn();

vi.mock('#/api/core/report', () => ({
  batchCreateReports: (...args: unknown[]) => batchCreateReports(...args),
  fetchCatalogReportNames: (...args: unknown[]) =>
    fetchCatalogReportNames(...args),
  fetchCatalogReportTypes: (...args: unknown[]) =>
    fetchCatalogReportTypes(...args),
  fetchReportLocations: (...args: unknown[]) => fetchReportLocations(...args),
}));
vi.mock('#/api/core/file', () => ({
  fetchUploadPolicy: (...args: unknown[]) => fetchUploadPolicy(...args),
  uploadFile: (...args: unknown[]) => uploadFile(...args),
}));

const TYPES = [
  {
    code: 'LAB',
    defaultDigitizeStatus: null,
    fields: [],
    name: '检验',
    nameRequired: true,
  },
];

const NAMES = [
  {
    id: '12',
    indicatorTplId: '55',
    items: [],
    name: '血常规（示例）',
    typeCode: 'LAB',
  },
  {
    id: '13',
    indicatorTplId: null,
    items: [],
    name: '肝肾功能（示例）',
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
  fetchCatalogReportNames.mockResolvedValue(NAMES);
  fetchReportLocations.mockResolvedValue(TREE);
  fetchUploadPolicy.mockResolvedValue({
    formats: [],
    maxAudioMb: 50,
    maxDocumentMb: 20,
    maxFilesPerReport: 10,
  });
  const wrapper = mount(BatchUploadDialog, {
    attachTo: document.body,
    props: { open: true, patientId: '301', ...props },
  });
  mounted.push(wrapper);
  await flush();
  return wrapper;
}

type DialogVm = {
  enqueueFiles: (files: File[]) => void;
  handleSubmit: () => Promise<void>;
  mode: 'MERGE' | 'ONE_PER_FILE';
  onItemDateChange: (uid: string, date: null | string) => void;
  onItemLocationsChange: (uid: string, value: ReportLocationInput[]) => void;
  onItemNameChange: (uid: string, id: string) => void;
  onNameChange: (id: string) => void;
  onTypeChange: (code: string) => Promise<void>;
  rows: Array<{
    fileId?: null | string;
    name: string;
    status: string;
    uid: string;
  }>;
  startUpload: () => Promise<void>;
  toggleExpand: (uid: string) => void;
};

function files(n: number) {
  return Array.from(
    { length: n },
    (_, i) => new File(['x'], `扫描件-${i}.png`),
  );
}

async function clickSubmit() {
  const button = [...document.body.querySelectorAll('button')].find((item) =>
    (item.textContent ?? '').replaceAll(' ', '').includes('提交'),
  );
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await flush();
}

describe('e16-t03 BatchUploadDialog（批量上传）', () => {
  it('打开时加载类型 / 位置树 / 上传策略，默认每个文件一份', async () => {
    await mountDialog();
    const vm = mounted[0]?.vm as DialogVm;
    expect(fetchCatalogReportTypes).toHaveBeenCalled();
    expect(fetchReportLocations).toHaveBeenCalledWith('301');
    expect(fetchUploadPolicy).toHaveBeenCalled();
    expect(vm.mode).toBe('ONE_PER_FILE');
  });

  it('oNE_PER_FILE：逐个上传全部文件后提交，载荷含逐份名称 / 日期覆盖', async () => {
    const wrapper = await mountDialog({ preset: PRESET });
    const vm = wrapper.vm as DialogVm;
    await vm.onTypeChange('LAB');
    await flush(1);
    vm.onNameChange('12');
    vm.enqueueFiles(files(3));
    expect(vm.rows).toHaveLength(3);
    expect(vm.rows.every((row) => row.status === 'pending')).toBe(true);

    uploadFile.mockImplementation(async (file: File) => ({
      fileId: `f-${file.name}`,
      format: 'png',
      mime: 'image/png',
      originalName: file.name,
      pages: 1,
      sha256: 'hash',
      size: file.size,
    }));
    await vm.startUpload();
    await flush(2);
    expect(uploadFile).toHaveBeenCalledTimes(3);
    expect(vm.rows.every((row) => row.status === 'done')).toBe(true);

    // 第二份逐份调整名称与报告时间
    const second = vm.rows[1]?.uid as string;
    vm.onItemNameChange(second, '13');
    vm.onItemDateChange(second, '2026-09-09');

    batchCreateReports.mockResolvedValue({
      fileCount: 3,
      mode: 'ONE_PER_FILE',
      reportCount: 3,
      reports: [],
    });
    await clickSubmit();

    expect(batchCreateReports).toHaveBeenCalledTimes(1);
    const payload = batchCreateReports.mock.calls.at(0)?.at(0) as {
      fileIds: string[];
      items: Array<{ fileId: string; nameId: string; reportDate: string }>;
      locations: unknown[];
      mode: string;
      nameId: null | string;
      typeCode: string;
    };
    expect(payload.mode).toBe('ONE_PER_FILE');
    expect(payload.typeCode).toBe('LAB');
    expect(payload.nameId).toBe('12');
    expect(payload.locations).toEqual(PRESET, '公共位置 = 预选进入位置');
    expect(payload.fileIds).toEqual([
      'f-扫描件-0.png',
      'f-扫描件-1.png',
      'f-扫描件-2.png',
    ]);
    expect(payload.items).toEqual([
      { fileId: 'f-扫描件-1.png', nameId: '13', reportDate: '2026-09-09' },
    ]);
  });

  it('失败可重试：单文件上传失败标记失败，重试成功后才能提交', async () => {
    const wrapper = await mountDialog({ preset: undefined });
    const vm = wrapper.vm as DialogVm;
    await vm.onTypeChange('LAB');
    await flush(1);
    vm.onNameChange('12');
    vm.enqueueFiles(files(1));

    uploadFile.mockRejectedValueOnce(new Error('boom'));
    await vm.startUpload();
    await flush(2);
    expect(vm.rows[0]?.status).toBe('failed');

    await clickSubmit();
    expect(batchCreateReports).not.toHaveBeenCalled();

    uploadFile.mockResolvedValueOnce({
      fileId: 'f-1',
      format: 'png',
      mime: 'image/png',
      originalName: '扫描件-0.png',
      pages: 1,
      sha256: 'hash',
      size: 1,
    });
    // 行重试（与表格「重试」按钮同一方法）
    await (
      wrapper.vm as unknown as {
        uploadRow: (row: { uid: string }) => Promise<void>;
      }
    ).uploadRow(vm.rows[0] as { uid: string });
    await flush(2);
    expect(vm.rows[0]?.status).toBe('done');
  });

  it('超过单批 50 个文件时入队拦截并提示', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as DialogVm;
    vm.enqueueFiles(files(60));
    expect(vm.rows).toHaveLength(
      50,
      '只入队 50 个（文案见 batchLimitText 单测）',
    );
  });

  it('位置为空提交被拦：显示「请至少选择 1 个位置」', async () => {
    const wrapper = await mountDialog();
    const vm = wrapper.vm as DialogVm;
    await vm.onTypeChange('LAB');
    await flush(1);
    vm.onNameChange('12');
    vm.enqueueFiles(files(1));
    uploadFile.mockResolvedValue({
      fileId: 'f-1',
      format: 'png',
      mime: 'image/png',
      originalName: '扫描件-0.png',
      pages: 1,
      sha256: 'hash',
      size: 1,
    });
    await vm.startUpload();
    await flush(2);

    // 无预选位置（打开对话框 preset 为空）→ 提交被拦
    await clickSubmit();
    expect(document.body.textContent ?? '').toContain('请至少选择 1 个位置');
    expect(batchCreateReports).not.toHaveBeenCalled();
  });
});
