import type { ImplantForm, ImplantOptions, ImplantRecordView } from './implant';

import { describe, expect, it } from 'vitest';

import {
  activeLeadsOf,
  buildExplantPayload,
  buildImplantPayload,
  buildImplantUpdatePayload,
  buildReplacePayload,
  emptyExplantForm,
  emptyImplantForm,
  emptyReplaceForm,
  generatorUnchanged,
  implantCardLines,
  implantEditReasonError,
  implantFormOf,
  leadPositionOptions,
  leadsAllowedForDevice,
  leadSummary,
  validateCRegistration,
  validateExplantForm,
  validateImplantForm,
  validateReplaceForm,
} from './implant';

/** 植入选项夹具（虚构示例；结构同 GET /implants/options，Q15 按腔室过滤）。 */
function options(): ImplantOptions {
  return {
    implantTypes: [{ code: 'FIRST', label: '首次植入' }],
    deviceTypes: [
      { code: 'DDD', label: '双腔起搏器(DDD)' },
      { code: 'LEADLESS', label: '无导线起搏器' },
    ],
    brands: [{ code: 'MEDTRONIC', label: '美敦力' }],
    pacingModes: [{ code: 'DDD', label: 'DDD' }],
    indications: [
      { code: 'SSS', label: '病态窦房结综合征' },
      { code: 'OTHER', label: '其他' },
    ],
    leadPositions: [
      {
        chamber: 'RA',
        positions: [
          { code: 'RA_APPENDAGE', label: '右心耳' },
          { code: 'RA_SEPTUM', label: '右房间隔' },
        ],
      },
      {
        chamber: 'RV',
        positions: [
          { code: 'RV_APEX', label: '右室心尖' },
          { code: 'HIS', label: '希氏束' },
        ],
      },
      { chamber: 'LV', positions: [{ code: 'CS', label: '冠状静脉窦' }] },
    ],
    leadStatuses: [{ code: 'ACTIVE', label: '在用' }],
  };
}

/** 虚构合法植入表单（DDD 双腔 + RA / RV 两根电极）。 */
function validForm(): ImplantForm {
  return {
    ...emptyImplantForm(),
    implantType: 'FIRST',
    implantDate: '2026-01-05',
    deviceType: 'DDD',
    indication: 'SSS',
    leads: [
      { chamber: 'RA', position: 'RA_APPENDAGE' },
      { chamber: 'RV', position: 'RV_APEX' },
    ],
  };
}

