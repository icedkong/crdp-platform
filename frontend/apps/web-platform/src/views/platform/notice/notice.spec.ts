import type { PlatformAnnouncementView, TenantListItem } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  announcementTimeText,
  emptyFormModel,
  tenantNamesText,
  tenantOptions,
  validateFormModel,
} from './notice';

/** 演示租户行（字段取自 TenantListItem 的最小子集）。 */
function tenant(id: number, code: string, name: string): TenantListItem {
  return {
    code,
    createdAt: '2026-11-01T09:00:00+08:00',
    expireAt: null,
    expireType: 'LONG_TERM',
    id,
    modules: [],
    name,
    shortName: null,
    status: 'ENABLED',
    userCount: 0,
    viewEnterable: false,
  };
}

function announcement(
  scope: 'ALL' | 'TENANTS',
  tenants: Array<{ tenantName: string }>,
): PlatformAnnouncementView {
  return {
    content: '正文（示例）',
    id: 1,
    publishedAt: '2027-07-26T10:00:00+08:00',
    publishedByName: '王运维（示例）（ops.demo）',
    revokedAt: null,
    scope,
    scopeText: scope === 'ALL' ? '全部租户' : '指定租户',
    status: 'PUBLISHED',
    tenants: tenants.map((item, index) => ({
      tenantCode: `t${index}`,
      tenantId: index + 1,
      tenantName: item.tenantName,
    })),
    title: '升级公告',
  };
}

describe('e05-t11 平台端系统公告逻辑', () => {
  it('标题与正文必填、长度上限（与后端同口径 128 / 5000）', () => {
    const model = emptyFormModel();
    model.title = ' ';
    model.content = '正文';
    expect(validateFormModel(model)).toContain('标题不能为空');

    model.title = '标'.repeat(129);
    expect(validateFormModel(model)).toContain('标题不能超过 128 个字符');

    model.title = '升级公告';
    model.content = ' ';
    expect(validateFormModel(model)).toContain('正文不能为空');

    model.content = '字'.repeat(5001);
    expect(validateFormModel(model)).toContain('正文不能超过 5000 个字符');

    model.content = '平台将于周六升级维护';
    expect(validateFormModel(model)).toEqual([]);
  });

  it('指定租户至少 1 个、最多 50 个（验收：可向全部或指定租户发布）', () => {
    const model = emptyFormModel();
    model.title = '定向公告';
    model.content = '仅 A 可见';
    model.scope = 'TENANTS';
    model.tenantIds = [];
    expect(validateFormModel(model)).toContain('指定租户不能为空');

    model.tenantIds = Array.from({ length: 51 }, (_, i) => i + 1);
    expect(validateFormModel(model)).toContain('指定租户一次最多 50 个');

    model.tenantIds = [1];
    expect(validateFormModel(model)).toEqual([]);

    model.scope = 'ALL';
    expect(validateFormModel(model)).toEqual([]);
  });

  it('租户下拉选项 label 为「医院名称（编码）」', () => {
    const options = tenantOptions([
      tenant(1, 'demoa', '演示医院 A（示例）'),
      tenant(2, 'demob', '演示医院 B（示例）'),
    ]);
    expect(options).toEqual([
      { label: '演示医院 A（示例）（demoa）', value: 1 },
      { label: '演示医院 B（示例）（demob）', value: 2 },
    ]);
  });

  it('发布范围列文案：全部租户直出，指定租户超 3 家折叠为「等 n 家」', () => {
    expect(tenantNamesText(announcement('ALL', []))).toBe('全部租户');
    expect(
      tenantNamesText(
        announcement('TENANTS', [
          { tenantName: '演示医院 A（示例）' },
          { tenantName: '演示医院 B（示例）' },
        ]),
      ),
    ).toBe('演示医院 A（示例）、演示医院 B（示例）');
    expect(
      tenantNamesText(
        announcement('TENANTS', [
          { tenantName: '演示医院 A（示例）' },
          { tenantName: '演示医院 B（示例）' },
          { tenantName: '演示医院 C（示例）' },
          { tenantName: '演示医院 D（示例）' },
        ]),
      ),
    ).toBe(
      '演示医院 A（示例）、演示医院 B（示例）、演示医院 C（示例） 等 4 家',
    );
  });

  it('时间展示：ISO-8601 带时区转本地 YYYY-MM-DD HH:mm，空值兜底（与运行时区无关，同 notice-bell-logic 口径）', () => {
    expect(announcementTimeText(null)).toBe('—');
    // 本地时区构造 → ISO 输入 → 断言与实现同一本地口径（CI 在 UTC 也能过）
    const time = new Date(2027, 6, 26, 10, 30);
    const pad = (value: number) => String(value).padStart(2, '0');
    expect(announcementTimeText(time.toISOString())).toBe(
      `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(
        time.getDate(),
      )} ${pad(time.getHours())}:${pad(time.getMinutes())}`,
    );
    expect(announcementTimeText('not-a-date')).toBe('not-a-date');
  });
});
