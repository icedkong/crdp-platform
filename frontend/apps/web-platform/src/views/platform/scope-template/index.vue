<script lang="ts" setup>
import type { ScopeTemplateView } from '@crdp/api';

import type { SensitivePermRowModel, TemplateChecked } from './logic';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  Space,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  listScopeTemplate,
  updateScopeTemplate,
  updateSensitivePerms,
} from '#/api/core/tenant-scope';
import { usePermission } from '#/reference/use-permission';

import {
  initialChecked,
  toSensitiveRows,
  toTemplateRows,
  toUpdateRequest,
  validateSensitiveRows,
} from './logic';

/**
 * 授权范围模板（E05-T07 / PLT-SCOPE-003、004）：按模块预置默认可分配项（白名单，
 * 清单同步新增的菜单项不在模板内即默认不可分配），与敏感权限清单（默认不可分配，
 * 命中的按钮只能按租户单独放开）。模板只改默认值；租户角色的收回走租户级范围变更。
 */
defineOptions({ name: 'PlatformScopeTemplate' });

const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:scope:template:edit');

const view = ref<null | ScopeTemplateView>(null);
const loading = ref(false);
const errorMessage = ref('');
const templateError = ref('');
const sensitiveError = ref('');
const saving = reactive<Record<string, boolean>>({});

// 模板勾选输入态：模块编码 → { menuId → 是否在模板 }
const checkedByModule = ref<Record<string, TemplateChecked>>({});

// 敏感清单编辑行（整表替换保存）
const sensitiveRows = ref<SensitivePermRowModel[]>([]);

const groups = computed(() =>
  view.value
    ? view.value.modules.map((module) => ({
        ...module,
        rows: toTemplateRows(module),
      }))
    : [],
);

const sensitiveErrors = computed(() =>
  validateSensitiveRows(sensitiveRows.value),
);

const columns = [
  { title: '菜单 / 按钮', dataIndex: 'title', key: 'title' },
  { title: '权限码', dataIndex: 'permCode', key: 'permCode', width: 220 },
  { title: '说明', key: 'hint', width: 260 },
];

const permColumns = [
  { title: '权限码', key: 'permCode', width: 240 },
  { title: '说明', key: 'name', width: 320 },
  { title: '操作', key: 'actions', width: 90 },
];

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const data = await listScopeTemplate();
    view.value = data;
    checkedByModule.value = initialChecked(data);
    sensitiveRows.value = toSensitiveRows(data.sensitivePerms);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function toggleTemplate(moduleCode: string, menuId: number, value: boolean) {
  const state = checkedByModule.value[moduleCode];
  if (!state) return;
  state[menuId] = value;
}

async function saveModule(moduleCode: string) {
  const state = checkedByModule.value[moduleCode];
  if (!state) return;
  saving[moduleCode] = true;
  templateError.value = '';
  try {
    view.value = await updateScopeTemplate(moduleCode, toUpdateRequest(state));
  } catch (error) {
    templateError.value =
      error instanceof Error ? error.message : String(error);
  } finally {
    saving[moduleCode] = false;
  }
}

function addSensitiveRow() {
  sensitiveRows.value.push({ name: '', permCode: '' });
}

function removeSensitiveRow(index: number) {
  sensitiveRows.value.splice(index, 1);
}

async function saveSensitivePerms() {
  if (Object.keys(sensitiveErrors.value).length > 0) return;
  saving.sensitive = true;
  sensitiveError.value = '';
  try {
    view.value = await updateSensitivePerms({
      items: sensitiveRows.value.map((row) => ({
        name: row.name.trim(),
        permCode: row.permCode.trim(),
      })),
    });
    sensitiveRows.value = toSensitiveRows(view.value.sensitivePerms);
  } catch (error) {
    sensitiveError.value =
      error instanceof Error ? error.message : String(error);
  } finally {
    saving.sensitive = false;
  }
}

onMounted(load);
</script>

