/**
 * E16-T05 / A-ATT-016、C-SCAN-014 · 全院报告单列表页逻辑（list-logic.ts）单测。
 *
 * 锁定：检索请求装配（空筛选项不提交、位置含「未指定位置」、日期范围拆为 from / to）、
 * 缩略图形态（图片 / 文件类型图标 / 无附件）、上传时间格式化（空格与 T 两种分隔）、
 * 响应式列集（平板保留主要列）、行点击进入患者档案报告单页签与患者副行文案。
 */

import type { ReportSearchRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  buildSearchPayload,
  EMPTY_FILTERS,
  fileCountText,
  formatUploadedAt,
  LOCATION_SCOPE_OPTIONS,
  patientReportsPath,
  patientSubText,
  previewFilesOf,
  TABLET_COLUMNS,
  thumbnailOf,
} from './list-logic';

const row = (overrides: Partial<ReportSearchRow> = {}): ReportSearchRow => ({
  createdAt: '2026-10-28 09:30:00.000',
  creatorName: '王医生（示例）',
  digitizeStatus: 'PENDING',
  fileCount: 2,
  firstFile: { fileId: '71', format: 'jpg' },
  id: '901',
  locations: [],
  mrn: 'MR****01',
  name: '血常规',
  patientId: '301',
  patientName: '张*明',
  reportDate: '2026-10-25',
  typeCode: 'LAB',
  typeName: '检验',
  visitNo: '105-2026-001',
  wardName: '示例二病区',
  ...overrides,
});

describe('e16-t05 buildSearchPayload（检索请求装配）', () => {
  it('空筛选全部不提交（后端缺省口径）', () => {
    expect(buildSearchPayload(EMPTY_FILTERS)).toEqual({});
  });

  it('关键词去空格；位置筛选含「未指定位置」；日期范围拆 from / to；病区非空才提交', () => {
    expect(
      buildSearchPayload({
        ...EMPTY_FILTERS,
        keyword: '  血常规 ',
        locationScope: 'UNASSIGNED',
        reportRange: ['2026-10-01', '2026-10-31'],
        typeCode: 'LAB',
        uploadedRange: ['2026-10-20', '2026-10-28'],
        uploaderId: '7',
        wardIds: ['5'],
      }),
    ).toEqual({
      creatorId: '7',
      keyword: '血常规',
      locationScope: 'UNASSIGNED',
      reportDateFrom: '2026-10-01',
      reportDateTo: '2026-10-31',
      typeCode: 'LAB',
      uploadedFrom: '2026-10-20',
      uploadedTo: '2026-10-28',
      wardIds: ['5'],
    });
  });

  it('位置选项包含「未指定位置」（验收口径）', () => {
    expect(
      LOCATION_SCOPE_OPTIONS.some((item) => item.value === 'UNASSIGNED'),
    ).toBe(true);
  });
});

describe('e16-t05 thumbnailOf（缩略图列）', () => {
  it('图片走预览链接、非图片显示格式、无附件为空', () => {
    expect(thumbnailOf(row())).toEqual({
      file: { fileId: '71', format: 'jpg' },
      kind: 'image',
    });
    expect(
      thumbnailOf(row({ firstFile: { fileId: '9', format: 'pdf' } })),
    ).toEqual({ kind: 'file', label: 'PDF' });
    expect(thumbnailOf(row({ firstFile: null }))).toBeNull();
  });
});

describe('e16-t05 formatUploadedAt（上传时间显示）', () => {
  it('空格分隔与 ISO T 分隔都归一为 YYYY-MM-DD HH:mm', () => {
    expect(formatUploadedAt('2026-10-28 09:30:00.000')).toBe(
      '2026-10-28 09:30',
    );
    expect(formatUploadedAt('2026-10-28T09:30:00')).toBe('2026-10-28 09:30');
  });
});

describe('e16-t05 列集与行文案', () => {
  it('平板保留主要列（缩略图 / 患者 / 类型 / 时间 / 位置 / 电子化）', () => {
    expect(TABLET_COLUMNS.map((column) => column.dataIndex)).toEqual([
      'thumb',
      'patientName',
      'typeName',
      'reportDate',
      'locations',
      'digitizeStatus',
    ]);
  });

  it('行点击进入患者档案报告单页签；患者副行含随访编号；附件数 0 显示「—」', () => {
    expect(patientReportsPath('301')).toBe('/followup/patient/301?tab=reports');
    expect(patientSubText(row())).toBe('MR****01 · 105-2026-001');
    expect(patientSubText(row({ visitNo: null }))).toBe('MR****01');
    expect(fileCountText(row({ fileCount: 0 }))).toBe('—');
    expect(fileCountText(row())).toBe('2');
  });
});

describe('e16-t06 previewFilesOf（缩略图点击 → 全屏查看器文件清单）', () => {
  it('首附件映射为查看器文件（fileId + format，名称取报告单名称，缺名称用类型）', () => {
    expect(previewFilesOf(row())).toEqual([
      { fileId: '71', format: 'jpg', name: '血常规' },
    ]);
    expect(
      previewFilesOf(
        row({ name: null, firstFile: { fileId: '72', format: 'pdf' } }),
      ),
    ).toEqual([{ fileId: '72', format: 'pdf', name: '检验' }]);
  });

  it('无附件（firstFile=null）返回 null，点击缩略图不打开查看器', () => {
    expect(previewFilesOf(row({ firstFile: null }))).toBeNull();
  });
});
