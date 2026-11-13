import type { PatientTransferPayload, WardFormState, WardView } from './ward';

import { describe, expect, it } from 'vitest';

import {
  buildTransferPayload,
  buildWardPayload,
  emptyTransferForm,
  emptyWardForm,
  parseAliasInput,
  transferDeptTargetOptions,
  transferSuccessText,
  transferTargetOptions,
  validateTransferForm,
  validateWardForm,
  wardDisableBlockedText,
  wardFormOf,
  wardTypeText,
} from './ward';

/** 虚构病区行（design-system §17 示例口径）。 */
function wardRow(): WardView {
  return {
    id: '7',
    code: 'W-DEMO-2',
    name: '示例二病区',
    wardType: 'WARD',
    aliases: ['心外二', '心外 2'],
    defaultOwnerId: '9',
    defaultOwnerName: '王医生（示例）',
    sortNo: 20,
    status: 'ENABLED',
    patientCount: 3,
    memberCount: 2,
  };
}

describe('e13-T08 病区字典表单（A-WARD-001）', () => {
  it('编码必填、字符集与长度；编辑态编码锁定不校验字符集', () => {
    const form = emptyWardForm();
    expect(validateWardForm(form, { codeLocked: false }).code).toBe(
      '请填写病区编码',
    );
    form.code = 'w two';
    expect(validateWardForm(form, { codeLocked: false }).code).toContain(
      '只支持',
    );
    expect(validateWardForm(form, { codeLocked: true }).code).toBeUndefined();
    form.code = 'W-2';
    form.name = '示例二病区';
    form.wardType = 'WARD';
    expect(validateWardForm(form, { codeLocked: false })).toEqual({});
  });

  it('别名单项 / 总长上限；解析去空白、兼容全角分号', () => {
    expect(parseAliasInput(' 心外二 ； 心外2 ;')).toEqual(['心外二', '心外2']);
    const long: WardFormState = {
      ...emptyWardForm(),
      code: 'W-2',
      name: '示例二病区',
      wardType: 'CENTER',
      aliases: `a;${'长'.repeat(65)}`,
    };
    expect(validateWardForm(long, { codeLocked: false }).aliases).toContain(
      '单项',
    );
    const total: WardFormState = {
      ...long,
      aliases: Array.from({ length: 10 }, () => '别'.repeat(30)).join(';'),
    };
    expect(validateWardForm(total, { codeLocked: false }).aliases).toContain(
      '总长',
    );
  });

  it('表单与请求体互转：回显拼分号、提交拆清单、负责人转数值', () => {
    const form = wardFormOf(wardRow());
    expect(form.aliases).toBe('心外二;心外 2');
    expect(form.defaultOwnerId).toBe(9);
    const payload = buildWardPayload(form);
    expect(payload.aliases).toEqual(['心外二', '心外 2']);
    expect(payload.defaultOwnerId).toBe(9);
    expect(payload.wardType).toBe('WARD');
  });

  it('类型与停用拦截文案', () => {
    expect(wardTypeText('CENTER')).toBe('中心');
    expect(wardTypeText(undefined)).toBe('');
    expect(wardDisableBlockedText(wardRow())).toContain('3 名在册患者');
  });
});

