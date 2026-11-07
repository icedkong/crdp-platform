<script lang="ts" setup>
import type {
  PlatformRoleView,
  TenantListItem,
  ViewRuleCatalog,
  ViewRuleEditModel,
  ViewRuleItem,
} from '@crdp/api';

import { computed, ref, watch } from 'vue';

import {
  newViewRuleModel,
  toViewRuleEditModel,
  toViewRuleSaveRequest,
  VIEW_RULE_LAYER_OPTIONS,
  VIEW_RULE_MASK_OPTIONS,
  VIEW_RULE_VISIBILITY_OPTIONS,
} from '@crdp/api';
import {
  Alert,
  Checkbox,
  Form,
  FormItem,
  Input,
  Modal,
  Select,
  SelectOption,
  Switch,
} from 'ant-design-vue';

import { listRoles } from '#/api/core/role';
import { listTenants } from '#/api/core/tenant';
import { createViewRule, updateViewRule } from '#/api/core/view-rule';

import {
  catalogFieldsOf,
  catalogHasWholeResource,
  catalogResources,
  validateEditModel,
} from './logic';

/**
 * 新建 / 维护视角规则（E20-T02 / PLT-VIEW-007 · pages.md §11 视角规则）：
 * 层级（全局默认 / 租户 / 模块 / 字段）→ 选择器（租户 / 模块 / 资源对象与字段）→
 * 适用平台角色（空 = 全部）→ 可见性（明文 / 脱敏 / 设盲 / 隐藏）+ 脱敏规则、操作、允许导出。
 * 高敏变更（改明文、取消设盲 / 只读、允许导出）的第二人复核随 E20-T05，本页普通变更即时生效。
 */
const props = defineProps<{
  catalog: null | ViewRuleCatalog;
  open: boolean;
  rule: null | ViewRuleItem;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'done'): void;
}>();

const form = ref<ViewRuleEditModel>(newViewRuleModel());
const roleRows = ref<PlatformRoleView[]>([]);
const roleOptions = ref<{ label: string; value: number }[]>([]);
const tenantOptions = ref<{ label: string; value: number }[]>([]);
const moduleOptions = ref<{ label: string; value: string }[]>([]);
const errors = ref<string[]>([]);
const submitting = ref(false);
const submitError = ref('');
const loadError = ref('');

const isCreate = computed(() => props.rule === null);
const title = computed(() =>
  isCreate.value
    ? '新建视角规则'
    : `维护视角规则：${props.rule?.name ?? ''}（${props.rule?.code ?? ''}）`,
);
const okDisabled = computed(() => errors.value.length > 0);
const fields = computed(() => props.catalog?.fields ?? []);
const resourceOptions = computed(() =>
  catalogResources(fields.value).map((resource) => ({
    label: resource,
    value: resource,
  })),
);
const fieldOptions = computed(() =>
  form.value.resource
    ? catalogFieldsOf(fields.value, form.value.resource).map((field) => ({
        label: field.label,
        value: field.field ?? '',
      }))
    : [],
);
const wholeAllowed = computed(
  () =>
    form.value.layer === 'FIELD' &&
    form.value.resource !== '' &&
    catalogHasWholeResource(fields.value, form.value.resource),
);

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    errors.value = [];
    submitError.value = '';
    loadError.value = '';
    try {
      const [roles, tenants] = await Promise.all([
        listRoles(),
        listTenants({ pageNo: 1, pageSize: 100 }),
      ]);
      roleRows.value = roles;
      roleOptions.value = roles.map((role: PlatformRoleView) => ({
        label: `${role.name}（${role.code}）`,
        value: role.id,
      }));
      tenantOptions.value = tenants.items.map((tenant: TenantListItem) => ({
        label: `${tenant.name}（${tenant.code}）`,
        value: tenant.id,
      }));
    } catch (error) {
      loadError.value = error instanceof Error ? error.message : String(error);
    }
    moduleOptions.value = [
      { label: '平台基础（base）', value: 'base' },
      { label: '随访（followup）', value: 'followup' },
    ];
    if (props.rule) {
      form.value = toViewRuleEditModel(props.rule);
      // 规则行只带角色码；提交按 id 整体替换，这里按码映射回 id 预填
      const byCode = new Map(
        roleRows.value.map((role) => [role.code, role.id]),
      );
      form.value.roleIds = (props.rule.roleCodes ?? [])
        .map((code) => byCode.get(code))
        .filter((id): id is number => id !== undefined);
    } else {
      form.value = newViewRuleModel();
    }
    onFormChange();
  },
);

function onFormChange() {
  // 层级切换时清理不适用字段，避免提交无关选择器
  if (form.value.layer === 'GLOBAL') {
    form.value.tenantId = null;
  }
  errors.value = validateEditModel(form.value, fields.value);
}

