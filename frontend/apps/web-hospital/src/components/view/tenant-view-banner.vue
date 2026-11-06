<script lang="ts" setup>
import type { TenantViewShellSession } from '@crdp/api';

import { computed } from 'vue';

import { tenantViewBannerTitle, tenantViewRemainingText } from '@crdp/api';
import { Alert, Button, Space } from 'ant-design-vue';

/**
 * E20-T04 / PLT-VIEW-003 · TenantViewBanner（components.md §13）：
 * 平台进入租户视角时医院端顶部常驻提示条 + 一键退出——
 * 橙色 `a-alert banner`、**不可关闭**（验收：视角中页面顶部常驻标识）；
 * 显示「租户视角：租户名称」、会话号、剩余时长（无操作 15 分钟 / 单次最长 2 小时）
 * 与「退出租户视角」按钮（只退自己的会话）。
 */
const props = defineProps<{
  remainingSeconds: number;
  session: TenantViewShellSession;
}>();

const emit = defineEmits<{
  (e: 'exit'): void;
}>();

const title = computed(() => tenantViewBannerTitle(props.session));
const remainingText = computed(() =>
  tenantViewRemainingText(props.remainingSeconds),
);
</script>

<template>
  <Alert
    :message="title"
    banner
    class="tenant-view-banner"
    :closable="false"
    :show-icon="false"
    type="warning"
  >
    <template #description>
      <Space :size="12" class="banner-row" wrap>
        <span>
          会话号
          <span class="code">{{ session.sessionNo }}</span>
        </span>
        <span>
          剩余 {{ remainingText }}（无操作 15 分钟自动退出，单次最长 2 小时）
        </span>
        <Button danger size="small" type="primary" @click="emit('exit')">
          退出租户视角
        </Button>
      </Space>
    </template>
  </Alert>
</template>

<style scoped>
.banner-row {
  font-size: 13px;
  line-height: 20px;
}

.code {
  font-family: monospace;
}
</style>
