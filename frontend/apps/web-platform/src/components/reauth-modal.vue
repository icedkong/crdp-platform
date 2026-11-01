<script lang="ts" setup>
import type { ReauthResult } from '@crdp/api';

import { ref } from 'vue';

import { isReauthCancelled, ReauthTokenKeeper, runWithReauth } from '@crdp/api';
import { Form, FormItem, InputPassword, Modal } from 'ant-design-vue';

import { requestReauth } from '#/api/core/reauth';

/**
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证弹窗（平台端，与医院端同构）。
 *
 * 页面通过 ref 调 `run(action)`：action 收到附加请求头（含 X-Reauth-Token）后发起敏感请求。
 * 5 分钟窗口内的令牌只保存在内存（不落 localStorage / IndexedDB），窗口内复用、过期或收到
 * 40304 后作废重验一次；用户取消时 run 抛 ReauthCancelledError（页面静默中止）。
 * 验证失败（42200 密码不正确）保持弹窗，错误提示由统一拦截器弹出。
 */
defineOptions({ name: 'ReauthModal' });

const keeper = new ReauthTokenKeeper();
const open = ref(false);
const submitting = ref(false);
const password = ref('');
let resolver: ((value: null | ReauthResult) => void) | null = null;

/** 弹窗 + 完成验证：成功返回结果，取消返回 null（ReauthFlowDeps.promptVerified 的实现）。 */
function promptVerified(): Promise<null | ReauthResult> {
  password.value = '';
  open.value = true;
  return new Promise((resolve) => {
    resolver = resolve;
  });
}

async function confirm() {
  const value = password.value;
  if (!value || value.length < 8) {
    return;
  }
  submitting.value = true;
  try {
    const result = await requestReauth(value);
    open.value = false;
    resolver?.(result);
    resolver = null;
  } catch {
    // 密码错误（42200 等）保持弹窗；错误文案由统一拦截器提示
  } finally {
    submitting.value = false;
  }
}

function cancel() {
  open.value = false;
  resolver?.(null);
  resolver = null;
}

/** 执行敏感操作：窗口内有令牌直接发起，否则先验证（见 runWithReauth）。 */
function run<T>(action: (headers: Record<string, string>) => Promise<T>) {
  return runWithReauth(action, {
    currentToken: () => keeper.valid(),
    onInvalidated: () => keeper.clear(),
    onIssued: (result) =>
      keeper.store(result.reauthToken, result.expiresInSeconds),
    promptVerified,
  });
}

defineExpose({ isReauthCancelled, run });
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :mask-closable="false"
    :open="open"
    :width="420"
    cancel-text="取消"
    ok-text="验证并继续"
    title="身份验证"
    @cancel="cancel"
    @ok="confirm"
  >
    <Form layout="vertical" @submit.prevent>
      <FormItem label="当前登录密码" required>
        <InputPassword
          v-model:value="password"
          autocomplete="current-password"
          placeholder="请输入当前登录密码"
          @press-enter="confirm"
        />
      </FormItem>
    </Form>
    <p class="reauth-hint">敏感操作需再次验证身份，验证后 5 分钟内有效。</p>
  </Modal>
</template>

<style scoped>
.reauth-hint {
  margin: 0 0 4px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
