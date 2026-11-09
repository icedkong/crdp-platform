<script lang="ts" setup>
import { Alert, Checkbox, Input, Modal } from 'ant-design-vue';
import { storeToRefs } from 'pinia';

import { useProxyReasonStore } from '#/store/proxy-reason';

/**
 * E20-T06 / PLT-VIEW-013「二次确认并填写原因」：视角模式下写请求的原因弹窗（由请求拦截器触发，
 * 各页面统一复用）——填写 2–500 字操作原因后随 X-View-Reason 头提交；勾选「本次会话沿用」后
 * 后台自动保存等写操作直接复用缓存原因。取消则中止本次写请求。
 */
const store = useProxyReasonStore();
const { draft, errors, open, remember } = storeToRefs(store);
</script>

<template>
  <Modal
    :open="open"
    :mask-closable="false"
    title="代为操作确认"
    ok-text="确认执行"
    cancel-text="取消"
    :width="480"
    @ok="store.confirm()"
    @cancel="store.cancel()"
  >
    <Alert
      message="你正在以平台账号身份代为操作，本次操作将完整记录平台审计（账号、原因、前后值）。"
      show-icon
      type="warning"
      class="mb-2"
    />
    <Input.TextArea
      v-model:value="draft"
      :maxlength="500"
      :rows="3"
      placeholder="请填写操作原因（2–500 字，如：配置核对后修正随访规则（示例））"
      show-count
      data-testid="proxy-reason-input"
      @press-enter.prevent
    />
    <div v-if="errors.length > 0" class="error">{{ errors.join('；') }}</div>
    <Checkbox v-model:checked="remember" class="mt-2">
      本次视角会话后续代为操作沿用此原因（自动保存等后台写不再重复弹窗）
    </Checkbox>
  </Modal>
</template>

<style scoped>
.mb-2 {
  margin-bottom: 8px;
}

.mt-2 {
  margin-top: 8px;
}

.error {
  margin-top: 4px;
  font-size: 13px;
  color: #ff4d4f;
}
</style>
