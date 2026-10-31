import { TENANT_STATUS_TEXT } from '@crdp/api';
import { describe, expect, it } from 'vitest';

import {
  canOpenProvision,
  EMPTY_FILTER,
  expireText,
  rowNumber,
  statusText,
  toTenantListQuery,
  userCountText,
} from './list';

describe('e05-T01 租户列表逻辑', () => {
  it('筛选模型转查询参数：空条件只剩分页，非空条件全带上', () => {
    expect(toTenantListQuery(EMPTY_FILTER)).toEqual({
      pageNo: 1,
      pageSize: 20,
    });
    expect(
      toTenantListQuery(
        {
          expireBefore: '2027-06-30',
          keyword: '  示例医院 ',
          status: 'ENABLED',
        },
        2,
        50,
      ),
    ).toEqual({
      expireBefore: '2027-06-30',
      keyword: '示例医院',
      pageNo: 2,
      pageSize: 50,
      status: 'ENABLED',
    });
  });

  it('到期日列：长期有效显示「长期」，有到期日显示日期（PLT-TEN-001）', () => {
    expect(expireText({ expireAt: null, expireType: 'LONG_TERM' })).toBe(
      '长期',
    );
    expect(expireText({ expireAt: '2027-01-31', expireType: 'FIXED' })).toBe(
      '2027-01-31',
    );
  });

  it('用户数列：后端取不到时显示「—」', () => {
    expect(userCountText({ userCount: null })).toBe('—');
    expect(userCountText({ userCount: 3 })).toBe('3');
  });

  it('状态文案映射覆盖 PLT-TEN-010 七态与开通中，未知值原样返回', () => {
    expect(statusText('TRIAL')).toBe('试用');
    expect(statusText('GRACE')).toBe('宽限期');
    expect(statusText('PROVISIONING')).toBe('开通中');
    expect(Object.keys(TENANT_STATUS_TEXT)).toHaveLength(7);
    expect(statusText('WHATEVER')).toBe('WHATEVER');
  });

  it('开通中的租户才显示开通进度入口', () => {
    expect(canOpenProvision('PROVISIONING')).toBe(true);
    expect(canOpenProvision('TRIAL')).toBe(false);
    expect(canOpenProvision('ENABLED')).toBe(false);
  });

  it('序号跨页连续：第 2 页第 1 行是 21', () => {
    expect(rowNumber(0, 2, 20)).toBe(21);
    expect(rowNumber(4, 1, 20)).toBe(5);
  });
});