describe('e21-T01 植入记录与电极（C-PAT-004、C-PAT-005）', () => {
  it('无导线起搏器不录电极：判定与校验同口径', () => {
    expect(leadsAllowedForDevice('DDD')).toBe(true);
    expect(leadsAllowedForDevice('LEADLESS')).toBe(false);
    expect(leadsAllowedForDevice(null)).toBe(false);

    const leadless = { ...validForm(), deviceType: 'LEADLESS' };
    expect(validateImplantForm(leadless, options()).leads).toBe(
      '无导线起搏器不录电极（C-PAT-005）',
    );
  });

  it('电极部位按腔室过滤（Q15）：RA 不可选右室心尖', () => {
    expect(leadPositionOptions(options(), 'RA').map((i) => i.code)).toEqual([
      'RA_APPENDAGE',
      'RA_SEPTUM',
    ]);
    expect(leadPositionOptions(options(), 'LV')).toHaveLength(1);
    expect(leadPositionOptions(options(), undefined)).toHaveLength(0);

    const cross = {
      ...validForm(),
      leads: [{ chamber: 'RA', position: 'RV_APEX' }],
    };
    expect(validateImplantForm(cross, options())['leads.0.position']).toBe(
      '不属于RA的可选部位',
    );
  });

  it('必填与日期口径：类型 / 日期 / 器械必填，日期不能晚于今天', () => {
    const empty = { ...emptyImplantForm(), implantType: null };
    const errors = validateImplantForm(empty, options());
    expect(errors.implantType).toBe('必填');
    expect(errors.implantDate).toBe('必填');
    expect(errors.deviceType).toBe('必填');

    const future = {
      ...validForm(),
      implantDate: '2999-01-01',
    };
    expect(validateImplantForm(future, options()).implantDate).toBe(
      '不能晚于今天',
    );
  });

  it('植入指征选「其他」须填补充说明（Q16）', () => {
    const other = { ...validForm(), indication: 'OTHER', indicationNote: ' ' };
    expect(validateImplantForm(other, options()).indicationNote).toContain(
      '补充说明',
    );
  });

  it('合法表单无错误；电极植入日期缺省同植入记录日期', () => {
    expect(validateImplantForm(validForm(), options())).toEqual({});
    const payload = buildImplantPayload(validForm());
    expect(payload.leads?.[0]?.implantDate).toBe('2026-01-05');
    expect(payload.leads?.[0]?.status).toBe('ACTIVE');
  });

  it('编辑请求体携带 baseVersion 乐观锁与修改原因（C-PAT-011 / UAT-SELF-01）', () => {
    const payload = buildImplantUpdatePayload(
      3,
      validForm(),
      ' 型号抄录更正（示例） ',
    );
    expect(payload.baseVersion).toBe(3);
    expect(payload.implant.implantType).toBe('FIRST');
    expect(payload.reason).toBe('型号抄录更正（示例）');
  });

  it('编辑修改原因必填、≤255 字（UAT-SELF-01）', () => {
    expect(implantEditReasonError(null)).toContain('请填写修改原因');
    expect(implantEditReasonError('  ')).toContain('请填写修改原因');
    expect(implantEditReasonError('型号更正（示例）')).toBeNull();
    expect(implantEditReasonError('长'.repeat(256))).toContain('255');
    expect(implantEditReasonError('长'.repeat(255))).toBeNull();
  });

  it('既有行 → 表单初值（电极行带 id 复用，附录 A 电极 #5）', () => {
    const form = implantFormOf({
      id: '501',
      patientId: '101',
      implantNo: 1,
      implantType: 'FIRST',
      implantTypeLabel: '首次植入',
      implantDate: '2018-05-10',
      deviceType: 'DDD',
      deviceTypeLabel: '双腔起搏器(DDD)',
      status: 'REPLACED',
      statusLabel: '已更换',
      current: false,
      version: 2,
      leads: [
        {
          id: '601',
          chamber: 'RA',
          chamberLabel: '右心房（RA）',
          position: 'RA_APPENDAGE',
          positionLabel: '右心耳',
          status: 'ACTIVE',
          statusLabel: '在用',
          model: null,
          implantDate: '2018-05-10',
          version: 0,
        },
      ],
    });
    expect(form.implantType).toBe('FIRST');
    expect(form.leads?.[0]?.id).toBe('601');
    expect(form.leads?.[0]?.chamber).toBe('RA');
  });

  it('电极行显示文案：部位 · 型号', () => {
    expect(
      leadSummary({
        id: '1',
        chamber: 'RV',
        chamberLabel: '右心室（RV）',
        position: 'HIS',
        positionLabel: '希氏束',
        status: 'ACTIVE',
        statusLabel: '在用',
        model: '示例电极 L-01',
        implantDate: null,
        version: 0,
      }),
    ).toBe('希氏束 · 示例电极 L-01');
  });
});

describe('e21-T01 C 登记校验（C-PAT-001 最小必填，OQ-71①）', () => {
  it('患者字段与首条植入记录都必填，错误键带前缀分区', () => {
    const errors = validateCRegistration(
      {
        medicalRecordNo: '',
        name: '',
        gender: undefined,
        birthDate: null,
        deptId: null,
        wardId: undefined,
      },
      null,
      options(),
    );
    expect(errors['patient.medicalRecordNo']).toBe('必填');
    expect(errors['patient.birthDate']).toBe('必填');
    expect(errors['patient.deptId']).toBe('必选');
    expect(errors['patient.implant']).toContain('首条植入记录');
  });

  it('e21-T10：visitNo 可空 = 自动生成；超长报 patient.visitNo（C-PAT-003）', () => {
    const base = {
      medicalRecordNo: '105-2026-001',
      name: '张小明（示例）',
      gender: 'M',
      birthDate: '1955-01-01',
      deptId: 7,
      wardId: 3,
    };
    expect(
      validateCRegistration({ ...base, visitNo: null }, validForm(), options()),
    ).toEqual({});
    expect(
      validateCRegistration({ ...base, visitNo: '  ' }, validForm(), options()),
    ).toEqual({});
    expect(
      validateCRegistration(
        { ...base, visitNo: 'PAPER-2019-012' },
        validForm(),
        options(),
      ),
    ).toEqual({});
    expect(
      validateCRegistration(
        { ...base, visitNo: 'x'.repeat(65) },
        validForm(),
        options(),
      ),
    ).toEqual({ 'patient.visitNo': '随访编号过长（最多 64 个字符）' });
  });

  it('合法 C 登记无错误；植入错误带 implant. 前缀', () => {
    const ok = validateCRegistration(
      {
        medicalRecordNo: '105-2026-001',
        name: '张小明（示例）',
        gender: 'M',
        birthDate: '1955-01-01',
        deptId: 7,
        wardId: 3,
      },
      validForm(),
      options(),
    );
    expect(ok).toEqual({});

    const bad = validateCRegistration(
      {
        medicalRecordNo: '105-2026-001',
        name: '张小明（示例）',
        gender: 'M',
        birthDate: '1955-01-01',
        deptId: 7,
        wardId: 3,
      },
      { ...validForm(), deviceType: undefined },
      options(),
    );
    expect(bad['implant.deviceType']).toBe('必填');
  });
});

