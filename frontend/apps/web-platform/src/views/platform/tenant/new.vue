<script lang="ts" setup>
import type { TenantOnboardingResult } from '@crdp/api';

import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Checkbox,
  CheckboxGroup,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  InputPassword,
  Radio,
  RadioGroup,
  Space,
  Steps,
} from 'ant-design-vue';

import { createTenant } from '#/api/core/tenant';

import {
  subdomainPreview,
  suggestTenantCode,
  validateInitialPassword,
  validateTenantCode,
} from './wizard';

defineOptions({ name: 'PlatformTenantNew' });

const router = useRouter();
const current = ref(0);
const submitting = ref(false);
const result = ref<null | TenantOnboardingResult>(null);
const submitError = ref('');

const basic = reactive({
  code: suggestTenantCode(),
  name: '',
});
const modules = ref<string[]>(['followup']);
const adminSkipped = ref(true);
const admin = reactive({
  displayName: '',
  initialPassword: '',
  username: '',
});
const codeError = computed(() => validateTenantCode(basic.code));

const STEP_TITLES = ['基本信息', '模块与到期设置', '首个租户管理员', '确认'];
/** 开通编排的步骤序号 → 中文标题（与后端 TenantOnboardingService 的 step 对应）。 */
const PROVISION_TITLES: Record<string, string> = {
  admin: '账号初始化（首个管理员）',
  database: '建库与账号',
  'migrate-register': '初始化与注册',
  'resource-row': '资源登记',
  'tenant-row': '租户登记',
};

const adminError = computed(() =>
  adminSkipped.value
    ? undefined
    : validateInitialPassword(admin.initialPassword),
);

const canLeaveStep = computed(() => {
  if (current.value === 0) {
    return basic.name.trim().length > 0 && !codeError.value;
  }
  if (current.value === 2) {
    return (
      adminSkipped.value ||
      (admin.username.trim().length > 0 && !adminError.value)
    );
  }
  return true;
});

function next() {
  current.value += 1;
}

function prev() {
  current.value -= 1;
}

