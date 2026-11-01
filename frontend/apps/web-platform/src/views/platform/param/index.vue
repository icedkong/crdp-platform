<script lang="ts" setup>
import type { PlatformParamView } from '@crdp/api';

import type { ParamEditModel } from './param';

import { h, onMounted, onUnmounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { paramRangeText, paramValueTypeText } from '@crdp/api';
import { breakpoints } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Result,
  Select,
  Table,
  Textarea,
} from 'ant-design-vue';

import { createParam, listParams, updateParam } from '#/api/core/param';

import {
  emptyParamModel,
  levelText,
  PARAM_VALUE_TYPES,
  rangePlaceholder,
  toParamModel,
  validateParamModel,
  valuePlaceholder,
} from './param';

defineOptions({ name: 'PlatformParam' });

/**
 * E06-T10 / PLT-CFG-003 · pages.md §11「系统参数」（/platform/param，platform:param:edit）：
 * 参数键创建后不可改；层级（平台锁定 / 租户可覆盖）与取值范围；平台默认值过类型与范围校验。
 * 改为锁定后租户已覆盖的值即时失效并提示（PLT-CFG-011）。仅桌面 / 平板。
 */
const isMobile = ref(false);

const loading = ref(false);
const loadError = ref('');
const params = ref<PlatformParamView[]>([]);

const dialogOpen = ref(false);
const creating = ref(true);
const saving = ref(false);
const form = reactive<ParamEditModel>(emptyParamModel());

const columns = [
  { dataIndex: 'paramKey', title: '参数键' },
  { dataIndex: 'name', title: '名称' },
  { dataIndex: 'valueType', title: '值类型', width: 90 },
  { dataIndex: 'platformValue', title: '平台默认值' },
  { dataIndex: 'tenantOverridable', title: '层级', width: 110 },
  { dataIndex: 'valueRange', title: '取值范围' },
  { dataIndex: 'action', title: '操作', width: 88 },
];

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    params.value = await listParams();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  creating.value = true;
  Object.assign(form, emptyParamModel());
  dialogOpen.value = true;
}

function openEdit(row: PlatformParamView) {
  creating.value = false;
  Object.assign(form, toParamModel(row));
  dialogOpen.value = true;
}

async function submit() {
  const errors = validateParamModel(form, creating.value);
  if (errors.length > 0) {
    message.warning(errors[0]);
    return;
  }
  saving.value = true;
  try {
    const range = form.valueRange.trim() || null;
    await (creating.value
      ? createParam({
          description: form.description.trim() || null,
          name: form.name.trim(),
          paramKey: form.paramKey.trim(),
          platformValue: form.platformValue.trim(),
          tenantOverridable: form.tenantOverridable,
          valueRange: range,
          valueType: form.valueType,
        })
      : updateParam(form.paramKey, {
          description: form.description.trim() || null,
          name: form.name.trim(),
          platformValue: form.platformValue.trim(),
          tenantOverridable: form.tenantOverridable,
          valueRange: range,
          valueType: form.valueType,
        }));
    dialogOpen.value = false;
    message.success('已保存，全部租户即时生效');
    await load();
  } finally {
    saving.value = false;
  }
}

function emptyNode() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () => '暂无参数');
}

onMounted(() => {
  read();
  window.addEventListener('resize', read);
  void load();
});
onUnmounted(() => window.removeEventListener('resize', read));
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="$router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page
    v-else
    title="系统参数"
    description="平台级参数：值类型、平台默认值、层级（平台锁定 / 租户可覆盖）与取值范围。租户只能在范围内覆盖；参数键创建后不可改。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <span class="hint">
          改为「平台锁定」后，租户已覆盖的值即时失效并提示；覆盖行保留，重新放开自动恢复。
        </span>
        <Button type="primary" data-testid="param-create" @click="openCreate">
          新建参数
        </Button>
      </div>

      <Alert
        v-if="loadError"
        class="mb-4"
        :message="`加载失败：${loadError}`"
        show-icon
        type="error"
      />
      <Table
        :columns="columns"
        :data-source="params"
        :loading="loading"
        :pagination="false"
        row-key="paramKey"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'valueType'">
            {{ paramValueTypeText((record as PlatformParamView).valueType) }}
          </template>
          <template v-else-if="column.dataIndex === 'tenantOverridable'">
            {{ levelText((record as PlatformParamView).tenantOverridable) }}
          </template>
          <template v-else-if="column.dataIndex === 'valueRange'">
            {{
              paramRangeText(
                (record as PlatformParamView).valueType,
                (record as PlatformParamView).valueRange,
              )
            }}
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <Button
              size="small"
              type="link"
              @click="openEdit(record as PlatformParamView)"
            >
              维护
            </Button>
          </template>
        </template>
        <template #empty-text>
          <component :is="emptyNode" />
        </template>
      </Table>
    </Card>

    <Modal
      v-model:open="dialogOpen"
      :title="creating ? '新建系统参数' : `维护参数 · ${form.paramKey}`"
      :confirm-loading="saving"
      :ok-text="creating ? '创建' : '保存'"
      cancel-text="取消"
      :width="640"
      @ok="submit"
    >
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem label="参数键（创建后不可改，全局唯一）" required>
          <Input
            v-model:value="form.paramKey"
            :disabled="!creating"
            placeholder="如 tenant.db.pool.max"
            data-testid="param-key"
          />
        </FormItem>
        <FormItem label="名称" required>
          <Input
            v-model:value="form.name"
            :maxlength="128"
            placeholder="如 每租户连接池上限"
          />
        </FormItem>
        <FormItem label="说明">
          <Textarea
            v-model:value="form.description"
            :maxlength="255"
            :rows="2"
            placeholder="用途说明，可留空"
          />
        </FormItem>
        <FormItem label="值类型" required>
          <Select
            v-model:value="form.valueType"
            :options="
              PARAM_VALUE_TYPES.map((type) => ({
                value: type,
                label: paramValueTypeText(type),
              }))
            "
          />
        </FormItem>
        <FormItem label="平台默认值" required>
          <Input
            v-model:value="form.platformValue"
            :placeholder="
              valuePlaceholder(form.valueType, form.valueRange.trim() || null)
            "
            data-testid="param-value"
          />
        </FormItem>
        <FormItem>
          <Checkbox v-model:checked="form.tenantOverridable">
            允许租户覆盖（层级 = 租户可覆盖；取消勾选即平台锁定）
          </Checkbox>
        </FormItem>
        <FormItem
          label="取值范围（JSON）"
          :extra="`可覆盖参数必填（布尔 / JSON 除外）；${rangePlaceholder(form.valueType)}`"
        >
          <Input
            v-model:value="form.valueRange"
            :disabled="
              form.valueType === 'BOOLEAN' || form.valueType === 'JSON'
            "
            :placeholder="rangePlaceholder(form.valueType)"
          />
        </FormItem>
      </Form>
    </Modal>
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  color: #8c8c8c;
}
</style>
