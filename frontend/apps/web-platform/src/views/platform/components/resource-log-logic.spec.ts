import { describe, expect, it } from 'vitest';

import {
  actionColor,
  emptyFilters,
  operatorText,
  timeText,
  toQuery,
  typeColor,
} from './resource-log-logic';

describe('e05-T10 资源变更记录逻辑', () => {
  it('筛选模型 → 查询参数：空筛选取空、关键词去首尾空白、带分页（PLT-PERM-012）', () => {
    expect(toQuery(emptyFilters('MENU'), 1)).toEqual({
      action: undefined,
      channel: undefined,
      keyword: undefined,
      pageNo: 1,
      pageSize: 20,
      resourceType: 'MENU',
    });
    expect(
      toQuery(
        {
          action: 'DELETE',
          channel: 'SYNC',
          keyword: '  患者档案 ',
          resourceType: 'API',
        },
        3,
      ),
    ).toEqual({
      action: 'DELETE',
      channel: 'SYNC',
      keyword: '患者档案',
      pageNo: 3,
      pageSize: 20,
      resourceType: 'API',
    });
  });

  it('默认类型筛选：菜单页 / 接口页各自预选，可清空看全部', () => {
    expect(emptyFilters('MENU').resourceType).toBe('MENU');
    expect(emptyFilters('API').resourceType).toBe('API');
    expect(emptyFilters().resourceType).toBe('');
  });

  it('操作人展示：系统触发（启动扫描）显示「系统」', () => {
    expect(operatorText(null)).toBe('系统');
    expect(operatorText('王运维（示例）（ops.demo）')).toBe(
      '王运维（示例）（ops.demo）',
    );
  });

  it('时间展示：ISO-8601 带时区 → yyyy-MM-dd HH:mm:ss', () => {
    expect(timeText('2026-11-01T10:00:00+08:00')).toBe('2026-11-01 10:00:00');
  });

  it('标签颜色：动作与类型映射稳定', () => {
    expect(actionColor('CREATE')).toBe('success');
    expect(actionColor('DELETE')).toBe('red');
    expect(actionColor('OFFLINE')).toBe('warning');
    expect(typeColor('MENU')).toBe('blue');
    expect(typeColor('API')).toBe('purple');
  });
});
