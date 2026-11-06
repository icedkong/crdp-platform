<script lang="ts" setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';

import { tenantViewExitReasonText } from '@crdp/api';
import { Button, Result } from 'ant-design-vue';

/**
 * E20-T04 / PLT-VIEW-003 · 医院端视角退出页：一键退出、到时、空闲超时、
 * 权限变更与令牌失效后的统一落地（reason 与台账结束方式对应）。
 * 退出租户视角不影响平台端登录，回平台端可重新进入（PLT-VIEW-004）。
 */
const route = useRoute();
const router = useRouter();

const reasonText = computed(() =>
  tenantViewExitReasonText(
    typeof route.query.reason === 'string' ? route.query.reason : 'LOGOUT',
  ),
);

function toLogin() {
  void router.replace({ path: LOGIN_PATH });
}
</script>

<template>
  <div class="view-exit">
    <Result status="info" title="租户视角已结束" :sub-title="reasonText">
      <template #extra>
        <Button type="primary" @click="toLogin">回到医院端登录页</Button>
      </template>
    </Result>
  </div>
</template>

<style scoped>
.view-exit {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100vw;
  height: 100vh;
  background: #f5f7fa;
}
</style>
