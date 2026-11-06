import type { BaselineLockInfo } from './baseline';
import type { FormSchema } from './form';

// E13-T02 / A-PAT-006：基线分节与锁定解锁纯逻辑（锁定后只读、解锁需权限并留原因）
import { describe, expect, it } from 'vitest';

import {
  BASELINE_LOCKED_TOOLTIP,
  baselineDraftRecordKey,
  baselineLockBanner,
  baselineProgressText,
  baselineReasonError,
  baselineSectionStatusText,
  canEditBaseline,
  sectionDisplaySchema,
  showBaselineLock,
  showBaselineUnlock,
  sliceSchemaCategory,
} from './baseline';

describe('baselineSectionStatusText', () => {
  it('映射三态：未填写 / 部分填写 / 已填写完整', () => {
    expect(baselineSectionStatusText('EMPTY')).toBe('未填写');
    expect(baselineSectionStatusText('PARTIAL')).toBe('部分填写');
    expect(baselineSectionStatusText('DONE')).toBe('已填写完整');
  });
});

describe('baselineProgressText', () => {
  it('已填 / 应填（A-PAT-021 口径）', () => {
    expect(baselineProgressText(3, 5)).toBe('已填 3 / 应填 5');
    expect(baselineProgressText(5, 5)).toBe('已填 5 / 应填 5');
  });

  it('无必填项的分类不显示 0/0', () => {
    expect(baselineProgressText(0, 0)).toBe('无必填项');
  });
});

describe('baselineLockBanner（pages.md §5 文案照抄）', () => {
  it('未锁定不出现横幅', () => {
    expect(baselineLockBanner(null)).toBeNull();
    expect(baselineLockBanner(unlocked())).toBeNull();
  });

  it('锁定显示「基线已锁定（A-PAT-006）」并附锁定人与说明', () => {
    expect(baselineLockBanner(locked({}))).toBe('基线已锁定（A-PAT-006）');
    expect(
      baselineLockBanner(
        locked({ lockBy: '张医生（示例）', reason: '出院 30 天内确认无误' }),
      ),
    ).toBe(
      '基线已锁定（A-PAT-006）（锁定人 张医生（示例），说明：出院 30 天内确认无误）',
    );
  });
});

describe('canEditBaseline（锁定后只读）', () => {
  it('未锁定且有编辑权限才可进编辑', () => {
    expect(canEditBaseline(false, true)).toBe(true);
    expect(canEditBaseline(false, false)).toBe(false);
    expect(canEditBaseline(true, true)).toBe(false);
    expect(canEditBaseline(true, false)).toBe(false);
  });

  it('置灰提示文案照抄 pages.md §4.2', () => {
    expect(BASELINE_LOCKED_TOOLTIP).toBe(
      '基线已锁定：有解锁权限并填写原因后才进入编辑',
    );
  });
});

describe('解锁 / 锁定入口（需专门权限 followup:patient:lock）', () => {
  it('已锁定且有锁定权限才显示「解锁」', () => {
    expect(showBaselineUnlock(true, true)).toBe(true);
    expect(showBaselineUnlock(true, false)).toBe(false);
    expect(showBaselineUnlock(false, true)).toBe(false);
  });

  it('未锁定且有锁定权限才显示「锁定」', () => {
    expect(showBaselineLock(false, true)).toBe(true);
    expect(showBaselineLock(true, true)).toBe(false);
    expect(showBaselineLock(false, false)).toBe(false);
  });
});

describe('baselineReasonError（解锁原因必填）', () => {
  it('空白与超长报错（上限 255 字与后端一致）', () => {
    expect(baselineReasonError('')).toBe('请填写原因');
    expect(baselineReasonError('   ')).toBe('请填写原因');
    expect(baselineReasonError('抄录错误')).toBeNull();
    expect(baselineReasonError('长'.repeat(256))).toBe('原因不超过 255 字');
    expect(baselineReasonError('长'.repeat(255))).toBeNull();
  });
});

describe('baselineDraftRecordKey', () => {
  it('kind=baseline 且键含患者 + 分类（与随访记录草稿互不冲突）', () => {
    expect(baselineDraftRecordKey('demoa', 9, '101', 'case')).toBe(
      'demoa:9:baseline:101:case',
    );
    expect(baselineDraftRecordKey(null, null, '101', 'echo')).toBe(
      '::baseline:101:echo',
    );
  });
});

describe('sliceSchemaCategory（按分类单独渲染 / 保存）', () => {
  const schema: FormSchema = schemaOf('case', 'lab');

  it('只保留指定分类，其余字段不动', () => {
    const sliced = sliceSchemaCategory(schema, 'lab');
    expect(sliced?.categories).toHaveLength(1);
    expect(sliced?.categories[0]?.key).toBe('lab');
    expect(sliced?.code).toBe('A_BASELINE');
    expect(sliced?.rules).toEqual(schema.rules);
  });

  it('分类不存在返回 null', () => {
    expect(sliceSchemaCategory(schema, 'missing')).toBeNull();
  });
});

// ---------------------------------------------------------------- 测试数据（虚构）

function locked(options: {
  lockBy?: null | string;
  reason?: null | string;
}): BaselineLockInfo {
  return {
    locked: true,
    lockReason: options.reason ?? null,
    lockedAt: '2026-11-06T10:00:00+08:00',
    lockedBy: options.lockBy ?? null,
    unlockReason: null,
    unlockedAt: null,
    unlockedBy: null,
  };
}

function unlocked(): BaselineLockInfo {
  return {
    locked: false,
    lockReason: null,
    lockedAt: null,
    lockedBy: null,
    unlockReason: null,
    unlockedAt: null,
    unlockedBy: null,
  };
}

function schemaOf(...keys: string[]): FormSchema {
  return {
    code: 'A_BASELINE',
    name: '基线档案',
    usage: 'BASELINE',
    version: 3,
    categories: keys.map((key, index) => ({
      key,
      label: `分类${index}`,
      order: index,
      fields: [],
    })),
    rules: [],
  } as unknown as FormSchema;
}

describe('sectionDisplaySchema（基线页签只读展示：只渲染 JSON 字段）', () => {
  it('过滤实体列 / 加密列字段（值来自患者主档，不在分节 JSON 重复显示）', () => {
    const schema = {
      code: 'A_BASELINE',
      name: '基线档案',
      usage: 'BASELINE',
      version: 3,
      categories: [
        {
          key: 'basic',
          label: '基本信息',
          order: 0,
          fields: [
            {
              key: 'f002',
              label: '病案号',
              code: 'mrn',
              required: true,
              sensitive: true,
              storage: 'encrypted',
              type: 'text',
            },
            {
              key: 'f007',
              label: '诊断信息',
              code: 'diagnosis',
              required: false,
              sensitive: false,
              storage: 'json',
              type: 'textarea',
            },
          ],
        },
      ],
      rules: [],
    } as unknown as FormSchema;

    const display = sectionDisplaySchema(schema, 'basic');
    expect(display?.categories[0]?.fields).toHaveLength(1);
    expect(display?.categories[0]?.fields[0]?.key).toBe('f007');
  });

  it('分类没有 JSON 字段时返回 null（页签跳过该块，主档块已覆盖）', () => {
    const schema = {
      code: 'A_BASELINE',
      name: '基线档案',
      usage: 'BASELINE',
      version: 3,
      categories: [
        {
          key: 'basic',
          label: '基本信息',
          order: 0,
          fields: [],
        },
      ],
      rules: [],
    } as unknown as FormSchema;

    expect(sectionDisplaySchema(schema, 'basic')).toBeNull();
  });
});
