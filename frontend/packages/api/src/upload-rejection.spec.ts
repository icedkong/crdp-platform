// E22-T16 / 等保 D-01 前端面：上传失败分类与行级文案（docs/api/README.md §2.3
// 错误码登记三步曲的前端一步——50300 病毒扫描暂不可用（fail closed）、40001 病毒检出拒绝）。
// 后端契约见 FileUploadService：检出「文件未通过病毒检测（检出 …），已拒绝上传」（40001）、
// 扫描不可用「病毒扫描服务暂不可用，请稍后重试」（50300，HTTP 503）。
import { describe, expect, it } from 'vitest';

import { uploadFailureInfo, uploadFailureText } from './upload-rejection';

/** axios 形错误（requestClient 抛出：响应体在 response.data）。 */
function axiosError(code: number, message: string) {
  return Object.assign(new Error(message), {
    response: { data: { code, message } },
  });
}

/** mock / 直调形错误（响应体在 data，同 login-rejection 的 errorBody 兼容口径）。 */
function dataError(code: number, message: string) {
  return Object.assign(new Error(message), { data: { code, message } });
}

const FALLBACK = '上传失败，可重试（格式与大小按租户配置）';
const VIRUS_MESSAGE =
  '文件未通过病毒检测（检出 Win.Test.EICAR.HDB-1），已拒绝上传';
const UNAVAILABLE_MESSAGE = '病毒扫描服务暂不可用，请稍后重试';

describe('e22-t16 uploadFailureInfo（上传失败分类）', () => {
  it('50300 = 扫描不可用（服务恢复后重试可成功）', () => {
    expect(uploadFailureInfo(axiosError(50_300, UNAVAILABLE_MESSAGE))).toEqual({
      kind: 'scan-unavailable',
      message: UNAVAILABLE_MESSAGE,
    });
  });

  it('40001 且 message 含「病毒检测」= 病毒检出（重试同一文件无意义）', () => {
    expect(uploadFailureInfo(axiosError(40_001, VIRUS_MESSAGE))).toEqual({
      kind: 'virus',
      message: VIRUS_MESSAGE,
    });
  });

  it('40001 其他 = 参数 / 格式类拒绝（不属于病毒扫描面）', () => {
    const info = uploadFailureInfo(axiosError(40_001, '文件类型不支持'));
    expect(info.kind).toBe('other');
    expect(info.message).toBe('文件类型不支持');
  });

  it('网络错误 / 无响应体 = other（message 为空）', () => {
    expect(uploadFailureInfo(new Error('Network Error'))).toEqual({
      kind: 'other',
      message: null,
    });
    expect(uploadFailureInfo(undefined)).toEqual({
      kind: 'other',
      message: null,
    });
  });

  it('错误对象形态兼容：response.data 与 data 两种放法同口径', () => {
    expect(uploadFailureInfo(dataError(50_300, UNAVAILABLE_MESSAGE)).kind).toBe(
      'scan-unavailable',
    );
    expect(uploadFailureInfo(dataError(40_001, VIRUS_MESSAGE)).kind).toBe(
      'virus',
    );
  });
});

describe('e22-t16 uploadFailureText（行级文案）', () => {
  it('病毒检出：显示后端原文（不引导对同一文件重试）', () => {
    expect(uploadFailureText(axiosError(40_001, VIRUS_MESSAGE), FALLBACK)).toBe(
      VIRUS_MESSAGE,
    );
  });

  it('扫描不可用：显示后端原文（自带「请稍后重试」）', () => {
    expect(
      uploadFailureText(axiosError(50_300, UNAVAILABLE_MESSAGE), FALLBACK),
    ).toBe(UNAVAILABLE_MESSAGE);
  });

  it('其他失败：保持调用方通用文案（格式 / 大小提示口径不变）', () => {
    expect(
      uploadFailureText(axiosError(40_001, '文件类型不支持'), FALLBACK),
    ).toBe(FALLBACK);
    expect(uploadFailureText(new Error('Network Error'), FALLBACK)).toBe(
      FALLBACK,
    );
  });

  it('病毒 / 扫描不可用但后端无 message：回落通用文案（不显示空白）', () => {
    const noMessage = Object.assign(new Error('x'), {
      response: { data: { code: 50_300 } },
    });
    expect(uploadFailureText(noMessage, FALLBACK)).toBe(FALLBACK);
  });
});
