<script lang="ts" setup>
import type { TenantProvisionView } from '@crdp/api';

import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Popconfirm,
  Space,
  Spin,
  Steps,
} from 'ant-design-vue';

import { provisionOf, recycleTenant, retryProvision } from '#/api/core/tenant';

import { canRecycle, canRetry } from './provision';
import { PROVISION_TITLES } from './wizard';

defineOptions({ name: 'PlatformTenantProvision' });

const route = useRoute();
const router = useRouter();
const tenantId = Number(route.params.id);

const view = ref<null | TenantProvisionView>(null);
const loading = ref(false);
const retrying = ref(false);
const recycling = ref(false);

const STATUS_TEXT: Record<string, string> = {
  ENABLED: '启用',
  PROVISIONING: '开通中',
  TRIAL: '试用',
};

async function load() {
  loading.value = true;
  try {
    view.value = await provisionOf(tenantId);
  } finally {
    loading.value = false;
  }
}

async function retry() {
  retrying.value = true;
  try {
    await retryProvision(tenantId);
    await load();
  } finally {
    retrying.value = false;
  }
}

async function recycle() {
  recycling.value = true;
  try {
    await recycleTenant(tenantId);
    router.push('/platform/tenant');
  } finally {
    recycling.value = false;
  }
}

const failedSteps = computed(
  () =>
    view.value?.steps.filter((step) => step.status === 'FAILED').length ?? 0,
);

function describe(step: { error: null | string; status: string }): string {
  if (step.status === 'FAILED') return step.error ?? '失败';
  if (step.status === 'SKIPPED') return '已跳过，启用前补设';
  return '';
}

const stepItems = computed(() =>
  (view.value?.steps ?? []).map((step) => ({
    description: describe(step),
    status: step.status === 'FAILED' ? 'error' : 'finish',
    title: PROVISION_TITLES[step.step] ?? step.step,
  })),
);

onMounted(load);
</script>

<template>
  <Page title="开通进度" description="逐步结果与失败重试（PLT-TEN-008）。">
    <Card>
      <Spin :spinning="loading">
        <template v-if="view">
          <Alert
            v-if="view.status === 'TRIAL'"
            type="success"
            show-icon
            class="mb-6"
            message="开通完成，租户已进入试用状态"
          />
          <Alert
            v-else-if="failedSteps > 0"
            type="error"
            show-icon
            class="mb-6"
            message="开通失败，可从失败步骤重试；不再需要时放弃回收"
          />
          <Alert
            v-else
            type="info"
            show-icon
            class="mb-6"
            message="开通进行中"
          />

          <Descriptions
            bordered
            :column="1"
            class="mb-6"
            style="max-width: 640px"
          >
            <DescriptionsItem label="医院名称">
              {{ view.name }}
            </DescriptionsItem>
            <DescriptionsItem label="租户编码">
              {{ view.code }}
            </DescriptionsItem>
            <DescriptionsItem label="状态">
              {{ STATUS_TEXT[view.status] ?? view.status }}
            </DescriptionsItem>
          </Descriptions>

          <Steps
            direction="vertical"
            :current="stepItems.length"
            :items="stepItems"
            style="max-width: 640px"
          />

          <Space class="mt-6">
            <Button
              v-if="canRetry(view.status, failedSteps)"
              type="primary"
              :loading="retrying"
              @click="retry"
            >
              从失败步骤重试
            </Button>
            <Popconfirm
              v-if="canRecycle(view.status)"
              title="放弃回收将删除已建的租户库与账号，确定？"
              ok-text="放弃回收"
              ok-type="danger"
              @confirm="recycle"
            >
              <Button danger :loading="recycling">放弃回收</Button>
            </Popconfirm>
            <Button @click="router.push('/platform/tenant')">
              返回租户列表
            </Button>
          </Space>
        </template>
      </Spin>
    </Card>
  </Page>
</template>
