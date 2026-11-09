/**
 * E24-T08 / NFR-RESP-001、002 · 5 个关键页面 × 真机矩阵自动化核心。
 * 矩阵定义、逐页手工用例与执行记录表见 docs/testing/device-matrix.md。
 *
 * 自动化覆盖矩阵中 CI 可模拟的维度（CSS 视口档位、触控指针、旧内核能力面）：
 * ① 每档设备经 applyDeviceProfile 后，useBreakpoint 的档位 / 触控判定与档案
 *    一致——这是 5 个页面模板分支（卡片 / 抽屉 / 底部固定操作栏）的信号源；
 * ② 路由级：手机档位守卫放行 5 个关键页面（responsive 名单）、拦截未适配页
 *    （MobileBlocker「请在电脑或平板上操作」）；平板 / 桌面档位不拦截；
 * ③ 手机档位行为抽样：患者列表 / 患者详情 / 我的任务的手机形态纯逻辑在全部
 *    手机档位下成立（随访表单与报告单上传 / 查看的深度行为分别由
 *    form-renderer-responsive.spec 与 upload-report-dialog / viewer 系列锁定）。
 * 真机独有维度（相机连拍、双指手势、safe-area、软键盘、微信内置浏览器 UA）
 * 由文档手工用例覆盖，物理设备执行由人按文档记录表完成（AGENTS.md §9）。
 */
import type { TaskRow, TaskStatus } from '@crdp/api';
import type { DeviceProfile } from '@crdp/responsive';

import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';

import {
  applyDeviceProfile,
  createMobileBlockedRoute,
  DEVICE_PROFILES,
  MOBILE_BLOCKED_PATH,
  setupResponsiveGuard,
  useBreakpoint,
} from '@crdp/responsive';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { responsiveRouteNames } from '#/router/responsive-routes';

import { contactCardLines, pageSlice } from './patient/detail-responsive-logic';
import { drawerFilterCount } from './patient/responsive-logic';
import {
  isTaskOverdue,
  segmentOfStatus,
  taskCardActions,
} from './task/responsive-logic';

const preferences = vi.hoisted(() => ({ app: { isMobile: false } }));

vi.mock('@vben/preferences', () => {
  return { preferences };
});

const restores: Array<() => void> = [];
const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const restore of restores.splice(0)) restore();
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

let lastBreakpointState: ReturnType<typeof useBreakpoint> | undefined;

/** 唯一组件：既作 useBreakpoint 宿主（信号源），也作守卫测试路由的占位组件 */
const Harness = defineComponent({
  setup() {
    lastBreakpointState = useBreakpoint();
    return () => null;
  },
});

/** 按档案挂 useBreakpoint（页面模板分支的信号源） */
function mountBreakpoint() {
  const wrapper = mount(Harness);
  mounted.push(wrapper);
  if (!lastBreakpointState) {
    throw new Error('useBreakpoint 未在 Harness 中执行');
  }
  return lastBreakpointState;
}

/** 5 个关键页面 + 一个未适配页 + /mobile 兜底的守卫测试路由器 */
function buildMatrixRouter() {
  const keyRoutes = [
    { name: 'FollowupPatient', path: '/followup/patient' },
    { name: 'FollowupPatientDetail', path: '/followup/patient/:id' },
    { name: 'FollowupRecordForm', path: '/followup/record/:id' },
    { name: 'FollowupTask', path: '/followup/task' },
    { name: 'FollowupReport', path: '/followup/report' },
  ].map(({ name, path }) => ({
    component: Harness,
    meta: { responsive: true, title: name },
    name,
    path,
  }));
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      ...keyRoutes,
      {
        component: Harness,
        meta: { title: '字段字典（未适配页对照）' },
        name: 'FollowupField',
        path: '/followup/field',
      },
      createMobileBlockedRoute(Harness),
    ],
  });
  setupResponsiveGuard(router);
  return router;
}

/** 与生产 menuNodesToRoutes 同口径：名单内路由 meta.responsive = true */
function applyProfile(profile: DeviceProfile) {
  const restore = applyDeviceProfile(profile);
  restores.push(restore);
  preferences.app.isMobile = profile.kind === 'mobile';
  return restore;
}

const PHONE_PROFILES = DEVICE_PROFILES.filter((p) => p.kind === 'mobile');
const WIDE_PROFILES = DEVICE_PROFILES.filter((p) => p.kind !== 'mobile');

