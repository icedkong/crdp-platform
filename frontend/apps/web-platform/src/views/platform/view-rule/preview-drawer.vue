<script lang="ts" setup>
import type {
  PlatformRoleView,
  TenantListItem,
  ViewRuleFieldEffect,
  ViewRuleModuleEffect,
  ViewRulePreviewResult,
} from '@crdp/api';

import { computed, ref, watch } from 'vue';

import { viewRuleDecidedByText, viewRuleEffectText } from '@crdp/api';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Drawer,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import { listRoles } from '#/api/core/role';
import { listTenants } from '#/api/core/tenant';
import { previewViewRules } from '#/api/core/view-rule';

import { validatePreviewModel } from './logic';

/**
 * 效果预览（E20-T02 / PLT-VIEW-009，验收「预览与实际执行一致」）：
 * 选平台角色（多选）+ 租户，查看各模块（页面）与各字段目录项的最终效果
 * （可见性 / 操作 / 允许导出与命中的规则）——预览与执行共用后端同一解析器。
 */
const props = defineProps<{ open: boolean }>();

const emit = defineEmits<{ (e: 'close'): void }>();

const tenantId = ref<null | number>(null);
const roleIds = ref<number[]>([]);
const tenantOptions = ref<{ label: string; value: number }[]>([]);
const roleOptions = ref<{ label: string; value: number }[]>([]);
const result = ref<null | ViewRulePreviewResult>(null);
const loading = ref(false);
const errorMessage = ref('');
const errors = ref<string[]>([]);

const canPreview = computed(() => errors.value.length === 0);

const moduleColumns = [
  { dataIndex: 'moduleName', title: '模块（页面）', width: 160 },
  { dataIndex: 'opened', title: '该院已开通', width: 100 },
  { dataIndex: 'effect', title: '最终效果' },
  { dataIndex: 'matched', title: '命中的规则', width: 260 },
];

const fieldColumns = [
  { dataIndex: 'label', title: '字段', width: 200 },
  { dataIndex: 'effect', title: '最终效果' },
  { dataIndex: 'matched', title: '命中的规则', width: 260 },
];

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    errorMessage.value = '';
    result.value = null;
    errors.value = [];
    try {
      const [roles, tenants] = await Promise.all([
        listRoles(),
        listTenants({ pageNo: 1, pageSize: 100 }),
      ]);
      roleOptions.value = roles.map((role: PlatformRoleView) => ({
        label: `${role.name}（${role.code}）`,
        value: role.id,
      }));
      tenantOptions.value = tenants.items.map((tenant: TenantListItem) => ({
        label: `${tenant.name}（${tenant.code}）`,
        value: tenant.id,
      }));
    } catch (error) {
      errorMessage.value =
        error instanceof Error ? error.message : String(error);
    }
  },
);

watch(
  [tenantId, roleIds],
  () => {
    errors.value = validatePreviewModel({
      roleIds: roleIds.value,
      tenantId: tenantId.value,
    });
  },
  { deep: true },
);

