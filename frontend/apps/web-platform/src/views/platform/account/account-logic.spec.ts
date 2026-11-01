import type { PlatformAccountView } from '@crdp/api';

/**
 * E06-T04 平台账号页纯逻辑测试：新建 / 维护表单模型、提交请求形状、
 * 校验口径（账号名、手机号必填与格式、密码必填、角色必填）、重置密码提示文案。
 */
import { describe, expect, it } from 'vitest';

import {
  phoneText,
  resetResultLines,
  roleNamesText,
  toAccountEditModel,
  toAccountSaveRequest,
  validateAccountModel,
} from './account-logic';

const account: PlatformAccountView = {
  createdAt: '2026-11-01T08:00:00+08:00',
  displayName: '实施演示（示例）',
  id: 7,
  phoneMasked: '139****1234',
  roleIds: [2],
  roleNames: ['实施人员'],
  status: 'ENABLED',
  username: 'it.impl.demo',
};

describe('toAccountEditModel / toAccountSaveRequest', () => {
  it('新建空模型：账号名与密码为空、角色未选', () => {
    const model = toAccountEditModel(null, []);
    expect(model.username).toBe('');
    expect(model.password).toBe('');
    expect(model.roleIds).toEqual([]);
    expect(model.status).toBe('ENABLED');
  });

  it('维护模型不回填密码与明文手机号', () => {
    const model = toAccountEditModel(account, [2]);
    expect(model.password).toBe('');
    expect(model.phone).toBe('');
    expect(model.username).toBe('it.impl.demo');
  });

  it('新建请求带 username 与 password；维护请求不带', () => {
    const created = toAccountSaveRequest(
      {
        ...toAccountEditModel(null, []),
        displayName: '新账号（示例）',
        password: 'Str0ng!Pass',
        phone: '13900001234',
        roleIds: [2],
        username: 'impl.demo',
      },
      'create',
    );
    expect(created.username).toBe('impl.demo');
    expect(created.password).toBe('Str0ng!Pass');
    expect(created.status).toBeUndefined();

    const updated = toAccountSaveRequest(
      { ...toAccountEditModel(account, [2]), phone: '' },
      'update',
    );
    expect(updated.username).toBeUndefined();
    expect(updated.password).toBeUndefined();
    expect(updated.status).toBe('ENABLED');
    // 维护模式手机号留空 = 不换绑
    expect(updated.phone).toBeUndefined();
  });
});

describe('validateAccountModel', () => {
  it('新建：账号名 / 密码 / 手机号 / 角色都必填', () => {
    const errors = validateAccountModel(toAccountEditModel(null, []), 'create');
    expect(errors).toContain('账号名必填');
    expect(errors).toContain('初始密码必填');
    expect(errors.join(',')).toContain('手机号必填');
    expect(errors).toContain('至少选择一个平台角色');
  });

  it('新建：账号名与手机号格式', () => {
    const errors = validateAccountModel(
      {
        ...toAccountEditModel(null, []),
        displayName: '示例',
        password: 'Str0ng!Pass',
        phone: '12345',
        roleIds: [2],
        username: 'Bad Name',
      },
      'create',
    );
    expect(errors.join(',')).toContain('账号名须以小写字母开头');
    expect(errors.join(',')).toContain('手机号格式不正确');
  });

  it('维护：手机号可不填（不换绑），角色仍必填', () => {
    const errors = validateAccountModel(
      { ...toAccountEditModel(account, []), roleIds: [] },
      'update',
    );
    expect(errors).toContain('至少选择一个平台角色');
    expect(errors.join(',')).not.toContain('手机号必填');

    const ok = validateAccountModel(
      { ...toAccountEditModel(account, [2]) },
      'update',
    );
    expect(ok).toEqual([]);
  });
});

describe('展示', () => {
  it('手机号只回脱敏值；角色空显示「—」', () => {
    expect(phoneText(account)).toBe('139****1234');
    expect(phoneText({ ...account, phoneMasked: null })).toBe('—');
    expect(roleNamesText(account)).toBe('实施人员');
    expect(roleNamesText({ ...account, roleNames: [] })).toBe('—');
  });

  it('重置密码提示：临时密码只显示一次', () => {
    const lines = resetResultLines('Xk2!abcdEF3g');
    expect(lines[0]).toContain('Xk2!abcdEF3g');
    expect(lines[1]).toContain('只显示这一次');
  });
});
