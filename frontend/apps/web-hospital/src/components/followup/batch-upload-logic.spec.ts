// E16-T03 / A-ATT-014、C-SCAN-007（pages.md §6.3）：批量上传纯逻辑——一次 ≤ 50 个文件、
// MERGE 模式受每份上限约束（A-ATT-004 验收：单份最多 10 个文件，超限拦截）、
// ONE_PER_FILE 逐份校验（名称按类型必填、报告时间）与提交载荷组装（行覆盖优先、
// 只带与公共值不同的行）
import type { ReportLocationInput } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  acceptBatchFiles,
  batchFormHasError,
  batchItemErrors,
  batchLimitText,
  buildBatchPayload,
  effectiveItemValue,
  MAX_BATCH_FILES,
  validateBatchForm,
} from './batch-upload-logic';

const LOC: ReportLocationInput[] = [
  {
    anchorKey: 'basic',
    anchorTemplateVersion: 3,
    anchorType: 'category',
    linkType: 'baseline',
  },
];

function fileOf(name: string): File {
  return new File(['x'], name, { type: 'image/png' });
}

function row(overrides: Record<string, unknown> = {}) {
  return {
    fileId: '900',
    name: '扫描件（示例）.png',
    size: 12,
    status: 'done',
    uid: 'u1',
    ...overrides,
  };
}

describe('e16-t03 acceptBatchFiles（数量拦截）', () => {
  it('oNE_PER_FILE：累计超过 50 个时只收满 50 个并提示', () => {
    const incoming = Array.from({ length: 5 }, (_, i) => fileOf(`批-${i}.png`));
    const result = acceptBatchFiles(48, incoming, 'ONE_PER_FILE', 10);
    expect(result.accepted).toHaveLength(2);
    expect(result.error).toBe('一次最多上传 50 个文件');
  });

  it('oNE_PER_FILE：已满 50 个时全部拒收', () => {
    const result = acceptBatchFiles(
      MAX_BATCH_FILES,
      [fileOf('批-超.png')],
      'ONE_PER_FILE',
      10,
    );
    expect(result.accepted).toHaveLength(0);
    expect(result.error).toContain('50');
  });

  it('mERGE：受每份上限约束（A 默认 10），超限拦截文案带每份上限', () => {
    const eleven = Array.from({ length: 11 }, (_, i) => fileOf(`并-${i}.png`));
    const result = acceptBatchFiles(0, eleven, 'MERGE', 10);
    expect(result.accepted).toHaveLength(10, '只收满每份上限');
    expect(result.error).toBe('合并为一份时最多 10 个文件（每份报告单上限）');
  });

  it('mERGE：租户上限大于 50 时仍按 50 收口（A-ATT-014）', () => {
    const result = acceptBatchFiles(
      0,
      Array.from({ length: 60 }, (_, i) => fileOf(`c-${i}.png`)),
      'MERGE',
      50,
    );
    expect(result.accepted).toHaveLength(50);
  });

  it('策略未加载（0）时 MERGE 也只按 50 收口，不因缺策略锁死', () => {
    expect(batchLimitText('MERGE', 0)).toBe('一次最多上传 50 个文件');
    const result = acceptBatchFiles(
      0,
      Array.from({ length: 51 }, (_, i) => fileOf(`d-${i}.png`)),
      'MERGE',
      0,
    );
    expect(result.accepted).toHaveLength(50);
  });
});

describe('e16-t03 validateBatchForm / batchFormHasError（公共校验）', () => {
  const base = {
    fileCount: 2,
    filesReady: true,
    locations: LOC,
    maxFilesPerReport: 10,
    mode: 'ONE_PER_FILE' as const,
    nameId: '12',
    nameRequired: true,
    reportDate: '2026-10-28',
    typeCode: 'LAB',
  };

  it('全部就绪时无错误', () => {
    const errors = validateBatchForm(base);
    expect(batchFormHasError(errors)).toBe(false);
  });

  it('类型 / 名称（必填类型）/ 报告时间 / 位置逐项提示', () => {
    const errors = validateBatchForm({
      ...base,
      locations: [],
      nameId: null,
      reportDate: null,
      typeCode: null,
    });
    expect(errors.typeCode).toBe('请选择类型');
    expect(errors.nameId).toBe('请选择名称');
    expect(errors.reportDate).toBe('请填写报告时间');
    expect(errors.locations).toBe('请至少选择 1 个位置');
    expect(batchFormHasError(errors)).toBe(true);
  });

  it('还有未上传成功的文件时不许提交；一个文件都没选也提示', () => {
    expect(validateBatchForm({ ...base, filesReady: false }).files).toContain(
      '未上传成功',
    );
    expect(
      validateBatchForm({ ...base, fileCount: 0, filesReady: true }).files,
    ).toBe('请选择文件');
  });
});

