import type {
  PatientDuplicateCheckResult,
  PatientRegistrationForm,
} from './patient-registration';

import { describe, expect, it } from 'vitest';

import {
  buildDuplicateCheckPayload,
  buildRegistrationPayload,
  canCheckDuplicate,
  dischargeStatusText,
  duplicateNoticeOf,
  validateRegistration,
  visitNoError,
  visitNoHelpText,
} from './patient-registration';

/** 虚构合法表单（A 出院登记最小必填集，design-system §17 示例口径）。 */
function validForm(): PatientRegistrationForm {
  return {
    medicalRecordNo: 'DEMO-A-0001',
    name: '张小明（示例）',
    gender: 'M',
    birthDate: '1955-01-01',
    wardId: 3,
    surgeryDate: '2026-03-01',
    admissionDate: '2026-02-25',
    dischargeDate: '2026-03-08',
    dischargeStatus: 1,
    legacyNo: ' ',
  };
}

describe('e13-T01 病案号查重（A-PAT-007、C-PAT-002）', () => {
  it('查重最小条件：去空白后非空且 ≤64 位', () => {
    expect(canCheckDuplicate('  MRN-1  ')).toBe(true);
    expect(canCheckDuplicate('   ')).toBe(false);
    expect(canCheckDuplicate(null)).toBe(false);
    expect(canCheckDuplicate('x'.repeat(65))).toBe(false);
  });

  it('查重请求体：病案号去空白；姓名 + 出生日期成对才携带', () => {
    expect(buildDuplicateCheckPayload({ medicalRecordNo: ' MRN-1 ' })).toEqual({
      medicalRecordNo: 'MRN-1',
    });
    expect(
      buildDuplicateCheckPayload({
        medicalRecordNo: 'MRN-1',
        name: '张小明（示例）',
        birthDate: '1955-01-01',
      }),
    ).toEqual({
      medicalRecordNo: 'MRN-1',
      name: '张小明（示例）',
      birthDate: '1955-01-01',
    });
    expect(
      buildDuplicateCheckPayload({
        medicalRecordNo: 'MRN-1',
        name: '张小明（示例）',
      }),
    ).toEqual({ medicalRecordNo: 'MRN-1' });
    expect(buildDuplicateCheckPayload({ medicalRecordNo: '  ' })).toBeNull();
  });

  it('病案号重复：阻止提示带范围内摘要（可跳转），范围外只报存在', () => {
    const inScope: PatientDuplicateCheckResult = {
      exists: true,
      patient: { id: '11', nameMasked: '李*）', wardName: '示例二病区' },
    };
    const blocked = duplicateNoticeOf(inScope);
    expect(blocked?.kind).toBe('blocked');
    expect(blocked?.patient?.id).toBe('11');

    const outOfScope: PatientDuplicateCheckResult = { exists: true };
    const hidden = duplicateNoticeOf(outOfScope);
    expect(hidden?.kind).toBe('blocked');
    expect(hidden?.patient).toBeNull();
    expect(hidden?.text).toContain('已在本院登记');
  });

  it('无重复且无同名同生日：不提示', () => {
    expect(duplicateNoticeOf({ exists: false })).toBeNull();
    expect(duplicateNoticeOf(null)).toBeNull();
  });

  it('同名同生日：可能重复提示（C-PAT-002，不阻止）', () => {
    const notice = duplicateNoticeOf({
      exists: false,
      possibleMatches: [
        { id: '21', nameMasked: '孙*）', wardName: '示例一病区' },
        { id: '22', nameMasked: '孙*）', wardName: '示例二病区' },
      ],
    });
    expect(notice).toMatchObject({
      kind: 'possible',
      matches: [
        { id: '21', nameMasked: '孙*）', wardName: '示例一病区' },
        { id: '22', nameMasked: '孙*）', wardName: '示例二病区' },
      ],
    });
    const text = notice?.kind === 'possible' ? notice.text : '';
    expect(text).toContain('2 名');
  });
});

describe('e13-T01 出院登记（A-PAT-003）', () => {
  it('合法表单无错误', () => {
    expect(validateRegistration(validForm())).toEqual({});
  });

  it('最小必填集逐项校验（文案与后端 40001 一致）', () => {
    const form = validForm();
    form.medicalRecordNo = ' ';
    form.name = '';
    form.gender = undefined;
    form.wardId = undefined;
    form.surgeryDate = undefined;
    form.admissionDate = undefined;
    form.dischargeDate = undefined;
    form.dischargeStatus = undefined;

    expect(validateRegistration(form)).toEqual({
      medicalRecordNo: '请填写病案号',
      name: '请填写姓名',
      gender: '请选择性别',
      wardId: '请选择病区',
      surgeryDate: '请填写手术日期',
      admissionDate: '请填写入院日期',
      dischargeDate: '请填写出院日期',
      dischargeStatus: '请选择出院状态',
    });
  });

  it('日期先后：入院 ≤ 手术 ≤ 出院', () => {
    const early = validForm();
    early.surgeryDate = '2026-02-24';
    expect(validateRegistration(early).surgeryDate).toBe(
      '手术日期不能早于入院日期',
    );

    const back = validForm();
    back.dischargeDate = '2026-02-28';
    expect(validateRegistration(back).dischargeDate).toBe(
      '出院日期不能早于手术日期',
    );
  });

  it('请求体归一：去空白、出院状态转数值、空串归一', () => {
    const payload = buildRegistrationPayload(validForm());
    expect(payload.medicalRecordNo).toBe('DEMO-A-0001');
    expect(payload.name).toBe('张小明（示例）');
    expect(payload.dischargeStatus).toBe(1);
    expect(payload.legacyNo).toBeNull();

    const selectValue = buildRegistrationPayload({
      ...validForm(),
      dischargeStatus: '2',
      deptId: undefined,
    });
    expect(selectValue.dischargeStatus).toBe(2);
    expect(selectValue.deptId).toBeNull();
  });

  it('出院状态文案：1=存活 / 2=病重自动出院 / 3=死亡', () => {
    expect(dischargeStatusText(1)).toBe('存活');
    expect(dischargeStatusText('2')).toBe('病重自动出院');
    expect(dischargeStatusText(3)).toBe('死亡');
    expect(dischargeStatusText(undefined)).toBe('');
    expect(dischargeStatusText(9)).toBe('9');
  });
});

describe('e21-T10 随访编号（C-PAT-003：留空自动生成，历史患者可填纸质原编号）', () => {
  it('请求体：留空归一 null，填写去空白', () => {
    const auto = buildRegistrationPayload({
      ...validForm(),
      visitNo: '   ',
    });
    expect(auto.visitNo).toBeNull();

    const manual = buildRegistrationPayload({
      ...validForm(),
      visitNo: ' PAPER-2019-012 ',
    });
    expect(manual.visitNo).toBe('PAPER-2019-012');
  });

  it('手填校验与后端 40001 同口径：空白视为留空，超长报错', () => {
    expect(visitNoError(null)).toBeNull();
    expect(visitNoError('  ')).toBeNull();
    expect(visitNoError('PAPER-2019-012')).toBeNull();
    expect(visitNoError('x'.repeat(65))).toBe('随访编号过长（最多 64 个字符）');
  });

  it('帮助文案展示本院生效规则与示例；无规则（A / B）退化为字段名', () => {
    expect(visitNoHelpText({ prefix: '105', sample: '105-2026-001' })).toBe(
      '留空按「105-建档年份-年内流水号」自动生成（如 105-2026-001）；历史患者可填纸质原编号，本院唯一',
    );
    expect(visitNoHelpText(null)).toBe('随访编号');
  });
});
