// A-FU-006、A-FU-025（E15-T04 · components.md §13 ContactLogModal：520 宽、方式 / 结果 /
// 约定下次联系时间 / 备注 / MP3 录音；未接通提示「系统次日自动生成跟进」）
import type { ContactLogRow } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ContactLogModal from './contact-log-modal.vue';

const createContactLog = vi.fn();
const updateContactLog = vi.fn();
const uploadFile = vi.fn();

vi.mock('#/api/core/contact', () => ({
  createContactLog: (...args: unknown[]) => createContactLog(...args),
  updateContactLog: (...args: unknown[]) => updateContactLog(...args),
}));
vi.mock('#/api/core/file', () => ({
  uploadFile: (...args: unknown[]) => uploadFile(...args),
}));

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  // Modal 内容经 teleport 挂到 body；卸载并清空 body，避免多个用例的弹窗互相串台
  for (const wrapper of mounted.splice(0)) {
    wrapper.unmount();
  }
  document.body.innerHTML = '';
});

function mountModal(props: Record<string, unknown> = {}) {
  const wrapper = mount(ContactLogModal, {
    attachTo: document.body,
    props: { open: true, patientId: '301', ...props },
  });
  mounted.push(wrapper);
  return wrapper;
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

/** Modal teleport 渲染需要一拍；点底部「确定」。 */
async function clickOk(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 30));
  // 测试环境未包应用层 zh-CN locale：antd 默认英文按钮，两种文案都认
  const ok = [
    ...document.body.querySelectorAll('.ant-modal-footer button'),
  ].find((item) => {
    const label = (item.textContent ?? '').replaceAll(' ', '');
    return label.includes('确定') || label.toUpperCase().includes('OK');
  });
  ok?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await new Promise((resolve) => setTimeout(resolve, 10));
}

describe('e15-t04 ContactLogModal（记录沟通）', () => {
  it('字段齐备：方式 / 结果 / 约定下次联系时间 / 备注 / MP3 录音（可选），默认电话 · 已联系', () => {
    mountModal();
    expect(bodyText()).toContain('沟通方式');
    expect(bodyText()).toContain('联系结果');
    expect(bodyText()).toContain('约定下次联系时间');
    expect(bodyText()).toContain('情况说明');
    expect(bodyText()).toContain('上传录音（可选）');
    expect(bodyText()).not.toContain('系统次日自动生成跟进');
  });

  it('提交创建：audioFileIds / taskId / 备注随请求提交，成功后通知父级', async () => {
    createContactLog.mockResolvedValue({
      log: { id: '5001', result: 'REACHED' },
      terminatedTasks: null,
    });
    const wrapper = mountModal({ taskId: '9001' });
    await clickOk();
    await vi.waitFor(() => {
      expect(createContactLog).toHaveBeenCalledWith('301', {
        audioFileIds: [],
        method: 'PHONE',
        nextContactTime: null,
        note: null,
        result: 'REACHED',
        taskId: '9001',
      });
    });
    expect(wrapper.emitted('success')).toBeTruthy();
  });

  it('编辑模式：预填行内容、未接通显示「系统次日自动生成跟进」并走 PUT（baseVersion）', async () => {
    updateContactLog.mockResolvedValue({ id: '5001', result: 'REACHED' });
    const row = {
      audios: [],
      contactTime: '2026-11-05T09:00:00',
      createdAt: '2026-11-05T09:00:00',
      creatorName: '王医生（示例）',
      id: '5001',
      method: 'PHONE',
      nextContactTime: null,
      note: '无人接听（示例）',
      patientId: '301',
      result: 'NOT_REACHED',
      version: 3,
    } as unknown as ContactLogRow;
    const wrapper = mountModal({ row });
    expect(bodyText()).toContain('修改沟通记录');
    expect(bodyText()).toContain('系统次日自动生成跟进');
    await clickOk();
    await vi.waitFor(() => {
      expect(updateContactLog).toHaveBeenCalledWith('5001', {
        baseVersion: 3,
        method: 'PHONE',
        nextContactTime: null,
        note: '无人接听（示例）',
        result: 'NOT_REACHED',
      });
    });
    expect(wrapper.emitted('success')).toBeTruthy();
  });

  it('创建失败时不上报 success（错误提示由统一拦截器弹出）', async () => {
    createContactLog.mockRejectedValue({
      message: '联系结果必须是：…（示例）',
    });
    const wrapper = mountModal();
    await clickOk();
    await vi.waitFor(() => {
      expect(createContactLog).toHaveBeenCalled();
    });
    expect(wrapper.emitted('success')).toBeFalsy();
  });

  it('录音预检（pages.md §6.1）：非 MP3/M4A/WAV/AMR 与超 50 MB 的文件被拒且不上传', async () => {
    const wrapper = mountModal();
    const beforeUpload = (
      wrapper.vm as unknown as {
        beforeUpload: (file: { name: string; size: number }) => unknown;
      }
    ).beforeUpload;
    expect(beforeUpload).toBeTruthy();
    // 扩展名目录外：拒绝（antd LIST_IGNORE 语义 = 不进列表、不触发上传）
    expect(beforeUpload({ name: '说明（示例）.txt', size: 100 })).toBeTruthy();
    expect(beforeUpload({ name: '说明（示例）.exe', size: 100 })).toBeTruthy();
    expect(uploadFile).not.toHaveBeenCalled();
    // 超 50 MB：拒绝
    expect(
      beforeUpload({ name: '通话录音（示例）.mp3', size: 51 * 1024 * 1024 }),
    ).toBeTruthy();
    expect(uploadFile).not.toHaveBeenCalled();
    // 合法 MP3：放行（真实类型与大小上限由后端 Tika / 策略最终校验）
    expect(beforeUpload({ name: '通话录音（示例）.mp3', size: 1024 })).toBe(
      true,
    );
  });
});
