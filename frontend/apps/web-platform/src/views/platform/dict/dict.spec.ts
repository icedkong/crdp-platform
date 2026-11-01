import type { PlatformDictItemView, PlatformDictView } from '@crdp/api';

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
} from './dict';

const gender: PlatformDictView = {
  code: 'gender',
  description: null,
  enabledItemCount: 2,
  itemCount: 3,
  name: '性别',
  status: 'ENABLED',
  updatedAt: null,
};

const male: PlatformDictItemView = {
  itemCode: '1',
  label: '男',
  sortNo: 10,
  status: 'ENABLED',
  updatedAt: null,
};

describe('e06-t10 平台字典管理逻辑', () => {
  it('编码规则与冲突：小写字母开头；与已有编码冲突报错（PLT-CFG-001 编码不可改且唯一）', () => {
    const model = emptyDictModel();
    model.code = 'Gender';
    model.name = '性别';
    expect(validateDictModel(model, [gender])).toContain(
      '编码必须是小写字母开头的 2–64 位小写字母、数字或下划线',
    );

    model.code = 'gender';
    expect(validateDictModel(model, [gender])).toContain('字典编码已存在');

    model.code = 'id_type';
    expect(validateDictModel(model, [gender])).toEqual([]);
  });

  it('名称必填与长度上限（维护时不校验编码）', () => {
    const update = toDictModel(gender);
    update.name = ' ';
    expect(validateDictUpdate(update)).toContain('名称不能为空');
    update.name = '性别（修订）';
    expect(validateDictUpdate(update)).toEqual([]);

    const tooLong = emptyDictModel();
    tooLong.code = 'ok_code';
    tooLong.name = '长'.repeat(129);
    expect(validateDictModel(tooLong, [])).toContain('名称不能超过 128 个字符');
  });

  it('项编码唯一与必填：新建查重、编辑不查；停用项仍在全部项里（停用不删除）', () => {
    const item = emptyItemModel();
    item.itemCode = '1';
    item.label = '男生';
    expect(validateItemModel(item, ['1', '2'], true)).toContain('项编码已存在');
    expect(validateItemModel(item, ['2'], true)).toEqual([]);
    // 编辑：编码不可改，不再查格式与冲突
    expect(validateItemModel(toItemModel(male), ['1'], false)).toEqual([]);

    item.itemCode = 'bad code';
    expect(validateItemModel(item, [], true)).toContain(
      '项编码必须是 1–64 位字母、数字、下划线或中横线',
    );

    const noLabel = emptyItemModel();
    noLabel.itemCode = 'X';
    noLabel.label = ' ';
    expect(validateItemModel(noLabel, [], true)).toContain('显示文字不能为空');
  });

  it('项数列：生效 / 全部（停用项不进租户生效集）', () => {
    expect(itemCountText(gender)).toBe('2 / 3');
  });
});
