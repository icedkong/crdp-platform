import { describe, expect, it } from 'vitest';

import {
  TENANT_ROLE_SCOPE_OPTIONS,
  tenantFieldPolicyText,
  tenantRoleScopeTypeText,
} from './tenant-role';

describe('e07-T06 数据范围选项（PLT-PERM-007 + 研究项目扩展类型）', () => {
  it('包含七种平台口径与研究项目扩展类型（E07-T06 配置页放开勾选）', () => {
    expect(TENANT_ROLE_SCOPE_OPTIONS.map((option) => option.type)).toEqual([
      'ALL',
      'WARD',
      'RESPONSIBLE_DEPT',
      'RESPONSIBLE_PATIENT',
      'DEPT',
      'SELF',
      'CUSTOM_DEPT',
      'RESEARCH_PROJECT',
    ]);
  });

  it('只有自定义科室需要勾选科室清单', () => {
    expect(
      TENANT_ROLE_SCOPE_OPTIONS.filter((option) => option.requiresDeptIds).map(
        (option) => option.type,
      ),
    ).toEqual(['CUSTOM_DEPT']);
  });

  it('研究项目类型有面向用户的文案', () => {
    expect(tenantRoleScopeTypeText('RESEARCH_PROJECT')).toBe(
      '研究项目（本人为成员）',
    );
  });
});

describe('e07-T06 字段策略文案（PLT-PERM-009）', () => {
  it('三种策略与未覆盖态的文案', () => {
    expect(tenantFieldPolicyText('VISIBLE')).toBe('可见');
    expect(tenantFieldPolicyText('MASKED')).toBe('脱敏');
    expect(tenantFieldPolicyText('HIDDEN')).toBe('隐藏');
  });
});