describe('真机矩阵 × 布局信号源（useBreakpoint，design-system §12 三档）', () => {
  it.each(DEVICE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：档位 / 触控判定与档案一致',
    (_key, profile) => {
      applyProfile(profile);
      const state = mountBreakpoint();
      expect(state.device.value).toBe(profile.kind);
      expect(state.isMobile.value).toBe(profile.kind === 'mobile');
      expect(state.isTablet.value).toBe(profile.kind === 'tablet');
      expect(state.isDesktop.value).toBe(profile.kind === 'desktop');
      expect(state.isTouch.value).toBe(profile.coarsePointer);
    },
  );
});

describe('5 个关键页面（路由级矩阵，E24-T02～T06 + 全院报告单检索）', () => {
  beforeEach(() => {
    preferences.app.isMobile = false;
  });

  it('responsive 名单 = 5 个关键页面的路由（design-system §12「只有 5 个页面做响应式」）', () => {
    expect([...responsiveRouteNames].toSorted()).toEqual([
      'FollowupPatient',
      'FollowupPatientDetail',
      'FollowupRecordForm',
      'FollowupReport',
      'FollowupTask',
    ]);
  });

  it.each(PHONE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：手机档位放行 5 个关键页面、拦截未适配页',
    async (_key, profile) => {
      applyProfile(profile);
      const router = buildMatrixRouter();
      for (const path of [
        '/followup/patient',
        '/followup/patient/301',
        '/followup/record/9001',
        '/followup/task',
        '/followup/report',
      ]) {
        await router.push(path);
        expect(router.currentRoute.value.path).toBe(path);
      }
      await router.push('/followup/field');
      expect(router.currentRoute.value.path).toBe(MOBILE_BLOCKED_PATH);
    },
  );

  it.each(WIDE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：平板 / 桌面档位不拦截任何页面',
    async (_key, profile) => {
      applyProfile(profile);
      const router = buildMatrixRouter();
      await router.push('/followup/field');
      expect(router.currentRoute.value.path).toBe('/followup/field');
      await router.push('/followup/report');
      expect(router.currentRoute.value.path).toBe('/followup/report');
    },
  );
});

describe('手机档位行为抽样（纯逻辑 × 全部手机档位）', () => {
  it.each(PHONE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：患者列表筛选抽屉角标按已应用条件计数（未选全的日期区间不计）',
    (_key, _profile) => {
      expect(
        drawerFilterCount({
          dischargeRange: ['2026-01-01', '2026-06-30'],
          nextVisitRange: null,
          status: 'FOLLOWING',
          wardId: 3,
        }),
      ).toBe(3);
    },
  );

  it.each(PHONE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：患者详情沟通记录转卡片（五要素逐行）+ 卡片分页',
    (_key, _profile) => {
      const lines = contactCardLines({
        contactTime: '2026-11-05T09:30:00+08:00',
        creatorName: '王医生（示例）',
        methodText: '电话',
        nextContact: '2027-02-05',
        note: '患者术后恢复良好（示例）',
        resultText: '接通',
      });
      expect(lines.map((line) => line.label)).toEqual([
        '方式',
        '结果',
        '约定下次联系',
        '备注',
        '操作人',
      ]);
      const items = Array.from({ length: 45 }, (_, i) => i);
      expect(pageSlice(items, 3)).toHaveLength(5); // 越界回落最后一页
      expect(pageSlice(items, 1).length).toBe(20);
    },
  );

  it.each(PHONE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：我的任务卡片（状态分段 / 逾期标记 / 卡片操作）',
    (_key, _profile) => {
      expect(segmentOfStatus(['OVERDUE' as TaskStatus])).toBe('OVERDUE');
      const task: TaskRow = {
        assigneeName: '王医生（示例）',
        id: '9001',
        mrn: 'MRN*001（示例）',
        occurrence: 4,
        patientId: '301',
        patientName: '周*（示例）',
        planDate: '2026-11-05',
        status: 'OVERDUE',
        taskType: 'FORM',
        windowEnd: '2026-11-12',
        windowStart: '2026-10-29',
      };
      expect(isTaskOverdue(task)).toBe(true);
      expect(
        taskCardActions(task, { canAssign: false, canEditRecord: true })
          .primary,
      ).toBe('enter');
    },
  );
});
