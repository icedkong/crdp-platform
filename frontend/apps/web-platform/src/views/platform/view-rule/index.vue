<script lang="ts" setup>
import type { ViewRuleCatalog, ViewRuleItem } from '@crdp/api';

import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  viewRuleEffectText,
  viewRuleLayerText,
  viewRuleRolesText,
  viewRuleScopeText,
  viewRuleStatusText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { breakpoints } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Result,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import { getViewRuleCatalog, listViewRules } from '#/api/core/view-rule';
import { usePermission } from '#/reference/use-permission';

import EditDialog from './edit-dialog.vue';
import { buildLayerTabs, filterByLayer } from './logic';
import PreviewDrawer from './preview-drawer.vue';

defineOptions({ name: 'PlatformViewRule' });

const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:view:rule:edit');

const isMobile = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const items = ref<ViewRuleItem[]>([]);
const catalog = ref<null | ViewRuleCatalog>(null);

/** 左栏选中层级（null = 全部）。 */
const activeLayer = ref<null | string>(null);

// 编辑对话框（新建 / 维护）与效果预览抽屉
const editOpen = ref(false);
const editTarget = ref<null | ViewRuleItem>(null);
const previewOpen = ref(false);

const tabs = computed(() => buildLayerTabs(items.value));
const filtered = computed(() =>
  filterByLayer(items.value, activeLayer.value as never),
);

const columns = [
  { dataIndex: 'code', title: '编号', width: 150 },
  { dataIndex: 'name', title: '名称', width: 200 },
  { dataIndex: 'scope', title: '作用范围' },
  { dataIndex: 'roleCodes', title: '适用角色', width: 170 },
  { dataIndex: 'effect', title: '效果', width: 210 },
  { dataIndex: 'status', title: '状态', width: 84 },
  { dataIndex: 'version', title: '版本', width: 70 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 130 },
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
    [items.value, catalog.value] = await Promise.all([
      listViewRules(),
      getViewRuleCatalog(),
    ]);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editTarget.value = null;
  editOpen.value = true;
}

function openEdit(row: ViewRuleItem) {
  editTarget.value = row;
  editOpen.value = true;
}

function onEditDone() {
  void load();
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
    title="视角规则"
    description="平台租户视角的脱敏、设盲、只读与导出规则：按「字段 > 模块 > 租户 > 全局默认」匹配，越具体越优先；同层命中多条（如账号有多个平台角色）按更严执行。规则由平台配置，医院不参与。"
  >
    <div class="view-rule-layout">
      <Card :bordered="false" class="crdp-card layer-card" title="规则层级">
        <div
          v-for="tab in tabs"
          :key="String(tab.value)"
          class="layer-item"
          :class="{ active: activeLayer === tab.value }"
          :data-testid="`view-rule-layer-${tab.value ?? 'ALL'}`"
          @click="activeLayer = tab.value"
        >
          <div class="layer-head">
            <span class="layer-label">{{ tab.label }}</span>
            <span class="layer-count">{{ tab.count }}</span>
          </div>
          <div class="layer-hint">{{ tab.hint }}</div>
        </div>
        <div class="layer-note">
          匹配优先级：字段 &gt; 模块 &gt; 租户 &gt; 全局默认；同层取更严（隐藏
          &gt; 设盲 &gt; 脱敏 &gt; 明文、只读 &gt; 可编辑、禁止导出 &gt;
          允许导出）。
        </div>
      </Card>

      <Card :bordered="false" class="crdp-card rule-card">
        <div class="title-row">
          <span class="hint">
            生效中的规则即时生效；进行中的视角会话在下一次请求按新规则执行。
          </span>
          <Space>
            <Button data-testid="view-rule-preview" @click="previewOpen = true">
              效果预览
            </Button>
            <Button
              v-if="canEdit"
              type="primary"
              data-testid="view-rule-create"
              @click="openCreate"
            >
              新建规则
            </Button>
          </Space>
        </div>

        <Alert
          v-if="errorMessage"
          :message="`加载失败：${errorMessage}`"
          show-icon
          type="error"
          class="mb-4"
        />

        <Table
          :columns="columns"
          :data-source="filtered"
          :loading="loading"
          :pagination="false"
          :row-key="(row: ViewRuleItem) => row.id"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <span v-if="column.dataIndex === 'code'" class="code">
              {{ record.code }}
            </span>
            <template v-else-if="column.dataIndex === 'name'">
              {{ record.name }}
              <Tag v-if="record.layer !== 'GLOBAL'">
                {{ viewRuleLayerText(record.layer) }}
              </Tag>
            </template>
            <span v-else-if="column.dataIndex === 'scope'" class="scope">
              {{ viewRuleScopeText(record as ViewRuleItem) }}
            </span>
            <span v-else-if="column.dataIndex === 'roleCodes'">
              {{ viewRuleRolesText(record as ViewRuleItem) }}
            </span>
            <Tooltip v-else-if="column.dataIndex === 'effect'">
              <template #title>
                该规则配置的效果；实际生效按层级匹配，以「效果预览」为准
              </template>
              <span class="effect">
                {{ viewRuleEffectText(record as ViewRuleItem) }}
              </span>
            </Tooltip>
            <StatusTag
              v-else-if="column.dataIndex === 'status'"
              :status="viewRuleStatusText(record.status)"
              variant="dot"
            />
            <span v-else-if="column.dataIndex === 'version'">
              v{{ record.version }}
            </span>
            <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
              <Button
                v-if="canEdit"
                size="small"
                type="link"
                :data-testid="`view-rule-edit-${record.code}`"
                @click="openEdit(record as ViewRuleItem)"
              >
                维护
              </Button>
            </Space>
          </template>
        </Table>
      </Card>
    </div>

    <EditDialog
      v-model:open="editOpen"
      :catalog="catalog"
      :rule="editTarget"
      @done="onEditDone"
    />
    <PreviewDrawer :open="previewOpen" @close="previewOpen = false" />
  </Page>
</template>

<style scoped>
.view-rule-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.layer-card {
  flex: 0 0 240px;
}

.rule-card {
  flex: 1;
  min-width: 0;
}

.layer-item {
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  border: 1px solid #e5e6eb;
  border-radius: 6px;
  transition: all 0.2s;
}

.layer-item.active {
  background: rgb(58 117 255 / 6%);
  border-color: #3a75ff;
}

.layer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.layer-label {
  font-weight: 500;
}

.layer-count {
  font-size: 12px;
  color: #86909c;
}

.layer-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #86909c;
}

.layer-note {
  padding-top: 8px;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #86909c;
  border-top: 1px solid #e5e6eb;
}

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

.scope,
.effect {
  font-size: 13px;
}
</style>
