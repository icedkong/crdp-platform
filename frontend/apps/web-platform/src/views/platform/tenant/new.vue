<script lang="ts" setup>
import type { ConfigTemplateView, TenantOnboardingResult } from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Checkbox,
  CheckboxGroup,
  DatePicker,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  InputNumber,
  InputPassword,
  Radio,
  RadioGroup,
  Select,
  Space,
  Steps,
  Switch,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { listConfigTemplates } from '#/api/core/config-template';
import { listModules } from '#/api/core/module';
import { createTenant } from '#/api/core/tenant';

import {
  EMPTY_EXPIRY_FORM,
  expiryFormText,
  toCreateExpiry,
  validateExpiryForm,
} from './lifecycle';
import {
  EMPTY_QUOTA_FORM,
  quotaFormText,
  toCreateQuota,
  validateQuotaForm,
} from './quota';
import {
  PROVISION_TITLES,
  provisionSkipText,
  subdomainPreview,
  suggestTenantCode,
  templateOptionLabel,
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
const modules = ref<string[]>([]);
// 模块选项来自目录（E05-T05 / PLT-MOD-004）：只列上架的非基础模块，默认全选
const moduleOptions = ref<
  Array<{ code: string; description: string; name: string }>
>([]);

// 配置模板选项（E12-T05 / A-FLD-014「新建租户时选择模板初始化」）：
// 取最近 100 条模板（新→旧），undefined = 不使用模板；列表读失败不挡向导（选项为空）
const templateId = ref<number | undefined>(undefined);
const templateOptions = ref<ConfigTemplateView[]>([]);

const templateSelectOptions = computed(() => [
  { label: '不使用模板', value: -1 },
  ...templateOptions.value.map((item) => ({
    label: templateOptionLabel(item),
    value: item.id,
  })),
]);

// Select 绑定值：-1 表示「不使用模板」
const templateSelection = computed({
  get: () => templateId.value ?? -1,
  set: (value: number) => {
    templateId.value = value === -1 ? undefined : value;
  },
});

const selectedTemplate = computed(() =>
  templateOptions.value.find((item) => item.id === templateId.value),
);

onMounted(async () => {
  try {
    const catalog = await listModules();
    moduleOptions.value = catalog
      .filter((item) => !item.baseModule && item.status === 'ON')
      .map((item) => ({
        code: item.code,
        description: item.description,
        name: item.name,
      }));
    modules.value = moduleOptions.value.map((item) => item.code);
  } catch {
    moduleOptions.value = [];
    modules.value = [];
  }
  try {
    const page = await listConfigTemplates(1, 100);
    templateOptions.value = page.items;
  } catch {
    templateOptions.value = [];
  }
});
// 到期设置（E05-T02 / PLT-TEN-011，向导第 2 步）：默认长期有效，选择有到期日时展开其余项
const expiry = reactive({ ...EMPTY_EXPIRY_FORM });
const expiryErrors = computed(() => validateExpiryForm(expiry));
// 配额（E05-T09 / PLT-TEN-014，向导第 2 步）：默认不限；开启后填入上限（存储 GB → 字节）
const quota = reactive({ ...EMPTY_QUOTA_FORM });
const quotaErrors = computed(() => validateQuotaForm(quota));
const adminSkipped = ref(true);
const admin = reactive({
  displayName: '',
  initialPassword: '',
  username: '',
});
const codeError = computed(() => validateTenantCode(basic.code));

const STEP_TITLES = ['基本信息', '模块与到期设置', '首个租户管理员', '确认'];

const adminError = computed(() =>
  adminSkipped.value
    ? undefined
    : validateInitialPassword(admin.initialPassword),
);

/** 确认步骤展示用：勾选模块的名称。 */
const moduleNames = computed(() =>
  modules.value
    .map(
      (code) =>
        moduleOptions.value.find((item) => item.code === code)?.name ?? code,
    )
    .toSorted((left, right) => left.localeCompare(right, 'zh-Hans-CN')),
);

const canLeaveStep = computed(() => {
  if (current.value === 0) {
    return basic.name.trim().length > 0 && !codeError.value;
  }
  if (current.value === 1) {
    return (
      Object.keys(expiryErrors.value).length === 0 &&
      Object.keys(quotaErrors.value).length === 0
    );
  }
  if (current.value === 2) {
    return (
      adminSkipped.value ||
      (admin.username.trim().length > 0 && !adminError.value)
    );
  }
  return true;
});

function onExpireDate(_date: unknown, date: null | string | string[]) {
  expiry.expireAt = Array.isArray(date) ? '' : (date ?? '');
}

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
      modules: [...modules.value],
      name: basic.name.trim(),
      templateId: templateId.value,
      ...toCreateExpiry(expiry),
      ...toCreateQuota(quota),
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
  step: string;
}): string {
  if (step.status === 'FAILED') return step.error ?? '失败';
  // SKIPPED 文案按步骤区分：admin=启用前补设，config-template=未选择模板（E12-T05）
  if (step.status === 'SKIPPED') return provisionSkipText(step.step);
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

      <!-- 第 2 步：模块、配置模板与到期设置（模块开通 E05-T05；配置模板 E12-T05 / A-FLD-014；到期设置 E05-T02） -->
      <Form v-show="current === 1" layout="vertical" style="max-width: 480px">
        <FormItem
          label="开通模块"
          help="平台基础必选；下架模块不可选（PLT-MOD-004）"
        >
          <CheckboxGroup v-model:value="modules">
            <Checkbox checked disabled value="base">平台基础（必选）</Checkbox>
            <Checkbox
              v-for="item in moduleOptions"
              :key="item.code"
              :value="item.code"
            >
              {{ item.name }}（{{ item.code }}）
            </Checkbox>
          </CheckboxGroup>
        </FormItem>
        <FormItem
          label="配置模板初始化"
          help="用已有租户的配置模板初始化新租户：只复制配置（表单、计划、报告单等），不含患者数据与账号；初始化后各租户独立修改"
        >
          <Select
            v-model:value="templateSelection"
            :options="templateSelectOptions"
            placeholder="选择配置模板"
          />
        </FormItem>
        <FormItem label="到期类型" required>
          <RadioGroup v-model:value="expiry.expireType">
            <Radio value="LONG_TERM">长期有效</Radio>
            <Radio value="FIXED">有到期日</Radio>
          </RadioGroup>
          <div
            v-if="expiry.expireType === 'LONG_TERM'"
            class="text-neutral-500 mt-1"
          >
            长期有效租户不提醒、不进入宽限期。
          </div>
        </FormItem>
        <template v-if="expiry.expireType === 'FIXED'">
          <FormItem
            label="试用或服务到期日"
            required
            :validate-status="expiryErrors.expireAt ? 'error' : undefined"
            :help="expiryErrors.expireAt"
          >
            <DatePicker
              :value="expiry.expireAt ? dayjs(expiry.expireAt) : undefined"
              placeholder="选择日期"
              style="width: 100%"
              value-format="YYYY-MM-DD"
              @change="onExpireDate"
            />
          </FormItem>
          <FormItem
            label="提醒节点（到期前 N 天，逗号分隔）"
            :validate-status="expiryErrors.remindDays ? 'error' : undefined"
            :help="expiryErrors.remindDays ?? '默认 30,7,1'"
          >
            <Input v-model:value="expiry.remindDays" placeholder="如：30,7,1" />
          </FormItem>
          <FormItem
            label="宽限期天数"
            :validate-status="expiryErrors.graceDays ? 'error' : undefined"
            :help="expiryErrors.graceDays ?? '默认 15'"
          >
            <InputNumber
              v-model:value="expiry.graceDays"
              :max="365"
              :min="1"
              placeholder="15"
              style="width: 100%"
            />
          </FormItem>
          <FormItem label="宽限期是否只读">
            <Switch v-model:checked="expiry.graceReadOnly" />
            <span class="switch-hint">
              {{
                expiry.graceReadOnly
                  ? '宽限期内只能查看'
                  : '宽限期内正常使用并提示续费'
              }}
            </span>
          </FormItem>
        </template>
        <!-- 配额（E05-T09 / PLT-TEN-014）：默认不限；保存后可随时在详情「配额」页签调整 -->
        <FormItem
          label="用户数上限"
          :help="
            quota.userLimited ? '达到上限后禁止新建或启用用户' : '不限（默认）'
          "
        >
          <Switch v-model:checked="quota.userLimited" />
          <InputNumber
            v-if="quota.userLimited"
            v-model:value="quota.userLimit"
            :min="1"
            :precision="0"
            placeholder="如：50"
            style="width: 160px; margin-left: 12px"
          />
        </FormItem>
        <FormItem
          label="存储上限（GB）"
          :help="
            quota.storageLimited ? '达到上限后禁止上传文件' : '不限（默认）'
          "
        >
          <Switch v-model:checked="quota.storageLimited" />
          <InputNumber
            v-if="quota.storageLimited"
            v-model:value="quota.storageLimitGb"
            :min="0.1"
            :step="1"
            placeholder="如：100"
            style="width: 160px; margin-left: 12px"
          />
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
          平台基础{{
            moduleNames.length > 0 ? `、${moduleNames.join('、')}` : ''
          }}
        </DescriptionsItem>
        <DescriptionsItem label="配置模板初始化">
          {{
            selectedTemplate
              ? templateOptionLabel(selectedTemplate)
              : '不使用模板'
          }}
        </DescriptionsItem>
        <DescriptionsItem label="到期设置">
          {{ expiryFormText(expiry) }}
        </DescriptionsItem>
        <DescriptionsItem label="配额">
          {{ quotaFormText(quota) }}
        </DescriptionsItem>
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
        <Button
          type="primary"
          @click="router.push(`/platform/tenant/${result.tenantId}/provision`)"
        >
          查看进度
        </Button>
        <Button @click="router.push('/platform/tenant')">返回租户列表</Button>
      </Space>
    </Card>
  </Page>
</template>

<style scoped>
.switch-hint {
  margin-left: 12px;
  color: #4e5969;
}
</style>
