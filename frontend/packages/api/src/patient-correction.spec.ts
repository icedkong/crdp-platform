import { describe, expect, it } from 'vitest';

import { identityCorrectionChanges } from './patient-correction';

describe('oqr-04 敏感身份请求', () => {
  it('只提交主动填写的新值，不将空白输入当作删除现有身份', () => {
    expect(
      identityCorrectionChanges({
        name: ' 更正姓名（示例） ',
        medicalRecordNo: '',
        address: '  ',
      }),
    ).toEqual({ name: '更正姓名（示例）' });
  });
  it('显式清除与未填写不同，可选身份字段清除传 null', () => {
    expect(identityCorrectionChanges({}, ['address'])).toEqual({
      address: null,
    });
  });
});
