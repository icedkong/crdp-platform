<script lang="ts" setup>
import type { PlatformAccountView, PlatformRoleView } from '@crdp/api';

import type { AccountEditModel } from './account-logic';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Checkbox,
  CheckboxGroup,
  Form,
  FormItem,
  Input,
  InputPassword,
  Modal,
  RadioButton,
  RadioGroup,
} from 'ant-design-vue';

import {
  createPlatformAccount,
  updatePlatformAccount,
} from '#/api/core/platform-account';
import { listRoles } from '#/api/core/role';

import {
  toAccountEditModel,
  toAccountSaveRequest,
  validateAccountModel,
} from './account-logic';

/**
 * 新建 / 维护平台账号（E06-T04 / PLT-ROLE-001 · pages.md §11 平台账号）：
 * 新建强制绑定手机号（双因素）+ 初始密码（首次登录强制改密）+ 平台角色多选；
 * 维护可改展示名、手机号（换绑）、角色与状态；账号名与密码不可在此改。
 */
const props = defineProps<{
  account: null | PlatformAccountView;
  open: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'done'): void;
}>();

const form = ref<AccountEditModel>(toAccountEditModel(null, []));
const roles = ref<PlatformRoleView[]>([]);
const errors = ref<string[]>([]);
const submitting = ref(false);
const submitError = ref('');
const loadError = ref('');

const isCreate = computed(() => props.account === null);
const title = computed(() =>
  isCreate.value
    ? '新建平台账号'
    : `维护平台账号：${props.account?.username ?? ''}`,
);
const okDisabled = computed(() => errors.value.length > 0);

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    errors.value = [];
    submitError.value = '';
    loadError.value = '';
    try {
      roles.value = await listRoles();
    } catch (error) {
      roles.value = [];
      loadError.value = error instanceof Error ? error.message : String(error);
    }
    form.value = toAccountEditModel(
      props.account,
      props.account ? props.account.roleIds : [],
    );
  },
);

function onFormChange() {
  errors.value = validateAccountModel(
    form.value,
    isCreate.value ? 'create' : 'update',
  );
}

async function submit() {
  if (okDisabled.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    const request = toAccountSaveRequest(
      form.value,
      isCreate.value ? 'create' : 'update',
    );
    await (props.account
      ? updatePlatformAccount(props.account.id, request)
      : createPlatformAccount(request));
    emit('update:open', false);
    emit('done');
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}

const passwordHints = [
  '至少 8 位，含大写、小写、数字、特殊字符中至少 3 类，且不包含账号名',
  '初始密码下发后，首次登录会强制修改',
];
</script>

<template>
  <Modal
    :open="open"
    :confirm-loading="submitting"
    :ok-button-props="{ disabled: okDisabled }"
    :title="title"
    :width="560"
    ok-text="保存"
    cancel-text="取消"
    @ok="submit"
    @cancel="emit('update:open', false)"
  >
    <Alert
      v-if="loadError"
      :message="`角色加载失败：${loadError}`"
      show-icon
      type="error"
      class="mb-2"
    />
    <Form layout="vertical" class="crdp-form">
      <FormItem label="账号名" :required="isCreate">
        <Input
          v-if="isCreate"
          v-model:value="form.username"
          placeholder="小写字母开头，如 impl.demo"
          @change="onFormChange"
        />
        <span v-else class="code">{{ form.username }}</span>
        <span v-if="!isCreate" class="readonly-hint">（创建后不可修改）</span>
      </FormItem>
      <FormItem
        label="姓名"
        required
        :validate-status="errors.length > 0 ? 'error' : undefined"
        :help="errors.join('；') || undefined"
      >
        <Input
          v-model:value="form.displayName"
          :maxlength="64"
          placeholder="如：实施人员（示例）"
          show-count
          @change="onFormChange"
        />
      </FormItem>
      <FormItem
        :label="
          isCreate ? '手机号（双因素认证，必填）' : '手机号（留空 = 不换绑）'
        "
        :required="isCreate"
      >
        <Input
          v-model:value="form.phone"
          :maxlength="11"
          placeholder="11 位国内手机号，接收双因素短信"
          @change="onFormChange"
        />
      </FormItem>
      <FormItem v-if="isCreate" label="初始密码" required>
        <InputPassword
          v-model:value="form.password"
          placeholder="至少 8 位，3 类字符"
          @change="onFormChange"
        />
        <p v-for="hint in passwordHints" :key="hint" class="hint">
          {{ hint }}
        </p>
      </FormItem>
      <FormItem label="平台角色" required>
        <CheckboxGroup v-model:value="form.roleIds" @change="onFormChange">
          <div class="role-list">
            <Checkbox
              v-for="role in roles"
              :key="role.id"
              :value="role.id"
              :disabled="role.status === 'DISABLED'"
            >
              {{ role.name }}
              <span class="role-code">{{ role.code }}</span>
            </Checkbox>
          </div>
        </CheckboxGroup>
        <p class="hint">
          停用角色不可授予；平台账号只能授予平台角色（PLT-ROLE-001）。
        </p>
      </FormItem>
      <FormItem v-if="!isCreate" label="状态">
        <RadioGroup v-model:value="form.status" @change="onFormChange">
          <RadioButton value="ENABLED">启用</RadioButton>
          <RadioButton value="DISABLED">停用</RadioButton>
        </RadioGroup>
        <p class="hint">
          平台管理员至少保留 2 个启用账号，不足时后端拒绝（40901）。
        </p>
      </FormItem>
    </Form>
    <Alert
      v-if="submitError"
      :message="`保存失败：${submitError}`"
      show-icon
      type="error"
      class="mt-2"
    />
  </Modal>
</template>

<style scoped>
.code {
  font-family: monospace;
}

.readonly-hint {
  margin-left: 8px;
  color: #86909c;
}

.hint {
  margin-bottom: 4px;
  font-size: 13px;
  color: #86909c;
}

.role-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.role-code {
  margin-left: 6px;
  font-family: monospace;
  font-size: 12px;
  color: #86909c;
}
</style>