async function submit() {
  submitting.value = true;
  submitError.value = '';
  try {
    result.value = await createTenant({
      adminDisplayName: adminSkipped.value
        ? undefined
        : admin.displayName.trim() || undefined,
      adminInitialPassword: adminSkipped.value
        ? undefined
        : admin.initialPassword,
      adminUsername: adminSkipped.value ? undefined : admin.username.trim(),
      code: basic.code.trim(),
      name: basic.name.trim(),
    });
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}

function stepStatus(status: string): 'error' | 'finish' | 'wait' {
  if (status === 'FAILED') return 'error';
  if (status === 'SKIPPED') return 'wait';
  return 'finish';
}

function stepDescription(step: {
  error: null | string;
  status: string;
}): string {
  if (step.status === 'FAILED') return step.error ?? '失败';
  if (step.status === 'SKIPPED') return '已跳过，启用前补设';
  return '';
}

const provisionItems = computed(() => {
  if (!result.value) return [];
  return result.value.steps.map((step) => ({
    description: stepDescription(step),
    status: stepStatus(step.status),
    title: PROVISION_TITLES[step.step] ?? step.step,
  }));
});
</script>

<template>
  <Page
    title="新建租户"
    description="向导走完即触发开通；中途失败可重试（从失败步骤继续）。"
  >
    <Card v-if="!result">
      <Steps
        :current="current"
        :items="STEP_TITLES.map((title) => ({ title }))"
        class="mb-6"
        style="max-width: 720px"
      />

      <!-- 第 1 步：基本信息（PLT-TEN-002：仅医院名称必填，编码自动生成可改） -->
      <Form v-show="current === 0" layout="vertical" style="max-width: 480px">
        <FormItem label="医院名称" required>
          <Input
            v-model:value="basic.name"
            :maxlength="128"
            placeholder="如：虚构示例医院甲"
            show-count
          />
        </FormItem>
        <FormItem
          label="租户编码"
          required
          :validate-status="codeError ? 'error' : undefined"
          :help="codeError"
        >
          <Input
            v-model:value="basic.code"
            :maxlength="32"
            placeholder="自动生成，可修改"
          />
        </FormItem>
        <Alert type="info" show-icon class="mt-4">
          <template #message>访问地址（PLT-TEN-015）</template>
          <template #description>
            租户子域名：
            <code>{{ subdomainPreview(basic.code.trim() || '编码') }}</code>
            ；统一登录页也可用租户编码登录。
          </template>
        </Alert>
      </Form>

      <!-- 第 2 步：模块与到期设置（模块开通 E05-T05、到期设置 E05-T02 接入后生效） -->
      <Form v-show="current === 1" layout="vertical" style="max-width: 480px">
        <FormItem label="开通模块">
          <CheckboxGroup v-model:value="modules">
            <Checkbox disabled checked value="base">平台基础（必选）</Checkbox>
            <Checkbox value="followup">随访</Checkbox>
          </CheckboxGroup>
        </FormItem>
        <FormItem label="到期设置">
          <RadioGroup value="long-term" disabled>
            <Radio value="long-term">长期有效</Radio>
          </RadioGroup>
          <div class="text-neutral-500 mt-1">
            到期日与宽限期设置随到期管理（E05-T02）提供。
          </div>
        </FormItem>
      </Form>

      <!-- 第 3 步：首个租户管理员（可跳过，启用前补设） -->
      <Form v-show="current === 2" layout="vertical" style="max-width: 480px">
        <FormItem>
          <Checkbox v-model:checked="adminSkipped">
            暂不设置，启用前补设
          </Checkbox>
        </FormItem>
        <template v-if="!adminSkipped">
          <FormItem label="用户名" required>
            <Input v-model:value="admin.username" :maxlength="64" />
          </FormItem>
          <FormItem label="姓名">
            <Input
              v-model:value="admin.displayName"
              :maxlength="64"
              placeholder="默认同用户名"
            />
          </FormItem>
          <FormItem
            label="初始口令"
            required
            :validate-status="adminError ? 'error' : undefined"
            :help="adminError ?? '首次登录将要求修改口令'"
          >
            <InputPassword
              v-model:value="admin.initialPassword"
              :maxlength="64"
            />
          </FormItem>
        </template>
      </Form>

      <!-- 第 4 步：确认 -->
      <Descriptions
        v-if="current === 3"
        bordered
        :column="1"
        style="max-width: 640px"
      >
        <DescriptionsItem label="医院名称">
          {{ basic.name.trim() }}
        </DescriptionsItem>
        <DescriptionsItem label="租户编码">
          {{ basic.code.trim() }}
        </DescriptionsItem>
        <DescriptionsItem label="访问地址">
          {{ subdomainPreview(basic.code.trim()) }}（统一登录页可用编码）
        </DescriptionsItem>
        <DescriptionsItem label="开通模块">
          平台基础{{ modules.includes('followup') ? '、随访' : '' }}
        </DescriptionsItem>
        <DescriptionsItem label="到期设置">长期有效</DescriptionsItem>
        <DescriptionsItem label="首个管理员">
          {{
            adminSkipped
              ? '启用前补设'
              : `${admin.username.trim()}（首次登录须改密）`
          }}
        </DescriptionsItem>
      </Descriptions>

      <Space class="mt-6">
        <Button v-if="current > 0" @click="prev">上一步</Button>
        <Button
          v-if="current < 3"
          type="primary"
          :disabled="!canLeaveStep"
          @click="next"
        >
          下一步
        </Button>
        <Button
          v-if="current === 3"
          type="primary"
          :loading="submitting"
          @click="submit"
        >
          确认并开通
        </Button>
      </Space>
    </Card>

    <!-- 开通结果：展示编排每一步（成功 / 跳过 / 失败），失败可重试 -->
    <Card v-else>
      <Alert
        v-if="result.steps.every((step) => step.status !== 'FAILED')"
        type="success"
        show-icon
        class="mb-6"
        message="开通完成，租户已进入试用状态"
        :description="`租户编码 ${result.code}，访问地址 ${subdomainPreview(result.code)}。`"
      />
      <Alert
        v-else
        type="error"
        show-icon
        class="mb-6"
        message="开通失败，可从失败步骤重试"
      />
      <Alert
        v-if="submitError"
        type="error"
        show-icon
        class="mb-6"
        :message="`提交失败：${submitError}`"
      />
      <Steps
        direction="vertical"
        :current="provisionItems.length"
        :items="provisionItems"
        style="max-width: 640px"
      />
      <Space class="mt-6">
        <Button
          v-if="
            result.steps.some((step) => step.status === 'FAILED') || submitError
          "
          type="primary"
          :loading="submitting"
          @click="submit"
        >
          重试
        </Button>
        <Button @click="router.push('/platform/tenant')">返回租户列表</Button>
      </Space>
    </Card>
  </Page>
</template>
