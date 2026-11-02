import type { TenantDictItemView, TenantDictTypeView } from '@crdp/api';

import { dictLogActionText, dictLogTargetText } from '@crdp/api';
import { describe, expect, it } from 'vitest';

import {
  emptyDictModel,
  emptyItemModel,
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
  aliases: null,
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

describe('e11-t08 业务字典：别名与变更记录', () => {
  it('本院项的别名进编辑模型；空别名为空串（A-FLD-012）', () => {
    const model = toItemModel({ ...ownItem, aliases: '老写法、旧名称' });
    expect(model.aliases).toBe('老写法、旧名称');
    expect(toItemModel(ownItem).aliases).toBe('');
  });

  it('别名超长 255 拦住（与后端 40001 同口径）', () => {
    const model = {
      ...emptyItemModel(),
      aliases: '别'.repeat(256),
      itemCode: 'X_OK',
      label: '其他',
    };
    expect(validateItemModel(model, [], true)).toContain(
      '别名不能超过 255 个字符',
    );
    const ok = { ...model, aliases: '别'.repeat(255) };
    expect(validateItemModel(ok, [], true)).toEqual([]);
  });

  it('变更记录动作与对象文案（A-FLD-009）', () => {
    expect(dictLogActionText('ITEM_ADD')).toBe('新增取值');
    expect(dictLogActionText('ITEM_UPDATE')).toBe('维护取值');
    expect(dictLogActionText('DICT_CREATE')).toBe('新建字典');
    expect(dictLogActionText('DICT_UPDATE')).toBe('维护字典');
    expect(
      dictLogTargetText({
        action: 'ITEM_UPDATE',
        afterJson: '{"label":"本院分型（示例）"}',
        beforeJson: null,
        createdAt: '2027-03-01T09:00:00+08:00',
        dictCode: 'patient_type',
        id: 1,
        itemCode: 'X_LOCAL',
        operatorId: 7,
        operatorName: '张医生（示例）',
      }),
    ).toBe('X_LOCAL · 本院分型（示例）');
    expect(
      dictLogTargetText({
        action: 'DICT_CREATE',
        afterJson: '{"name":"转诊来源"}',
        beforeJson: null,
        createdAt: '2027-03-01T09:00:00+08:00',
        dictCode: 'local_referral',
        id: 2,
        itemCode: null,
        operatorId: 7,
        operatorName: null,
      }),
    ).toBe('local_referral');
  });
});
