import type { H5Info } from './api';

/**
 * E16-T10 / A-ATT-015、C-SCAN-008（pages.md §6.2）单测：手机上传页纯逻辑——
 * 令牌解析、二维码预选、表单校验（与电脑端同口径）、提交载荷、拍照队列与倒计时。
 */
import { describe, expect, it } from 'vitest';

import {
  canSubmit,
  countdownText,
  createPhotoItem,
  formErrors,
  initialForm,
  photoStatusText,
  queueSummary,
  submitLocations,
  tokenFromHash,
} from './upload-flow';

function info(overrides: Partial<H5Info> = {}): H5Info {
  return {
    patientName: '张*明',
    medicalRecordNo: 'MR****01',
    wardName: '示例二病区',
    today: '2026-11-09',
    types: [
      {
        code: 'ECHO',
        name: '心脏超声',
        nameRequired: true,
        names: [{ id: '55', name: '经胸超声心动图' }],
      },
      { code: 'OTHER', name: '其他资料', nameRequired: false, names: [] },
    ],
    locationOptions: [
      {
        key: 'baseline:echo',
        label: '基线资料 · 超声检查',
        value: {
          linkType: 'baseline',
          linkRecordId: null,
          anchorType: 'category',
          anchorKey: 'echo',
          anchorTemplateVersion: 3,
        },
      },
      {
        key: 'rec:12',
        label: '随访记录 · 第2年随访 2026-06-10',
        value: {
          linkType: 'followup_record',
          linkRecordId: '12',
          anchorType: null,
          anchorKey: null,
          anchorTemplateVersion: 5,
        },
      },
    ],
    presetKey: null,
    requireLogin: false,
    expiresAtEpochSecond: 1_900_000_000,
    ...overrides,
  };
}

describe('tokenFromHash（#/u/{令牌}，令牌只在 URL 片段）', () => {
  it('解析合法令牌', () => {
    expect(tokenFromHash('#/u/abcd1234abcd1234abcd1234abcd1234')).toBe(
      'abcd1234abcd1234abcd1234abcd1234',
    );
  });

  it('拒绝空片段、非法字符与查询串形态', () => {
    expect(tokenFromHash('')).toBeNull();
    expect(tokenFromHash('#/u/')).toBeNull();
    expect(tokenFromHash('#/u/有中文令牌')).toBeNull();
    expect(tokenFromHash('#/upload?token=abcd1234abcd1234')).toBeNull();
  });
});

describe('initialForm（二维码预选位置，A-ATT-015）', () => {
  it('带预选 key 时位置自动预选、报告时间缺省今天', () => {
    const form = initialForm(info({ presetKey: 'baseline:echo' }));
    expect(form.locationKeys).toEqual(['baseline:echo']);
    expect(form.reportDate).toBe('2026-11-09');
    expect(form.typeCode).toBeNull();
  });

  it('无预选时不预选；仅一个类型时直接选中', () => {
    const single = info({
      presetKey: null,
      types: [
        { code: 'OTHER', name: '其他资料', nameRequired: false, names: [] },
      ],
    });
    const form = initialForm(single);
    expect(form.locationKeys).toEqual([]);
    expect(form.typeCode).toBe('OTHER');
  });
});

describe('formErrors（与电脑端同口径：至少 1 个位置、名称必填类型须选名称）', () => {
  it('全空时逐项报错', () => {
    const errors = formErrors(
      { typeCode: null, nameId: null, reportDate: null, locationKeys: [] },
      info().types,
    );
    expect(errors.typeCode).toBe('请选择资料类型');
    expect(errors.reportDate).toBe('请选择报告时间');
    expect(errors.locations).toBe('请至少选择 1 个位置');
  });

  it('名称必填类型缺名称报错，非必填类型不报', () => {
    const missing = formErrors(
      {
        typeCode: 'ECHO',
        nameId: null,
        reportDate: '2026-11-09',
        locationKeys: ['rec:12'],
      },
      info().types,
    );
    expect(missing.nameId).toBe('该类型必须选择名称（心脏超声）');
    const optional = formErrors(
      {
        typeCode: 'OTHER',
        nameId: null,
        reportDate: '2026-11-09',
        locationKeys: ['rec:12'],
      },
      info().types,
    );
    expect(optional.nameId).toBeUndefined();
    expect(Object.keys(optional)).toHaveLength(0);
  });
});

describe('submitLocations（下拉项 value 五元组原样回传）', () => {
  it('按选中顺序回传 value，未知 key 忽略', () => {
    const form = {
      typeCode: 'OTHER',
      nameId: null,
      reportDate: '2026-11-09',
      locationKeys: ['rec:12', 'baseline:echo', 'gone'],
    };
    const options = info().locationOptions;
    const values = submitLocations(form, options);
    expect(values).toHaveLength(2);
    expect(values[0]).toEqual(options[1]?.value);
    expect(values[1]).toEqual(options[0]?.value);
  });
});

describe('拍照队列（可连续多张、全部成功才可提交）', () => {
  it('空队列不可提交；有失败项不可提交', () => {
    expect(canSubmit([])).toBe(false);
    const failed = {
      ...createPhotoItem('第1页.jpg'),
      status: 'failed' as const,
    };
    const done = { ...createPhotoItem('第2页.jpg'), status: 'done' as const };
    expect(canSubmit([failed, done])).toBe(false);
  });

  it('全部完成可提交；统计与状态文案齐备', () => {
    const a = {
      ...createPhotoItem('第1页.jpg'),
      status: 'done' as const,
      fileId: '101',
    };
    const b = {
      ...createPhotoItem('第2页.jpg'),
      status: 'uploading' as const,
      progress: 40,
    };
    expect(canSubmit([a, b])).toBe(false);
    expect(queueSummary([a, b])).toEqual({ total: 2, done: 1, failed: 0 });
    expect(photoStatusText('compressing')).toBe('压缩中…');
    expect(photoStatusText('uploading')).toBe('上传中…');
    expect(photoStatusText('failed')).toBe('上传失败');
    const bDone = {
      ...b,
      status: 'done' as const,
      progress: 100,
      fileId: '102',
    };
    expect(canSubmit([a, bDone])).toBe(true);
  });

  it('新队列项 id 递增（页序即 sort_no）', () => {
    expect(createPhotoItem('a.jpg').id).toBeLessThan(
      createPhotoItem('b.jpg').id,
    );
  });
});

describe('countdownText（15 分钟倒计时）', () => {
  it('分:秒展示，不足一分钟按剩余秒，过期归零', () => {
    expect(countdownText(1_000_060, 1_000_000)).toBe('01:00');
    expect(countdownText(1_000_059, 1_000_000)).toBe('00:59');
    expect(countdownText(1_000_000, 1_000_030)).toBe('00:00');
    // 15 分钟（timings.mobileUploadQrMinutes，A-ATT-015）
    expect(countdownText(1_000_900, 1_000_000)).toBe('15:00');
  });
});
