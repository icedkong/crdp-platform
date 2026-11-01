import { describe, expect, it } from 'vitest';

import {
  baseModuleText,
  canTakeOffShelf,
  dependsOnText,
  menuSideText,
  menuTypeText,
  shelfStatusText,
  syncedAtText,
  syncOutcomeLine,
  toEditModel,
  toUpdateRequest,
  validateEditModel,
  versionText,
} from './catalog';

describe('e05-T04 模块目录逻辑', () => {
  it('上架状态文案：ON 上架、OFF 下架，未知值原样返回（PLT-MOD-001）', () => {
    expect(shelfStatusText('ON')).toBe('上架');
    expect(shelfStatusText('OFF')).toBe('下架');
    expect(shelfStatusText('GONE')).toBe('GONE');
  });

  it('基础模块列与下架可用性：基础模块必选不能下架（平台规格 §3）', () => {
    expect(baseModuleText(true)).toBe('是');
    expect(baseModuleText(false)).toBe('否');
    expect(canTakeOffShelf({ baseModule: true })).toBe(false);
    expect(canTakeOffShelf({ baseModule: false })).toBe(true);
  });

  it('依赖与版本列：空显示「—」，版本为 功能 / 迁移（PLT-MOD-003）', () => {
    expect(dependsOnText([])).toBe('—');
    expect(dependsOnText(['base'])).toBe('base');
    expect(versionText({ featureVersion: '', migrationVersion: '' })).toBe('—');
    expect(
      versionText({ featureVersion: '0.1.0', migrationVersion: '2' }),
    ).toBe('0.1.0 / 2');
  });

  it('同步时间列：未同步显示「—」，已同步精确到分钟', () => {
    expect(syncedAtText({ syncedAt: null })).toBe('—');
    expect(syncedAtText({ syncedAt: '2026-11-01T09:05:30+08:00' })).toBe(
      '2026-11-01 09:05',
    );
  });

  it('菜单清单的端别与类型文案，未知值原样返回', () => {
    expect(menuSideText('HOSPITAL')).toBe('医院端');
    expect(menuSideText('PLATFORM')).toBe('平台端');
    expect(menuTypeText('DIR')).toBe('目录');
    expect(menuTypeText('MENU')).toBe('菜单');
    expect(menuTypeText('BUTTON')).toBe('按钮');
    expect(menuTypeText('X')).toBe('X');
  });

  it('目录行转维护表单模型（PLT-MOD-002：只编辑名称、说明、上架状态）', () => {
    expect(
      toEditModel({
        baseModule: false,
        code: 'followup',
        configItems: [],
        dependsOn: ['base'],
        description: '说明（示例）',
        featureVersion: '0.1.0',
        menus: [],
        migrationVersion: '2',
        name: '随访',
        permissions: [],
        status: 'ON',
        syncedAt: null,
        versions: [],
      }),
    ).toEqual({ description: '说明（示例）', name: '随访', status: 'ON' });
  });

  it('维护表单校验：名称必填 ≤64、说明 ≤255（PLT-MOD-002）', () => {
    expect(
      validateEditModel({ description: '', name: '随访', status: 'ON' }),
    ).toEqual([]);
    expect(
      validateEditModel({ description: '', name: '  ', status: 'ON' }),
    ).toContain('模块名称必填');
    expect(
      validateEditModel({
        description: '',
        name: 'x'.repeat(65),
        status: 'ON',
      }),
    ).toContain('模块名称过长（最多 64 个字符）');
    expect(
      validateEditModel({
        description: 'y'.repeat(256),
        name: '随访',
        status: 'ON',
      }),
    ).toContain('模块说明过长（最多 255 个字符）');
  });

  it('维护表单转提交请求：只带名称、说明与收窄后的上架状态（PLT-MOD-002）', () => {
    expect(
      toUpdateRequest({
        description: '说明（示例）',
        name: '随访',
        status: 'ON',
      }),
    ).toEqual({ description: '说明（示例）', name: '随访', status: 'ON' });
    expect(
      toUpdateRequest({ description: '', name: '随访', status: 'OFF' }),
    ).toEqual({ description: '', name: '随访', status: 'OFF' });
    // 单选按钮之外的值按上架兜底，不会把非法状态发给后端
    expect(
      toUpdateRequest({ description: '', name: '随访', status: 'GONE' }),
    ).toEqual({ description: '', name: '随访', status: 'ON' });
  });

  it('同步结果提示：模块 + 动作 + 版本（PLT-MOD-002）', () => {
    expect(
      syncOutcomeLine({
        action: 'UPDATED',
        code: 'followup',
        featureVersion: '0.1.0',
        migrationVersion: '2',
      }),
    ).toBe('followup：已更新（功能 0.1.0 / 迁移 2）');
    expect(
      syncOutcomeLine({
        action: 'REGISTERED',
        code: 'followup',
        featureVersion: '',
        migrationVersion: '',
      }),
    ).toBe('followup：新注册（功能 — / 迁移 —）');
  });
});
