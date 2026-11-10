import type {
  OrgCampusNode,
  TenantUserDetailView,
  WardOptionView,
} from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  deptTreeOptions,
  mainDeptText,
  responsibleScopeHint,
  toFormModel,
  validateAssignments,
  validateDisplayName,
  validateForm,
  validatePhone,
  validateReason,
  validateStaffNo,
  validateUsername,
  wardSelectOptions,
} from './user-logic';

function detail(
  overrides: Partial<TenantUserDetailView>,
): TenantUserDetailView {
  return {
    createdAt: '2026-11-01T10:00:00+08:00',
    deptScopeIds: [12],
    displayName: '示例随访护士',
    id: 11,
    mainDeptId: 101,
    mainDeptName: '虚构心内科',
    phoneMasked: '139****1001',
    roleIds: [31],
    roleNames: ['随访医生'],
    staffNo: 'IT-7001',
    status: 'ENABLED',
    username: 'nurse.it',
    wardIds: [201, 202],
    ...overrides,
  };
}

function ward(overrides: Partial<WardOptionView>): WardOptionView {
  return { code: 'W-DEMO-1', id: 201, name: '虚构病区一', ...overrides };
}

describe('e06-T07 用户表单校验（与后端 40001 口径一致）', () => {
  it('账号必填、长度与字符集（创建后不可改）', () => {
    expect(validateUsername(' nurse.demo ')).toBe(null);
    expect(validateUsername('  ')).toBe('账号必填');
    // 中文账号（示例）同样过不了字符集
    expect(validateUsername('张三（示例）')).toBe(
      '账号只允许字母、数字、下划线、短横线与点',
    );
    expect(validateUsername('a'.repeat(65))).toBe('账号过长（最多 64 个字符）');
  });

  it('姓名必填、工号与原因可空但有上限', () => {
    expect(validateDisplayName('  ')).toBe('姓名必填');
    expect(validateDisplayName('长'.repeat(65))).toBe(
      '姓名过长（最多 64 个字符）',
    );
    expect(validateStaffNo('IT-7001')).toBe(null);
    expect(validateStaffNo('a'.repeat(65))).toBe('工号过长（最多 64 个字符）');
    expect(validateReason('轮岗（示例）')).toBe(null);
    expect(validateReason('长'.repeat(201))).toBe(
      '变更原因过长（最多 200 个字符）',
    );
  });

  it('手机号：新建必填、编辑留空＝保持不变', () => {
    // 号码为虚构测试号段（139-0000 段，AGENTS.md §5.5）
    expect(validatePhone('13900001001', true)).toBe(null);
    expect(validatePhone('', true)).toBe('手机号必填');
    expect(validatePhone('', false)).toBe(null);
    expect(validatePhone('12345', false)).toBe(
      '手机号格式不正确（11 位，1 开头）',
    );
  });

  it('主科室必填、至少一个角色', () => {
    expect(validateAssignments(101, [31])).toBe(null);
    expect(validateAssignments(null, [31])).toBe('主科室必填');
    expect(validateAssignments(101, [])).toBe('至少分配一个角色');
  });

  it('表单校验汇总：创建时校验账号，编辑时跳过', () => {
    const base = toFormModel(detail({ username: 'ok.name' }));
    const createErrors = validateForm(
      { ...base, username: '张三（示例）', phone: '' },
      { creating: true },
    );
    expect(createErrors.username).not.toBe('');
    expect(createErrors.phone).toBe('手机号必填');
    const editErrors = validateForm(
      { ...base, username: '张三（示例）', phone: '' },
      { creating: false },
    );
    expect(editErrors.username).toBe('');
    expect(editErrors.phone).toBe('');
  });
});

describe('e06-T07 负责病区 / 负责科室（A-WARD-002、C-WARD-003）', () => {
  it('编辑回显：详情转表单模型，手机号不回填（留空＝保持不变）', () => {
    const model = toFormModel(detail({}));
    expect(model.wardIds).toEqual([201, 202]);
    expect(model.deptScopeIds).toEqual([12]);
    expect(model.mainDeptId).toBe(101);
    expect(model.roleIds).toEqual([31]);
    expect(model.phone).toBe('');
  });

  it('病区候选 label 附加编码，便于区分同名病区', () => {
    expect(
      wardSelectOptions([
        ward({}),
        ward({ code: 'W-IT-B', id: 202, name: '示例病区乙' }),
      ]),
    ).toEqual([
      { label: '虚构病区一（W-DEMO-1）', value: 201 },
      { label: '示例病区乙（W-IT-B）', value: 202 },
    ]);
  });

  it('组织树转 TreeSelect：院区为不可选分组节点，停用科室标注且默认隐藏', () => {
    const orgs: OrgCampusNode[] = [
      {
        code: 'ORG-DEMO',
        depts: [
          {
            children: [
              {
                children: [],
                code: 'D-2',
                deptType: null,
                hisCode: null,
                id: 102,
                leaderName: null,
                leaderUserId: null,
                name: '示例CCU',
                orgId: 1,
                parentId: 101,
                sortNo: 1,
                status: 'DISABLED',
              },
            ],
            code: 'D-1',
            deptType: null,
            hisCode: null,
            id: 101,
            leaderName: null,
            leaderUserId: null,
            name: '虚构心内科',
            orgId: 1,
            parentId: 0,
            sortNo: 1,
            status: 'ENABLED',
          },
        ],
        id: 1,
        name: '虚构院区',
        status: 'ENABLED',
      },
    ];
    const all = deptTreeOptions(orgs);
    expect(all[0]?.selectable).toBe(false);
    expect(all[0]?.children?.[0]?.title).toBe('虚构心内科');
    expect(all[0]?.children?.[0]?.children?.[0]?.title).toBe('示例CCU（停用）');
    const enabledOnly = deptTreeOptions(orgs, { onlyEnabled: true });
    expect(enabledOnly[0]?.children?.[0]?.children).toEqual([]);
  });

  it('负责范围提示带数量与立即生效说明', () => {
    expect(responsibleScopeHint(2, 1)).toBe(
      '已选负责病区 2 个、负责科室 1 个；保存后立即生效（在线会话的下一次请求按新范围计算）。',
    );
  });

  it('主科室缺省显示占位符', () => {
    expect(mainDeptText(detail({ mainDeptName: null }))).toBe('—');
  });
});