describe('e21-T01 手机卡片行（pages.md §4.10 植入记录表格转卡片）', () => {
  it('卡片行含器械 / 状态与电极行', () => {
    const lines = implantCardLines({
      id: '502',
      patientId: '101',
      implantNo: 2,
      implantType: 'REPLACE',
      implantTypeLabel: '更换（电池耗竭）',
      implantDate: '2026-03-02',
      deviceType: 'CRT_D',
      deviceTypeLabel: 'CRT-D',
      brandLabel: '美敦力（示例）',
      serialNo: 'DEMO-SN-0001',
      status: 'ACTIVE',
      statusLabel: '在用',
      current: true,
      version: 0,
      leads: [
        {
          id: '601',
          chamber: 'RV',
          chamberLabel: '右心室（RV）',
          position: 'RV_APEX',
          positionLabel: '右室心尖',
          status: 'ACTIVE',
          statusLabel: '在用',
          model: null,
          implantDate: '2018-05-10',
          version: 0,
        },
      ],
    });
    const keys = lines.map(([key]) => key);
    expect(keys).toContain('植入类型');
    expect(keys).toContain('状态');
    // E24-T10：电极行腔室用显示名（与桌面表格「腔室」列同口径），缺 label 回落编码
    expect(keys).toContain('电极 右心室（RV）');
    const rv = lines.find(([key]) => key === '电极 右心室（RV）')?.[1] ?? '';
    expect(rv).toContain('右室心尖');
    expect(rv).toContain('2018-05-10');
  });

  it('电极腔室缺显示名时回落编码（后端未下发 label 的兜底）', () => {
    const lines = implantCardLines({
      id: '503',
      patientId: '101',
      implantNo: 1,
      implantType: 'FIRST',
      implantTypeLabel: null,
      implantDate: '2026-01-05',
      deviceType: 'DDD',
      deviceTypeLabel: null,
      status: 'ACTIVE',
      statusLabel: null,
      current: true,
      version: 0,
      leads: [
        {
          id: '602',
          chamber: 'RA',
          chamberLabel: null,
          position: null,
          positionLabel: null,
          status: 'ACTIVE',
          statusLabel: null,
          model: null,
          implantDate: null,
          version: 0,
        },
      ],
    });
    expect(lines.some(([key]) => key === '电极 RA')).toBe(true);
  });
});

/** 目标（当前器械）夹具：DDD 2026-01-05 在用，RA 在用 + RV 已拔除电极。 */
function targetRecord() {
  return {
    id: '501',
    patientId: '101',
    implantNo: 1,
    implantType: 'FIRST',
    implantTypeLabel: '首次植入',
    implantDate: '2026-01-05',
    deviceType: 'DDD',
    deviceTypeLabel: '双腔起搏器(DDD)',
    brand: 'MEDTRONIC',
    brandLabel: '美敦力',
    model: '示例型号 S-01',
    serialNo: 'DEMO-SN-0001',
    pacingMode: 'DDD',
    pacingModeLabel: 'DDD',
    status: 'ACTIVE',
    statusLabel: '在用',
    endDate: null,
    endReason: null,
    current: true,
    version: 3,
    leads: [
      {
        id: '601',
        chamber: 'RA',
        chamberLabel: '右心房（RA）',
        position: 'RA_APPENDAGE',
        positionLabel: '右心耳',
        model: null,
        implantDate: '2026-01-05',
        status: 'ACTIVE',
        statusLabel: '在用',
        version: 0,
      },
      {
        id: '602',
        chamber: 'RV',
        chamberLabel: '右心室（RV）',
        position: 'RV_APEX',
        positionLabel: '右室心尖',
        model: null,
        implantDate: '2025-11-01',
        status: 'EXTRACTED',
        statusLabel: '已拔除',
        version: 1,
      },
    ],
  } satisfies ImplantRecordView;
}