describe('e13-T08 转病区表单（A-WARD-005）', () => {
  it('目标病区与原因必填；原因上限 255', () => {
    const errors = validateTransferForm(
      { ...emptyTransferForm(), reason: '' },
      { deptEnabled: false },
    );
    expect(errors.wardId).toBe('请选择目标病区');
    expect(errors.reason).toBe('请填写转病区 / 转科室原因');
    expect(
      validateTransferForm(
        { ...emptyTransferForm(), wardId: 4, reason: '长'.repeat(256) },
        { deptEnabled: false },
      ).reason,
    ).toContain('255');
    expect(
      validateTransferForm(
        { ...emptyTransferForm(), wardId: 4, reason: ' 术后转入（示例） ' },
        { deptEnabled: false },
      ),
    ).toEqual({});
  });

  it('请求体组装：非法输入返回 null（不发起请求）', () => {
    const payload: null | PatientTransferPayload = buildTransferPayload(
      { ...emptyTransferForm(), wardId: 4, reason: ' 术后转入（示例） ' },
      { deptEnabled: false },
    );
    expect(payload).toEqual({ wardId: 4, reason: '术后转入（示例）' });
    expect(
      buildTransferPayload(
        { ...emptyTransferForm(), reason: 'x' },
        { deptEnabled: false },
      ),
    ).toBeNull();
    expect(
      buildTransferPayload(
        { ...emptyTransferForm(), wardId: 4, reason: '  ' },
        { deptEnabled: false },
      ),
    ).toBeNull();
  });

  it('目标候选排除当前病区（已在的病区无需转入）', () => {
    const wards = [
      { id: 1, name: '示例一病区' },
      { id: 2, name: '示例二病区' },
    ];
    expect(transferTargetOptions(wards, 1)).toEqual([
      { label: '示例二病区', value: 2 },
    ]);
    expect(transferTargetOptions(wards, undefined)).toHaveLength(2);
  });

  it('a / B 口径（deptEnabled=false）：科室维度不参与校验与请求体，仍要求病区', () => {
    const form = { ...emptyTransferForm(), deptId: 8, reason: '原因（示例）' };
    expect(validateTransferForm(form, { deptEnabled: false }).wardId).toBe(
      '请选择目标病区',
    );
    // A 口径忽略误填的科室维度，只提交病区
    const payload = buildTransferPayload(
      { ...form, wardId: 4 },
      { deptEnabled: false },
    );
    expect(payload).toEqual({ wardId: 4, reason: '原因（示例）' });
  });
});

describe('e21-T21 转科室 / 转病区表单（C-WARD-006）', () => {
  it('病区与科室至少一项；原因口径与上限 255', () => {
    expect(validateTransferForm(emptyTransferForm()).wardId).toBe(
      '请选择目标病区或科室',
    );
    expect(
      validateTransferForm({
        ...emptyTransferForm(),
        wardId: 4,
        reason: '术后转入（示例）',
      }),
    ).toEqual({});
    expect(
      validateTransferForm({
        ...emptyTransferForm(),
        deptId: 7,
        reason: '转科（示例）',
      }),
    ).toEqual({});
    expect(
      validateTransferForm({
        ...emptyTransferForm(),
        wardId: 4,
        deptId: 7,
        reason: '长'.repeat(256),
      }).reason,
    ).toContain('255');
  });

  it('请求体组装：只带科室 / 只带病区 / 两者同时；缺目标返回 null', () => {
    expect(
      buildTransferPayload({
        ...emptyTransferForm(),
        deptId: 7,
        reason: '转科（示例）',
      }),
    ).toEqual({ deptId: 7, reason: '转科（示例）' });
    expect(
      buildTransferPayload({
        ...emptyTransferForm(),
        wardId: 4,
        deptId: 7,
        reason: '同时转（示例）',
      }),
    ).toEqual({ wardId: 4, deptId: 7, reason: '同时转（示例）' });
    expect(
      buildTransferPayload({ ...emptyTransferForm(), reason: '缺目标' }),
    ).toBeNull();
  });

  it('科室目标候选排除当前科室；成功提示按变化维度拼装', () => {
    const depts = [
      { id: 7, name: '示例心内科' },
      { id: 8, name: '示例心外科' },
    ];
    expect(transferDeptTargetOptions(depts, 7)).toEqual([
      { label: '示例心外科', value: 8 },
    ]);
    const result = {
      patientId: '101',
      wardId: '4',
      wardName: '示例二病区',
      deptId: '8',
      deptName: '示例心外科',
    };
    expect(transferSuccessText(result, { dept: true })).toContain(
      '已转入科室「示例心外科」',
    );
    expect(transferSuccessText(result, { ward: true, dept: true })).toContain(
      '已转入病区「示例二病区」',
    );
  });
});
