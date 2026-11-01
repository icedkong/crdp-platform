<script lang="ts" setup>
import type { MenuResourceNode } from '@crdp/api';

import type { MenuEditModel } from './resource';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Form,
  FormItem,
  Input,
  InputNumber,
  Modal,
  RadioButton,
  RadioGroup,
  Select,
  Switch,
} from 'ant-design-vue';

import { createMenu, updateMenu } from '#/api/core/menu-resource';

import {
  newMenuModel,
  parentCandidates,
  toCreateRequest,
  toEditModel,
  validateCreate,
} from './resource';

/**
 * 新建 / 维护菜单项（E05-T06 / PLT-PERM-001..003 · pages.md §11 菜单资源）。
 * 新建：类型、所属模块、权限码（按钮）一次定死；维护：只改标题、图标、排序、隐藏、路由字段、状态
 * （端别 / 类型 / 模块 / 权限码 / 来源不可改——权限码创建后不可改名，OQ-11）。
 */
const props = defineProps<{
  /** 可选模块（plt_module 目录）。 */
  modules: string[];
  /** 新建时的预填上级（点行内「新增子项」进入）。 */
  presetParentId?: null | number;
  /** 维护目标（null = 新建）。 */
  target: MenuResourceNode | null;
  tree: MenuResourceNode[];
}>();

const emit = defineEmits<{
  (e: 'done'): void;
  (e: 'update:open', open: boolean): void;
}>();

const open = defineModel<boolean>('open', { default: false });

const form = ref<MenuEditModel>(newMenuModel('PLATFORM', []));
const errors = ref<string[]>([]);
const submitting = ref(false);
const submitError = ref('');

const isCreate = computed(() => props.target === null);
const title = computed(() =>
  isCreate.value ? '新增菜单项' : `维护：${props.target?.title ?? ''}`,
);

watch(
  () => open.value,
  (value) => {
    if (!value) return;
    errors.value = [];
    submitError.value = '';
    if (props.target) {
      form.value = toEditModel(props.target);
      return;
    }
    const model = newMenuModel('PLATFORM', props.modules);
    if (props.presetParentId && props.presetParentId > 0) {
      model.parentId = String(props.presetParentId);
    }
    form.value = model;
  },
);

const parentOptions = computed(() => {
  const candidates = parentCandidates(props.tree, form.value.type);
  const options = [
    { label: '顶级', value: '0' },
    ...candidates.map((item) => ({
      label: `${item.title}（${item.routeName ?? item.permCode}）`,
      value: String(item.id),
    })),
  ];
  return options;
});

/** 编辑时类型不可换（换类型 = 删了重建）。 */
const typeDisabled = computed(() => !isCreate.value);
/** 权限码、模块、端别创建后不可改。 */
const immutableInEdit = computed(() => !isCreate.value);

function validate() {
  errors.value = isCreate.value ? validateCreate(form.value) : [];
}

async function submit(status: 'DISABLED' | 'ENABLED') {
  validate();
  if (errors.value.length > 0) return;
  submitting.value = true;
  submitError.value = '';
  try {
    if (isCreate.value) {
      await createMenu(toCreateRequest(form.value));
    } else if (props.target) {
      await updateMenu(props.target.id, {
        component:
          form.value.component.trim() === ''
            ? null
            : form.value.component.trim(),
        hidden: form.value.hidden,
        icon: form.value.icon.trim() === '' ? null : form.value.icon.trim(),
        path: form.value.path.trim() === '' ? null : form.value.path.trim(),
        routeName:
          form.value.routeName.trim() === ''
            ? null
            : form.value.routeName.trim(),
        sortNo: form.value.sortNo,
        status,
        title: form.value.title.trim(),
      });
    }
    open.value = false;
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
    :title="title"
    :confirm-loading="submitting"
    :mask-closable="false"
    ok-text="保存"
    cancel-text="取消"
    :ok-button-props="{ disabled: errors.length > 0 }"
    @ok="submit('ENABLED')"
    @cancel="open = false"
  >
    <Alert
      v-if="errors.length > 0"
      type="error"
      show-icon
      class="mb-3"
      :message="errors.join('；')"
    />
    <Alert
      v-if="submitError"
      type="error"
      show-icon
      class="mb-3"
      :message="`保存失败：${submitError}`"
    />
    <Form layout="vertical">
      <FormItem label="类型" required>
        <RadioGroup
          v-model:value="form.type"
          :disabled="typeDisabled"
          @change="form.parentId = '0'"
        >
          <RadioButton value="DIR">目录</RadioButton>
          <RadioButton value="MENU">菜单</RadioButton>
          <RadioButton value="BUTTON">按钮</RadioButton>
        </RadioGroup>
      </FormItem>
      <FormItem label="标题" required>
        <Input v-model:value="form.title" :maxlength="64" @change="validate" />
      </FormItem>
      <FormItem label="所属模块" required>
        <Select
          v-model:value="form.moduleCode"
          :disabled="immutableInEdit"
          :options="modules.map((code) => ({ label: code, value: code }))"
        />
      </FormItem>
      <FormItem label="上级">
        <Select v-model:value="form.parentId" :options="parentOptions" />
      </FormItem>
      <template v-if="form.type === 'BUTTON'">
        <FormItem label="权限码（创建后不可改）" required>
          <Input
            v-model:value="form.permCode"
            :disabled="immutableInEdit"
            placeholder="如 followup:patient:export"
            @change="validate"
          />
        </FormItem>
      </template>
      <template v-else>
        <FormItem label="路由名（大驼峰）" required>
          <Input v-model:value="form.routeName" @change="validate" />
        </FormItem>
        <FormItem label="路径（以 / 开头）" required>
          <Input v-model:value="form.path" @change="validate" />
        </FormItem>
        <FormItem v-if="form.type === 'MENU'" label="前端视图路径" required>
          <Input
            v-model:value="form.component"
            placeholder="如 followup/patient/index"
            @change="validate"
          />
        </FormItem>
      </template>
      <FormItem label="图标（可选）">
        <Input v-model:value="form.icon" placeholder="如 MedicineBoxOutlined" />
      </FormItem>
      <FormItem label="排序">
        <InputNumber v-model:value="form.sortNo" :min="0" :max="999" />
      </FormItem>
      <FormItem label="隐藏（不进侧栏，仍可路由）">
        <Switch v-model:checked="form.hidden" />
      </FormItem>
      <FormItem v-if="!isCreate" label="状态">
        <RadioGroup v-model:value="form.status">
          <RadioButton value="ENABLED">启用</RadioButton>
          <RadioButton value="DISABLED">停用</RadioButton>
        </RadioGroup>
      </FormItem>
    </Form>
  </Modal>
</template>