describe('e21-T02 更换、升级与电极修正（C-PAT-006）', () => {
  it('表单初值：器械字段预填目标、在用电极缺省沿用（历史电极不进处置清单）', () => {
    const form = emptyReplaceForm(targetRecord(), 'REPLACE');
    expect(form.device.deviceType).toBe('DDD');
    expect(form.leadActions).toEqual([{ leadId: '601', action: 'KEEP' }]);
    expect(activeLeadsOf(targetRecord())).toHaveLength(1);

    const revision = emptyReplaceForm(targetRecord(), 'LEAD_REVISION');
    expect(revision.device.model).toBe('示例型号 S-01');
    expect(revision.device.serialNo).toBe('DEMO-SN-0001');
  });

  it('「其他」类型须填说明；日期不能早于原植入日期、不能晚于今天', () => {
    const form = emptyReplaceForm(targetRecord(), 'OTHER');
    form.date = '2026-03-10';
    expect(validateReplaceForm(form, options()).note).toContain('须填写说明');

    form.note = '电极脱位再次手术（示例）';
    expect(validateReplaceForm(form, options())).toEqual({});

    const early = emptyReplaceForm(targetRecord(), 'REPLACE');
    early.date = '2025-12-31';
    expect(validateReplaceForm(early, options()).date).toContain(
      '不能早于原植入日期',
    );

    const future = emptyReplaceForm(targetRecord(), 'REPLACE');
    future.date = '2999-01-01';
    expect(validateReplaceForm(future, options()).date).toBe('不能晚于今天');
  });

  it('电极修正锁定脉冲发生器：改类型被拦，同器械放行', () => {
    const form = emptyReplaceForm(targetRecord(), 'LEAD_REVISION');
    form.date = '2026-04-01';
    form.newLeads = [{ chamber: 'RV', position: 'HIS' }];
    expect(generatorUnchanged(form)).toBe(true);
    expect(validateReplaceForm(form, options())).toEqual({});

    form.device.deviceType = 'CRT_D';
    expect(generatorUnchanged(form)).toBe(false);
    expect(validateReplaceForm(form, options()).deviceType).toContain(
      '电极修正不改变脉冲发生器',
    );
  });

  it('在用电极逐根显式处置；无导线新器械不可携带电极', () => {
    const missing = emptyReplaceForm(targetRecord(), 'REPLACE');
    missing.date = '2026-03-10';
    missing.leadActions = [{ leadId: '601', action: null }];
    expect(validateReplaceForm(missing, options())['leadActions.0']).toBe(
      '请选择处置',
    );

    const leadless = emptyReplaceForm(targetRecord(), 'REPLACE');
    leadless.date = '2026-03-10';
    leadless.device.deviceType = 'LEADLESS';
    expect(validateReplaceForm(leadless, options()).leadActions).toContain(
      '无导线起搏器',
    );
  });

  it('组装更换请求体：baseVersion、说明归一与新电极日期缺省手术日期', () => {
    const form = emptyReplaceForm(targetRecord(), 'UPGRADE');
    form.date = '2026-03-10';
    form.device.deviceType = 'CRT_D';
    form.note = '  ';
    form.leadActions = [{ leadId: '601', action: 'KEEP' }];
    form.newLeads = [{ chamber: 'LV', position: 'CS' }];
    const payload = buildReplacePayload(form);
    expect(payload.baseVersion).toBe(3);
    expect(payload.type).toBe('UPGRADE');
    expect(payload.note).toBeNull();
    expect(payload.implant.implantDate).toBe('2026-03-10');
    expect(payload.leadActions).toEqual([{ leadId: '601', action: 'KEEP' }]);
    expect(payload.newLeads?.[0]?.implantDate).toBe('2026-03-10');
  });

  it('取出表单：在用电极须选废弃留置 / 已拔除，KEEP 不属于取出', () => {
    const form = emptyExplantForm(targetRecord());
    expect(form.leadActions).toEqual([{ leadId: '601', action: null }]);

    form.date = '2026-05-01';
    expect(validateExplantForm(form, targetRecord())['leadActions.0']).toBe(
      '请选择废弃留置或已拔除',
    );

    form.date = '2025-12-01';
    expect(validateExplantForm(form, targetRecord()).date).toContain(
      '不能早于植入日期',
    );

    form.date = '2026-05-01';
    form.leadActions = [{ leadId: '601', action: 'KEEP' }];
    expect(validateExplantForm(form, targetRecord())['leadActions.0']).toBe(
      '请选择废弃留置或已拔除',
    );

    form.leadActions = [{ leadId: '601', action: 'EXTRACT' }];
    const payload = buildExplantPayload(form, targetRecord());
    expect(payload.baseVersion).toBe(3);
    expect(payload.date).toBe('2026-05-01');
    expect(payload.leadActions).toEqual([{ leadId: '601', action: 'EXTRACT' }]);
  });
});