async function submit() {
  if (okDisabled.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    const request = toViewRuleSaveRequest(form.value);
    await (props.rule
      ? updateViewRule(props.rule.id, request)
      : createViewRule(request));
    emit('update:open', false);
    emit('done');
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open"
    :confirm-loading="submitting"
    :ok-button-props="{ disabled: okDisabled }"
    :title="title"
    :width="640"
    ok-text="保存"
    cancel-text="取消"
    @ok="submit"
    @cancel="emit('update:open', false)"
  >
    <Alert
      v-if="loadError"
      :message="`选项加载失败：${loadError}`"
      show-icon
      type="error"
      class="mb-2"
    />
    <Alert
      v-if="submitError"
      :message="`保存失败：${submitError}`"
      show-icon
      type="error"
      class="mb-2"
    />
    <Form layout="vertical" class="crdp-form">
      <FormItem v-if="!isCreate" label="编号">
        <span class="code">{{ rule?.code }}</span>
        <span class="readonly-hint">（创建后不可修改）</span>
      </FormItem>
      <FormItem
        label="名称"
        required
        :validate-status="errors.length > 0 ? 'error' : undefined"
        :help="errors.join('；') || undefined"
      >
        <Input
          v-model:value="form.name"
          :maxlength="128"
          placeholder="如：业务数据默认：可见、只读（示例）"
          show-count
          @change="onFormChange"
        />
      </FormItem>
      <FormItem label="层级" required>
        <Select
          v-model:value="form.layer"
          :disabled="!isCreate"
          placeholder="选择规则层级"
          data-testid="view-rule-layer"
          @change="onFormChange"
        >
          <SelectOption
            v-for="option in VIEW_RULE_LAYER_OPTIONS"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }} —— {{ option.hint }}
          </SelectOption>
        </Select>
      </FormItem>
      <FormItem
        v-if="form.layer !== 'GLOBAL'"
        label="适用租户（可选，空 = 全部租户）"
      >
        <Select
          v-model:value="form.tenantId"
          allow-clear
          :disabled="form.layer === 'TENANT'"
          :placeholder="form.layer === 'TENANT' ? '租户层级必选' : '全部租户'"
          :options="tenantOptions"
          @change="onFormChange"
        />
      </FormItem>
      <FormItem v-if="form.layer === 'MODULE'" label="模块" required>
        <Select
          v-model:value="form.moduleCode"
          placeholder="选择模块"
          :options="moduleOptions"
          @change="onFormChange"
        />
      </FormItem>
      <template v-if="form.layer === 'FIELD'">
        <FormItem label="资源对象" required>
          <Select
            v-model:value="form.resource"
            placeholder="选择资源对象（目录内）"
            :options="resourceOptions"
            data-testid="view-rule-resource"
            @change="onFormChange"
          />
        </FormItem>
        <FormItem label="字段">
          <Select
            v-model:value="form.field"
            :disabled="form.resourceWhole || fieldOptions.length === 0"
            :options="fieldOptions"
            placeholder="选择字段"
            data-testid="view-rule-field"
            @change="onFormChange"
          />
          <Checkbox
            v-if="wholeAllowed"
            v-model:checked="form.resourceWhole"
            class="whole-check"
            @change="onFormChange"
          >
            整个资源对象（含所有字段，如原件与附件）
          </Checkbox>
        </FormItem>
      </template>
      <FormItem label="适用平台角色（空 = 全部角色）">
        <Select
          v-model:value="form.roleIds"
          mode="multiple"
          allow-clear
          :options="roleOptions"
          placeholder="全部角色"
          data-testid="view-rule-roles"
        />
      </FormItem>
      <FormItem label="可见性" required>
        <Select
          v-model:value="form.visibility"
          data-testid="view-rule-visibility"
          @change="onFormChange"
        >
          <SelectOption
            v-for="option in VIEW_RULE_VISIBILITY_OPTIONS"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </SelectOption>
        </Select>
      </FormItem>
      <FormItem
        v-if="form.visibility === 'MASKED'"
        label="脱敏规则"
        required
        extra="沿用字段资源与策略的脱敏规则（platform-spec 第 4 章）"
      >
        <Select
          v-model:value="form.maskRule"
          :options="VIEW_RULE_MASK_OPTIONS"
          placeholder="选择脱敏规则"
          data-testid="view-rule-mask"
          @change="onFormChange"
        />
      </FormItem>
      <FormItem label="操作" required>
        <Select v-model:value="form.operation" @change="onFormChange">
          <SelectOption value="READONLY">只读</SelectOption>
          <SelectOption value="EDITABLE">可编辑</SelectOption>
        </Select>
      </FormItem>
      <FormItem label="允许导出">
        <Switch v-model:checked="form.allowExport" />
        <span class="switch-hint">
          开启后命中本规则的角色可导出 / 打印 / 下载；默认禁止（PLT-VIEW-008）
        </span>
      </FormItem>
      <FormItem v-if="!isCreate" label="状态">
        <Select v-model:value="form.status" data-testid="view-rule-status">
          <SelectOption value="ACTIVE">生效</SelectOption>
          <SelectOption value="DISABLED">停用</SelectOption>
        </Select>
      </FormItem>
    </Form>
  </Modal>
</template>

<style scoped>
.code {
  font-family: monospace;
}

.readonly-hint,
.switch-hint,
.whole-check {
  margin-left: 12px;
  font-size: 13px;
  color: #86909c;
}
</style>