describe('e16-t03 batchItemErrors（ONE_PER_FILE 逐份校验，按生效值）', () => {
  const emptyCommon = { nameId: null, reportDate: null };

  it('名称必填类型：行与公共都未选名称提示；行回落公共名称后只查报告时间', () => {
    const errors = batchItemErrors(
      [row({ uid: 'a', nameId: undefined, reportDate: undefined })],
      'ONE_PER_FILE',
      true,
      emptyCommon,
    );
    expect(errors.get('a')).toBe('请选择名称');

    const withCommonName = batchItemErrors(
      [row({ uid: 'a', nameId: undefined, reportDate: null })],
      'ONE_PER_FILE',
      true,
      { nameId: '12', reportDate: null },
    );
    expect(withCommonName.get('a')).toBe(
      '请填写报告时间',
      '公共名称生效，只查时间',
    );

    const clean = batchItemErrors(
      [row({ uid: 'a', nameId: '12', reportDate: null })],
      'ONE_PER_FILE',
      true,
      emptyCommon,
    );
    expect(clean.get('a')).toBe('请填写报告时间');
  });

  it('mERGE 模式不逐份校验（合并为一份用公共值）', () => {
    const errors = batchItemErrors(
      [row({ uid: 'a', nameId: undefined, reportDate: undefined })],
      'MERGE',
      true,
      emptyCommon,
    );
    expect(errors.size).toBe(0);
  });

  it('名称选填类型（nameRequired=false）只查报告时间', () => {
    const errors = batchItemErrors(
      [row({ uid: 'a', nameId: undefined, reportDate: undefined })],
      'ONE_PER_FILE',
      false,
      emptyCommon,
    );
    expect(errors.get('a')).toBe('请填写报告时间');
  });
});

describe('e16-t03 buildBatchPayload（载荷组装）', () => {
  it('行覆盖优先：只带与公共值不同的行；fileIds 按行顺序', () => {
    const payload = buildBatchPayload({
      commonNameId: '12',
      commonReportDate: '2026-10-28',
      locations: LOC,
      mode: 'ONE_PER_FILE',
      patientId: '301',
      rows: [
        row({ uid: 'a', fileId: '901' }),
        row({
          uid: 'b',
          fileId: '902',
          nameId: '13',
          reportDate: '2026-09-09',
        }),
        row({ uid: 'c', fileId: '903', locations: LOC }),
      ],
      typeCode: 'LAB',
    });
    expect(payload.fileIds).toEqual(['901', '902', '903']);
    expect(payload.items).toEqual([
      { fileId: '902', nameId: '13', reportDate: '2026-09-09' },
      { fileId: '903', locations: LOC },
    ]);
  });

  it('全部行等于公共值时 items 为 undefined；未上传完成的行不进载荷', () => {
    const payload = buildBatchPayload({
      commonNameId: '12',
      commonReportDate: '2026-10-28',
      locations: LOC,
      mode: 'MERGE',
      patientId: '301',
      rows: [
        row({
          uid: 'a',
          fileId: '901',
          nameId: '12',
          reportDate: '2026-10-28',
        }),
        row({ uid: 'b', fileId: '902', status: 'failed' }),
      ],
      typeCode: 'LAB',
    });
    expect(payload.fileIds).toEqual(['901']);
    expect(payload.items).toBeUndefined();
    expect(payload.mode).toBe('MERGE');
  });

  it('effectiveItemValue：行空值回落公共值', () => {
    expect(
      effectiveItemValue(row({ nameId: undefined, reportDate: null }), {
        nameId: '12',
        reportDate: '2026-10-28',
      }),
    ).toEqual({ nameId: '12', reportDate: '2026-10-28' });
  });
});
