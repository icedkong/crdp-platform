/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- 此文件为 Ant Design 组件测试替身，使用透传 props 验证业务交互。 */
import type { PatientCorrectionView } from '@crdp/api';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import RegistrationCard from './registration-card.vue';

const api = vi.hoisted(() => ({
  fetch: vi.fn(),
  save: vi.fn(),
  history: vi.fn(),
}));
vi.mock('#/api/core/patient-correction', () => ({
  fetchRegistration: api.fetch,
  correctRegistration: api.save,
  fetchRegistrationHistory: api.history,
}));
vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({ hasPermission: () => true }),
}));
vi.mock('#/store/view', () => ({
  useTenantViewStore: () => ({ active: false }),
}));
vi.mock('@crdp/form-engine', () => ({
  FormRenderer: defineComponent({
    props: ['schema'],
    setup: (p) => () => h('div', p.schema?.name),
  }),
}));
vi.mock('ant-design-vue', () => {
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const input = (tag: string) =>
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h(tag, {
            value: p.value ?? '',
            disabled: p.disabled,
            onInput: (e: Event) =>
              emit('update:value', (e.target as HTMLInputElement).value),
          }),
    });
  return {
    Card: wrapper,
    Spin: wrapper,
    Space: wrapper,
    Table: wrapper,
    Alert: defineComponent({
      props: ['message'],
      setup: (p) => () => h('div', p.message),
    }),
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Input: input('input'),
    Textarea: input('textarea'),
    Checkbox: defineComponent({
      props: ['checked'],
      emits: ['update:checked'],
      setup:
        (p, { emit }) =>
        () =>
          h('input', {
            type: 'checkbox',
            checked: p.checked,
            onChange: (e: Event) =>
              emit('update:checked', (e.target as HTMLInputElement).checked),
          }),
    }),
    Modal: defineComponent({
      props: ['open'],
      emits: ['ok', 'cancel'],
      setup:
        (p, { slots, emit }) =>
        () =>
          p.open
            ? h('section', [
                slots.default?.(),
                h('button', { onClick: () => emit('ok') }, '确认保存'),
                h('button', { onClick: () => emit('cancel') }, '关闭'),
              ])
            : null,
    }),
  };
});
function view(id = '1'): PatientCorrectionView {
  const schema = {
    code: 'PT_REG_A',
    name: `登记 ${id}`,
    usage: 'BASELINE' as const,
    version: 1,
    categories: [],
    rules: [],
  };
  return {
    patientId: id,
    version: 0,
    data: {},
    schema,
    identitySchema: {
      ...schema,
      categories: [
        {
          key: 'identity',
          label: '身份',
          order: 1,
          fields: [
            {
              key: 'name',
              code: 'name',
              label: '姓名',
              type: 'text',
              sensitive: true,
              storage: 'encrypted',
              required: true,
            },
          ],
        },
      ],
    },
    identity: { name: '张*（示例）' },
    statusSchema: { ...schema, name: '归属与状态' },
    status: {},
    locked: false,
  };
}
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
let host: HTMLDivElement;
let app: ReturnType<typeof createApp>;
const patient = ref('1');
function click(text: string) {
  const button = [...host.querySelectorAll('button')].find(
    (b) => b.textContent?.trim() === text,
  );
  expect(button).toBeDefined();
  button?.click();
}
beforeEach(() => {
  vi.useFakeTimers();
  patient.value = '1';
  api.fetch.mockResolvedValue(view());
  api.save.mockResolvedValue(view());
  api.history.mockResolvedValue([]);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
  vi.useRealTimers();
  vi.clearAllMocks();
});
function mount() {
  app = createApp(() => h(RegistrationCard, { patientId: patient.value }));
  app.mount(host);
}
describe('oqr-04 身份输入生命周期', () => {
  it('60 秒清空敏感新值，不写浏览器存储', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    mount();
    await flush();
    click('更正敏感身份');
    await flush();
    const input = host.querySelector(
      'input:not([type=checkbox])',
    ) as HTMLInputElement;
    input.value = '更正姓名（示例）';
    input.dispatchEvent(new Event('input'));
    await flush();
    expect(input.value).toBe('更正姓名（示例）');
    await vi.advanceTimersByTimeAsync(60_000);
    await flush();
    expect(input.value).toBe('');
    expect(host.textContent).toContain('敏感新值已在 60 秒后清除');
    expect(storage).not.toHaveBeenCalled();
    expect(api.save).not.toHaveBeenCalled();
    storage.mockRestore();
  });
  it('必须确认才能提交，成功后清除敏感新值', async () => {
    mount();
    await flush();
    click('更正敏感身份');
    await flush();
    const input = host.querySelector(
      'input:not([type=checkbox])',
    ) as HTMLInputElement;
    input.value = '更正姓名（示例）';
    input.dispatchEvent(new Event('input'));
    const reason = host.querySelector('textarea') as HTMLTextAreaElement;
    reason.value = '身份核实（示例）';
    reason.dispatchEvent(new Event('input'));
    click('确认保存');
    await flush();
    expect(api.save).not.toHaveBeenCalled();
    const checkbox = host.querySelector(
      'input[type=checkbox]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    await flush();
    click('确认保存');
    await flush();
    expect(api.save).toHaveBeenCalledWith(
      '1',
      {
        baseVersion: 0,
        changes: { name: '更正姓名（示例）' },
        confirmed: true,
        reason: '身份核实（示例）',
      },
      true,
    );
    expect(host.querySelector('section')).toBeNull();
    expect(host.textContent).not.toContain('更正姓名（示例）');
  });
  it('切换患者后忽略旧患者迟到响应', async () => {
    let resolve!: (value: PatientCorrectionView) => void;
    api.fetch.mockImplementation((id: string) =>
      id === '1'
        ? new Promise<PatientCorrectionView>((done) => {
            resolve = done;
          })
        : Promise.resolve(view(id)),
    );
    mount();
    await flush();
    patient.value = '2';
    await flush();
    expect(host.textContent).toContain('登记 2');
    resolve(view('1'));
    await flush();
    expect(host.textContent).toContain('登记 2');
    expect(host.textContent).not.toContain('登记 1');
  });
});
