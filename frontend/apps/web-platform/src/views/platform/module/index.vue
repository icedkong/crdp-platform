<script lang="ts" setup>
import type { ModuleCatalogItem } from '@crdp/api';

import { h, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { moduleSyncActionText } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { breakpoints } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Empty,
  Result,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import { listModules, syncModules } from '#/api/core/module';
import { usePermission } from '#/reference/use-permission';

import {
  baseModuleText,
  canTakeOffShelf,
  dependsOnText,
  menuSideText,
  menuTypeText,
  shelfStatusText,
  syncedAtText,
  syncOutcomeLine,
  versionText,
} from './catalog';
import EditDialog from './edit-dialog.vue';

defineOptions({ name: 'PlatformModule' });

const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:module:edit');

const isMobile = ref(false);
const loading = ref(false);
const items = ref<ModuleCatalogItem[]>([]);
const errorMessage = ref('');

// 同步（PLT-MOD-002）：与启动时同一套逻辑；幂等，无变化不写版本记录
const syncing = ref(false);
const syncMessage = ref('');
const syncError = ref('');

// 维护对话框（PLT-MOD-002）：展示信息与上下架
const editOpen = ref(false);
const editTarget = ref<ModuleCatalogItem | null>(null);

const columns = [
  { dataIndex: 'code', title: '模块编码', width: 110 },
  { dataIndex: 'name', title: '名称' },
  { dataIndex: 'description', title: '说明' },
  { dataIndex: 'baseModule', title: '基础', width: 72 },
  { dataIndex: 'status', title: '上架状态', width: 96 },
  { dataIndex: 'version', title: '功能 / 迁移版本', width: 150 },
  { dataIndex: 'dependsOn', title: '依赖', width: 110 },
  { dataIndex: 'syncedAt', title: '同步时间', width: 140 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 88 },
];

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    items.value = await listModules();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

async function sync() {
  syncing.value = true;
  syncMessage.value = '';
  syncError.value = '';
  try {
    const result = await syncModules();
    syncMessage.value = result.outcomes
      .map((outcome) => syncOutcomeLine(outcome))
      .join('；');
    await load();
  } catch (error) {
    syncError.value = error instanceof Error ? error.message : String(error);
  } finally {
    syncing.value = false;
  }
}

function openEdit(row: ModuleCatalogItem) {
  editTarget.value = row;
  editOpen.value = true;
}

function onEditDone() {
  void load();
}

function emptyNode() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () =>
    canEdit ? '暂无模块 · 去同步' : '暂无模块',
  );
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
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page
    v-else
    title="模块目录"
    description="随发版注册的平台模块：版本、依赖、菜单与权限码清单、配置项与版本历史。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <span class="hint">
          模块由代码随发版登记并自动注册，这里只维护展示信息与上下架（PLT-MOD-002）。
        </span>
        <Button
          v-if="canEdit"
          type="primary"
          data-testid="module-sync"
          :loading="syncing"
          @click="sync"
        >
          立即同步
        </Button>
      </div>

      <Alert
        v-if="errorMessage"
        :message="`加载失败：${errorMessage}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        v-if="syncError"
        :message="`同步失败：${syncError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        v-if="syncMessage"
        :message="`同步完成：${syncMessage}（${moduleSyncActionText('UNCHANGED')}＝与启动时一致）`"
        show-icon
        type="success"
        class="mb-4"
      />

      <Table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        :locale="{ emptyText: emptyNode() }"
        :pagination="false"
        :row-key="(row: ModuleCatalogItem) => row.code"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.dataIndex === 'code'" class="code">
            {{ record.code }}
          </span>
          <span v-else-if="column.dataIndex === 'description'">
            {{ record.description || '—' }}
          </span>
          <span v-else-if="column.dataIndex === 'baseModule'">
            {{ baseModuleText(record.baseModule) }}
          </span>
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="shelfStatusText(record.status)"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'version'">
            {{ versionText(record as ModuleCatalogItem) }}
          </span>
          <span v-else-if="column.dataIndex === 'dependsOn'">
            {{ dependsOnText(record.dependsOn) }}
          </span>
          <span v-else-if="column.dataIndex === 'syncedAt'">
            {{ syncedAtText(record as ModuleCatalogItem) }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
            <Button
              v-if="canEdit"
              size="small"
              type="link"
              data-testid="module-edit"
              @click="openEdit(record as ModuleCatalogItem)"
            >
              维护
            </Button>
            <span v-else>—</span>
          </Space>
        </template>

        <template #expandedRowRender="{ record }">
          <div class="detail" :data-testid="`module-detail-${record.code}`">
            <div class="section">
              <h4>菜单清单</h4>
              <span v-if="record.menus.length === 0">—</span>
              <Tag v-for="menu in record.menus" :key="menu.id">
                {{ menuSideText(menu.side) }} · {{ menuTypeText(menu.type) }} ·
                {{ menu.title }}
              </Tag>
            </div>
            <div class="section">
              <h4>权限码清单</h4>
              <span v-if="record.permissions.length === 0">—</span>
              <Tag v-for="code in record.permissions" :key="code">
                {{ code }}
              </Tag>
            </div>
            <div class="section">
              <h4>配置项</h4>
              <span v-if="record.configItems.length === 0">—</span>
              <Tag v-for="item in record.configItems" :key="item.code">
                {{ item.name }}（{{ item.code }}，默认 {{ item.defaultValue }}）
              </Tag>
            </div>
            <div class="section">
              <h4>版本历史</h4>
              <span v-if="record.versions.length === 0">—</span>
              <ul v-else class="versions">
                <li v-for="version in record.versions" :key="version.createdAt">
                  <span class="version-no">
                    功能 {{ version.featureVersion }} / 迁移
                    {{ version.migrationVersion }}
                  </span>
                  <span class="version-date">
                    {{ version.createdAt.slice(0, 16).replace('T', ' ') }}
                  </span>
                  <span class="version-note">{{ version.changeNote }}</span>
                </li>
              </ul>
            </div>
          </div>
        </template>
      </Table>
    </Card>

    <EditDialog
      v-model:open="editOpen"
      :module-item="editTarget"
      :can-take-off-shelf="editTarget ? canTakeOffShelf(editTarget) : false"
      @done="onEditDone"
    />
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.hint {
  font-size: 13px;
  color: #86909c;
}

.code {
  font-family: monospace;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 4px 8px;
}

.detail .section h4 {
  margin: 0 0 4px;
  font-weight: 600;
}

.versions {
  padding-left: 18px;
  margin: 0;
}

.versions li {
  margin-bottom: 4px;
}

.version-no {
  margin-right: 12px;
  font-weight: 600;
}

.version-date {
  margin-right: 12px;
  color: #86909c;
}
</style>
