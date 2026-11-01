import type { TenantDictItemView, TenantDictTypeView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  emptyDictModel,
  itemCountText,
  toDictModel,
  toItemModel,
  validateDictModel,
  validateDictUpdate,
  validateItemModel,
} from './dicts';

const gender: TenantDictTypeView = {
  code: 'gender',
  description: null,
  disabledPlatformCount: 0,
  itemCount: 3,
  name: '性别',
  source: 'PLATFORM',
  status: 'ENABLED',
  tenantItemCount: 0,
  updatedAt: null,
};

const ownItem: TenantDictItemView = {
  editable: true,
  itemCode: 'X_OTHER',
  label: '其他（本院）',
  sortNo: 30,
  source: 'TENANT',
  status: 'ENABLED',
  updatedAt: null,
};

describe('e06-t10 租户字典逻辑', () => {
  it('本院字典编码与平台字典冲突要在前端拦住（后端 40902 兜底）', () => {
    const model = emptyDictModel();
    model.code = 'gender';
    model.name = '本院性别';
    expect(validateDictModel(model, [gender])).toContain(
      '字典编码已存在（含平台字典）',
    );

    model.code = 'UPPER';
    expect(validateDictModel(model, [gender])).toContain(
      '编码必须是小写字母开头的 2–64 位小写字母、数字或下划线',
    );

    model.code = 'local_referral';
    expect(validateDictModel(model, [gender])).toEqual([]);

    const update = toDictModel({ ...gender, source: 'TENANT' });
    update.name = ' ';
    expect(validateDictUpdate(update)).toContain('名称不能为空');
  });

  it('本院项编码不能与平台项或本院项重复（含平台项）', () => {
    const itemModel = {
      itemCode: '1',
      label: '男生',
      sortNo: 10,
      status: 'ENABLED' as const,
    };
    expect(validateItemModel(itemModel, ['1', '2'], true)).toContain(
      '项编码已存在（含平台项）',
    );
    expect(validateItemModel(itemModel, ['2'], true)).toEqual([]);
    expect(validateItemModel(toItemModel(ownItem), ['X_OTHER'], false)).toEqual(
      [],
    );
  });

  it('生效项数文案：平台字典带停用计数、本院字典只显示数量', () => {
    expect(itemCountText(gender)).toBe('3');
    expect(
      itemCountText({ ...gender, disabledPlatformCount: 1, itemCount: 2 }),
    ).toBe('2（停用平台项 1）');
    expect(
      itemCountText({
        ...gender,
        source: 'TENANT',
        itemCount: 5,
        tenantItemCount: 5,
      }),
    ).toBe('5');
  });
});