<template>
  <Page
    title="授权范围模板"
    description="按模块预置默认可分配项（新租户与无覆盖租户取模板默认）；敏感权限默认不可分配，只能按租户单独放开。"
  >
    <Card :bordered="false" class="crdp-card">
      <Alert
        message="模板只改默认值：模块开通、清单同步新增的菜单项按模板决定是否默认进入可分配范围；已开通租户的存量角色收回须在租户详情 · 授权范围里逐租户确认。"
        show-icon
        type="info"
        class="mb-4"
      />
      <Alert
        v-if="errorMessage"
        :message="`加载失败：${errorMessage}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Spin :spinning="loading">
        <div v-for="group in groups" :key="group.code" class="group">
          <div class="group-title">
            <span>{{ group.name }}</span>
            <Tag v-if="group.baseModule" color="blue">固定能力（不进模板）</Tag>
            <Button
              v-if="canEdit && !group.baseModule"
              :loading="saving[group.code]"
              size="small"
              type="primary"
              :data-testid="`save-template-${group.code}`"
              @click="saveModule(group.code)"
            >
              保存模板
            </Button>
          </div>
          <Alert
            v-if="group.baseModule"
            message="平台基础模块（组织、用户、角色管理）是固定能力，恒可分配、不受模板控制。"
            show-icon
            type="info"
            class="mb-2"
          />
          <Table
            v-else
            :columns="columns"
            :data-source="group.rows"
            :pagination="false"
            row-key="menuId"
            size="small"
            default-expand-all-rows
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'title'">
                <Checkbox
                  :checked="checkedByModule[group.code]?.[record.menuId]"
                  :disabled="record.disabled || !canEdit"
                  @change="
                    (event: any) =>
                      toggleTemplate(
                        group.code,
                        record.menuId,
                        event.target.checked,
                      )
                  "
                >
                  {{ record.title }}
                </Checkbox>
              </template>
              <template v-else-if="column.key === 'permCode'">
                <code v-if="record.permCode">{{ record.permCode }}</code>
                <span v-else>—</span>
              </template>
              <template v-else-if="column.key === 'hint'">
                <Tag v-if="record.sensitive" color="red">
                  敏感（不能预置进模板）
                </Tag>
                <span v-else-if="checkedByModule[group.code]?.[record.menuId]">
                  默认可分配
                </span>
                <span v-else class="hint">默认不进入可分配范围</span>
              </template>
            </template>
          </Table>
        </div>
        <Alert
          v-if="templateError"
          :message="`保存模板失败：${templateError}`"
          show-icon
          type="error"
          class="mb-4"
        />

        <div class="group">
          <div class="group-title">
            <span>敏感权限清单</span>
            <Space v-if="canEdit">
              <Button size="small" @click="addSensitiveRow">新增</Button>
              <Button
                :loading="saving.sensitive"
                size="small"
                type="primary"
                :disabled="Object.keys(sensitiveErrors).length > 0"
                data-testid="save-sensitive-perms"
                @click="saveSensitivePerms"
              >
                保存清单
              </Button>
            </Space>
          </div>
          <Alert
            v-if="sensitiveError"
            :message="`保存清单失败：${sensitiveError}`"
            show-icon
            type="error"
            class="mb-2"
          />
          <Table
            :columns="permColumns"
            :data-source="sensitiveRows"
            :pagination="false"
            row-key="permCode"
            size="small"
          >
            <template #bodyCell="{ column, record, index }">
              <template v-if="column.key === 'permCode'">
                <Input
                  v-model:value="record.permCode"
                  :disabled="!canEdit"
                  placeholder="followup:patient:plain"
                />
                <span
                  v-if="sensitiveErrors[index] && record.permCode"
                  class="error"
                >
                  {{ sensitiveErrors[index] }}
                </span>
              </template>
              <template v-else-if="column.key === 'name'">
                <Input
                  v-model:value="record.name"
                  :disabled="!canEdit"
                  placeholder="如：敏感字段明文查看"
                />
              </template>
              <template v-else-if="column.key === 'actions'">
                <Button
                  v-if="canEdit"
                  danger
                  size="small"
                  type="link"
                  @click="removeSensitiveRow(index)"
                >
                  移除
                </Button>
              </template>
            </template>
          </Table>
          <p class="hint">
            未绑定菜单的权限码可先登记（如 rct
            模块后续项），命中按钮后默认不可分配；放开须在租户详情 ·
            授权范围逐租户进行并填写原因。
          </p>
        </div>
      </Spin>
    </Card>
  </Page>
</template>

<style scoped>
.group {
  margin-bottom: 16px;
}

.group-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 500;
}

.hint {
  font-size: 12px;
  color: #86909c;
}

.error {
  display: block;
  font-size: 12px;
  color: #ff4d4f;
}

.mb-2 {
  margin-bottom: 8px;
}
</style>
