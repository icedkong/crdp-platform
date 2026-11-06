<script lang="ts" setup>
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  parseTenantViewEntryTicket,
  TENANT_VIEW_EXIT_PATH,
  tenantViewExitReasonText,
} from '@crdp/api';
import { Alert, Button, Spin } from 'ant-design-vue';

import { useTenantViewStore } from '#/store';

/**
 * E20-T04 / PLT-VIEW-002 · 医院端视角入口页（pages.md §11「进入租户视角 → 打开医院端」）：
 * 平台端以 hospitalUrl + /view/entry#ticket=…（60 秒一次性凭据，片段携带、不进
 * 服务端日志）打开本页；本页换视角令牌装壳医院端（菜单与该院一致、顶部视角横幅），
 * 随后进工作台。失败（凭据过期 / 会话结束 / 无凭据直开）落在提示态，回平台端重进。
 */
const route = useRoute();
const router = useRouter();
const viewStore = useTenantViewStore();

const errorText = ref('');
const exchanging = ref(false);

onMounted(async () => {
  const ticket = parseTenantViewEntryTicket(route.hash ?? '');
  if (!ticket) {
    errorText.value = tenantViewExitReasonText(null);
    return;
  }
  exchanging.value = true;
  try {
    await viewStore.exchangeAndAdopt(ticket);
    // 装壳完成：守卫按令牌拉 me / codes / menus（视角菜单与该院一致）后进工作台
    await router.replace({ path: '/dashboard' });
  } catch (error) {
    const body = error as { message?: string };
    errorText.value = body?.message || tenantViewExitReasonText('EXPIRED');
  } finally {
    exchanging.value = false;
  }
});

function toExit() {
  void router.replace({
    path: TENANT_VIEW_EXIT_PATH,
    query: { reason: 'EXPIRED' },
  });
}
</script>

<template>
  <div class="view-entry">
    <Spin :spinning="exchanging" tip="正在进入租户视角…">
      <div class="panel">
        <h2>租户视角</h2>
        <p v-if="!errorText" class="hint">
          正在以平台账号进入该医院的查看视角（单次最长 2 小时、空闲 15
          分钟自动退出）。
        </p>
        <Alert
          v-if="errorText"
          :message="errorText"
          show-icon
          type="error"
          class="error"
        />
        <Button v-if="errorText" type="primary" @click="toExit">
          查看说明
        </Button>
      </div>
    </Spin>
  </div>
</template>

<style scoped>
.view-entry {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100vw;
  height: 100vh;
  background: #f5f7fa;
}

.panel {
  width: 420px;
  padding: 32px;
  text-align: center;
  background: #fff;
  border-radius: 8px;
}

.hint {
  margin-top: 8px;
  font-size: 13px;
  color: #86909c;
}

.error {
  margin: 16px 0;
  text-align: left;
}
</style>
