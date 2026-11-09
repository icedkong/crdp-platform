/**
 * E24-T09 走查回归 · 随访表单页挂载冒烟。
 *
 * 走查（docs/testing/responsive-walkthrough.md W-0）发现：E16-T11 在本页
 * setup 里新增 `watch(compareReportId, …)` 但未从 'vue' 导入 `watch`，组件
 * setup 抛 ReferenceError，整页在任何设备上白屏——单元测试只测 logic.ts
 * 纯函数、CI 无 vue-tsc，漏网。本用例真实挂载页面组件（重依赖全量 mock），
 * setup 能跑通且渲染出表单骨架即通过；同类「缺导入 / setup 崩」回归会被
 * 这里拦住。
 */
import { mount } from '@vue/test-utils';
import { computed, ref } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import RecordForm from './form.vue';

// ---- 重依赖 mock（挂载冒烟只关心 setup 与首帧渲染，不发真实请求；vitest 会把
// vi.mock 提升到 import 之前，import RecordForm 时 mock 已生效） ----

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { recordId: '99001' },
    query: {},
  }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

vi.mock('@vben/stores', () => ({
  useUserStore: () => ({
    userInfo: {
      tenantCode: 'demoa',
      userId: '1',
      displayName: '演示护士（示例）',
    },
  }),
}));

vi.mock('@crdp/form-engine', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@crdp/form-engine')>();
  return {
    ...actual,
    FormRenderer: actual.FormRenderer, // 真组件对空模板有分节空态，保留
    useAutoSave: () => ({
      conflict: ref(null),
      discardLocalDraft: vi.fn(async () => {}),
      hasLocalDraft: vi.fn(async () => false),
      keepMine: vi.fn(async () => {}),
      lastSavedAt: ref(null),
      loadServer: vi.fn(async () => {}),
      restoreLocalDraft: vi.fn(async () => null),
      saveNow: vi.fn(async () => {}),
      status: ref('idle' as const),
    }),
  };
});

vi.mock('#/api/core/form', () => ({
  // 最小合法模板（FormSchema：categories/code/name/rules/version）
  fetchFormSchema: vi.fn(async () => ({
    categories: [],
    code: 'A_ANNUAL_FU',
    name: '年度随访表（示例）',
    rules: [],
    version: 1,
  })),
}));

vi.mock('#/api/core/report', () => ({
  fetchPatientReports: vi.fn(async () => ({ items: [] })),
}));

vi.mock('#/api/core/task', () => ({
  fetchRecordDetail: vi.fn(
    async (): Promise<Record<string, unknown>> => ({
      id: '99001',
      formCode: 'A_ANNUAL_FU',
      formVersion: 1,
      status: 'DRAFT',
      version: 0,
      data: {},
      dataMeta: {},
      // 开放态任务 + 窗口已结束 → bannersOf 会调用 today()（W-0 连带：
      // 调用位错把 source 顶进 today 形参时横幅 computed 崩，横幅不渲染）
      context: {
        createType: null,
        patientId: '30001',
        planName: '冠心病术后年度随访（示例）',
        taskStatus: 'IN_PROGRESS',
        visitName: '第4年随访',
        windowEnd: '2020-01-01',
        windowStart: '2019-12-18',
      },
      patient: null,
      previous: null,
      compareRules: null,
      source: 'TASK',
    }),
  ),
  fetchTaskQueue: vi.fn(async () => ({ nextId: null, prevId: null })),
  saveRecordDraft: vi.fn(),
  startTask: vi.fn(),
  submitRecord: vi.fn(),
}));

vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({ hasPermission: () => true }),
}));

vi.mock('#/reference/use-watermark-viewer', () => ({
  useWatermarkViewer: () => ({ open: vi.fn() }),
}));

describe('e24-t09 随访表单页挂载冒烟（走查 W-0 回归）', () => {
  it('setup 不抛 ReferenceError，渲染出表单页骨架', async () => {
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});
    try {
      const wrapper = mount(RecordForm, {
        attachTo: document.body,
        global: { config: { warnHandler: () => {} } },
      });
      // 未提交态渲染 .page 骨架（提交成功态是 Result，这里走表单分支）
      expect(wrapper.find('.page').exists()).toBe(true);
      wrapper.unmount();
    } finally {
      consoleWarn.mockRestore();
      consoleError.mockRestore();
    }
  });

  it('窗口已结束的任务渲染「逾期完成」横幅（bannersOf 实参位次回归，W-0 连带）', async () => {
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});
    try {
      const wrapper = mount(RecordForm, {
        attachTo: document.body,
        global: { config: { warnHandler: () => {} } },
      });
      // onMounted 的 load() 异步完成后横幅才渲染（mock 全部即答，等微任务队列）
      await new Promise((resolve) => setTimeout(resolve, 30));
      // today() 被正确当函数调用：窗口 2020-01-01 已结束 → 逾期横幅出现
      expect(document.body.innerText).toContain('窗口已于 2020-01-01 结束');
      expect(document.body.innerText).toContain('逾期完成');
      wrapper.unmount();
    } finally {
      consoleWarn.mockRestore();
      consoleError.mockRestore();
    }
  });

  it('组件模块可导入（watch 等响应式 API 缺导入时挂载用例即抛错）', () => {
    expect(RecordForm).toBeTruthy();
  });
});

// computed 引用避免未用告警（与页面同源的响应式导入示例）
void computed;
