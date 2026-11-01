import type { PlatformParamView } from '@crdp/api';

import { paramRangeText } from '@crdp/api';
import { describe, expect, it } from 'vitest';

import {
  emptyParamModel,
  levelText,
  toParamModel,
  validateParamModel,
  validateRangeShape,
  valuePlaceholder,
} from './param';

const pool: PlatformParamView = {
  description: null,
  name: '每租户连接池上限',
  paramKey: 'tenant.db.pool.max',
  platformValue: '20',
  tenantOverridable: true,
  updatedAt: null,
  valueRange: '{"min":5,"max":50}',
  valueType: 'NUMBER',
};

describe('e06-t10 平台系统参数逻辑', () => {
  it('参数键格式与名称必填（PLT-CFG-003：参数键全局唯一、创建后不可改）', () => {
    const model = emptyParamModel();
    model.paramKey = 'BadKey';
    model.name = '示例';
    model.valueType = 'NUMBER';
    model.platformValue = '20';
    model.tenantOverridable = true;
    model.valueRange = '{"min":1,"max":99}';
    expect(validateParamModel(model, true)).toContain(
      '参数键必须是小写字母开头的 3–64 位小写字母、数字或点（如 tenant.db.pool.max）',
    );
    // 维护不校验键
    model.paramKey = 'BadKey';
    expect(validateParamModel(model, false).join(',')).not.toContain('参数键');

    model.paramKey = 'tenant.db.pool.max';
    model.name = ' ';
    expect(validateParamModel(model, true)).toContain('名称不能为空');
  });

  it('可覆盖参数必须带范围（布尔 / JSON 除外，与后端口径一致）', () => {
    const model = toParamModel(pool);
    model.valueRange = '';
    expect(validateParamModel(model, false)).toContain(
      '租户可覆盖的参数必须配置取值范围（布尔 / JSON 除外）',
    );
    model.valueType = 'BOOLEAN';
    expect(validateParamModel(model, false).join(',')).not.toContain(
      '取值范围',
    );
  });

  it('平台默认值也要过范围校验', () => {
    const model = toParamModel(pool);
    model.platformValue = '500';
    expect(validateParamModel(model, false)).toContain(
      '平台默认值：取值不得大于 50',
    );
    model.platformValue = 'abc';
    expect(validateParamModel(model, false)).toContain(
      '平台默认值：取值必须是数字',
    );
  });

  it('范围 JSON 形状按值类型校验', () => {
    expect(validateRangeShape('NUMBER', '{"min":5,"max":50}')).toBeNull();
    expect(validateRangeShape('NUMBER', '{"min":"a"}')).toContain(
      '数值参数的取值范围 min / max 必须是数字',
    );
    expect(validateRangeShape('TEXT', '{"maxLength":0}')).toContain(
      '文本参数的取值范围必须是 1–512 的 maxLength',
    );
    expect(validateRangeShape('ENUM', '{"options":[]}')).toContain(
      '枚举参数的取值范围必须是非空 options 字符串数组',
    );
    expect(validateRangeShape('BOOLEAN', '{"min":1}')).toContain(
      '布尔参数没有取值范围',
    );
    expect(validateRangeShape('TEXT', 'not-json')).toBe(
      '取值范围必须是 JSON 对象',
    );
  });

  it('层级与取值范围展示文案', () => {
    expect(levelText(true)).toBe('租户可覆盖');
    expect(levelText(false)).toBe('平台锁定');
    expect(paramRangeText('NUMBER', '{"min":5,"max":50}')).toBe('5–50');
    expect(paramRangeText('TEXT', '{"maxLength":255}')).toBe('≤ 255 字');
    expect(paramRangeText('ENUM', '{"options":["DAILY","WEEKLY"]}')).toBe(
      'DAILY / WEEKLY',
    );
    expect(paramRangeText('NUMBER', null)).toBe('—');
    expect(paramRangeText('BOOLEAN', null)).toBe('不限');
  });

  it('值输入提示按类型给样例', () => {
    expect(valuePlaceholder('BOOLEAN', null)).toBe('true 或 false');
    expect(valuePlaceholder('NUMBER', '{"min":5,"max":50}')).toContain('范围');
    expect(valuePlaceholder('JSON', null)).toContain('合法 JSON');
  });
});