async function run() {
  if (!canPreview.value) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    result.value = await previewViewRules({
      roleIds: roleIds.value,
      tenantId: tenantId.value as number,
    });
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <Drawer
    :open="open"
    title="效果预览"
    :width="760"
    class="crdp-drawer"
    @close="emit('close')"
  >
    <Alert
      message="选择平台角色与租户，预览视角中的最终效果：按「字段 > 模块 > 租户 > 全局默认」匹配、同层取更严；预览与实际执行共用同一解析，结果即进入租户视角后的真实效果。"
      show-icon
      type="info"
      class="mb-4"
    />
    <Space class="mb-4" wrap>
      <Select
        v-model:value="tenantId"
        :options="tenantOptions"
        placeholder="选择租户"
        style="width: 260px"
        show-search
        option-filter-prop="label"
        data-testid="preview-tenant"
      />
      <Select
        v-model:value="roleIds"
        :options="roleOptions"
        placeholder="选择平台角色（可多选）"
        style="width: 320px"
        mode="multiple"
        show-search
        option-filter-prop="label"
        data-testid="preview-roles"
      />
      <Button
        type="primary"
        :disabled="!canPreview"
        :loading="loading"
        data-testid="preview-run"
        @click="run"
      >
        预览
      </Button>
    </Space>
    <div v-if="errors.length > 0" class="preview-errors">
      {{ errors.join('；') }}
    </div>
    <Alert
      v-if="errorMessage"
      :message="`预览失败：${errorMessage}`"
      show-icon
      type="error"
      class="mb-4"
    />

    <template v-if="result">
      <Descriptions :column="3" size="small" bordered class="mb-4">
        <DescriptionsItem label="租户">
          {{ result.tenantName }}（{{ result.tenantCode }}）
        </DescriptionsItem>
        <DescriptionsItem label="平台角色" :span="2">
          {{ result.roleCodes.join('、') || '—' }}
        </DescriptionsItem>
      </Descriptions>

      <Card :bordered="false" class="crdp-card mb-4" title="模块（页面）效果">
        <Table
          :columns="moduleColumns"
          :data-source="result.modules"
          :loading="loading"
          :pagination="false"
          :row-key="(row: ViewRuleModuleEffect) => row.moduleCode"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'moduleName'">
              {{ record.moduleName }}
              <span class="code">{{ record.moduleCode }}</span>
            </template>
            <Tag
              v-else-if="column.dataIndex === 'opened'"
              :color="record.opened ? 'green' : 'default'"
            >
              {{ record.opened ? '已开通' : '未开通' }}
            </Tag>
            <span v-else-if="column.dataIndex === 'effect'" class="effect">
              {{ viewRuleEffectText(record as ViewRuleModuleEffect) }}
            </span>
            <Tooltip
              v-else-if="column.dataIndex === 'matched'"
              placement="topLeft"
            >
              <template #title>
                决定：{{
                  viewRuleDecidedByText(
                    (record as ViewRuleModuleEffect).matched,
                  )
                }}
                <template
                  v-if="(record as ViewRuleModuleEffect).overridden.length > 0"
                >
                  （被覆盖：{{
                    viewRuleDecidedByText(
                      (record as ViewRuleModuleEffect).overridden,
                    )
                  }}）
                </template>
              </template>
              <span class="matched">
                {{
                  viewRuleDecidedByText(
                    (record as ViewRuleModuleEffect).matched,
                  )
                }}
              </span>
            </Tooltip>
          </template>
        </Table>
      </Card>

      <Card
        :bordered="false"
        class="crdp-card"
        title="字段效果（含脱敏与设盲）"
      >
        <Table
          :columns="fieldColumns"
          :data-source="result.fields"
          :loading="loading"
          :pagination="false"
          :row-key="
            (row: ViewRuleFieldEffect) => row.resource + (row.field ?? '')
          "
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'label'">
              {{ record.label }}
              <Tag>{{ record.resource }}</Tag>
            </template>
            <span v-else-if="column.dataIndex === 'effect'" class="effect">
              {{ viewRuleEffectText(record as ViewRuleFieldEffect) }}
            </span>
            <Tooltip
              v-else-if="column.dataIndex === 'matched'"
              placement="topLeft"
            >
              <template #title>
                决定：{{
                  viewRuleDecidedByText((record as ViewRuleFieldEffect).matched)
                }}
                <template
                  v-if="(record as ViewRuleFieldEffect).overridden.length > 0"
                >
                  （被覆盖：{{
                    viewRuleDecidedByText(
                      (record as ViewRuleFieldEffect).overridden,
                    )
                  }}）
                </template>
              </template>
              <span class="matched">
                {{
                  viewRuleDecidedByText((record as ViewRuleFieldEffect).matched)
                }}
              </span>
            </Tooltip>
          </template>
        </Table>
      </Card>
    </template>
  </Drawer>
</template>

<style scoped>
.preview-errors {
  margin-bottom: 12px;
  font-size: 13px;
  color: #ff4d4f;
}

.code {
  margin-left: 6px;
  font-family: monospace;
  font-size: 12px;
  color: #86909c;
}

.effect,
.matched {
  font-size: 13px;
}
</style>
