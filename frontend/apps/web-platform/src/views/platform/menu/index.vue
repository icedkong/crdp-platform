<script lang="ts" setup>
import type { MenuResourceNode, MenuSyncDiffItem } from '@crdp/api';

import { h, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { menuSourceText } from '@crdp/api';
import { breakpoints } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Empty,
  Modal,
  Popconfirm,
  RadioButton,
  RadioGroup,
  Result,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  applyMenuSync,
  deleteMenu,
  listMenuTree,
  menuSyncDiff,
} from '#/api/core/menu-resource';
import { listModules } from '#/api/core/module';
import { usePermission } from '#/reference/use-permission';

import EditDialog from './edit-dialog.vue';
import {
  applyResultText,
  diffLine,
  diffSummary,
  SIDE_OPTIONS,
  sideText,
  typeText,
} from './resource';

defineOptions({ name: 'PlatformMenu' });

const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:menu:edit');

const isMobile = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const side = ref<'HOSPITAL' | 'PLATFORM'>('PLATFORM');
const tree = ref<MenuResourceNode[]>([]);
const modules = ref<string[]>([]);

// 新建 / 维护对话框
const editOpen = ref(false);
const editTarget = ref<MenuResourceNode | null>(null);
const presetParentId = ref<null | number>(null);

// 清单同步（PLT-PERM-011）：先看差异，确认后应用
const syncOpen = ref(false);
const syncLoading = ref(false);
const syncItems = ref<MenuSyncDiffItem[]>([]);
const syncError = ref('');
const applying = ref(false);
const applyMessage = ref('');

const columns = [
  { dataIndex: 'title', title: '标题' },
  { dataIndex: 'type', title: '类型', width: 80 },
  { dataIndex: 'moduleCode', title: '所属模块', width: 110 },
  { dataIndex: 'permCode', title: '权限码', width: 210 },
  { dataIndex: 'routeName', title: '路由名', width: 150 },
  { dataIndex: 'path', title: '路径', width: 180 },
  { dataIndex: 'component', title: '视图', width: 180 },
  { dataIndex: 'sortNo', title: '排序', width: 70 },
  { dataIndex: 'source', title: '来源', width: 96 },
  { dataIndex: 'status', title: '状态', width: 80 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 176 },
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
    tree.value = await listMenuTree(side.value);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

async function loadModules() {
  try {
    const items = await listModules();
    modules.value = items.map((item) => item.code);
  } catch {
    modules.value = ['base'];
  }
}

function switchSide(value: 'HOSPITAL' | 'PLATFORM') {
  side.value = value;
  void load();
}

function openCreate(parentId: null | number = null) {
  editTarget.value = null;
  presetParentId.value = parentId;
  editOpen.value = true;
}

function openEdit(node: MenuResourceNode) {
  editTarget.value = node;
  presetParentId.value = null;
  editOpen.value = true;
}

async function remove(node: MenuResourceNode) {
  errorMessage.value = '';
  try {
    await deleteMenu(node.id);
    await load();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  }
}

async function openSync() {
  syncOpen.value = true;
  syncError.value = '';
  applyMessage.value = '';
  syncLoading.value = true;
  try {
    const result = await menuSyncDiff();
    syncItems.value = result.items;
  } catch (error) {
    syncError.value = error instanceof Error ? error.message : String(error);
    syncItems.value = [];
  } finally {
    syncLoading.value = false;
  }
}

async function applySync() {
  applying.value = true;
  syncError.value = '';
  try {
    const result = await applyMenuSync();
    applyMessage.value = applyResultText(result);
    const diff = await menuSyncDiff();
    syncItems.value = diff.items;
    await load();
  } catch (error) {
    syncError.value = error instanceof Error ? error.message : String(error);
  } finally {
    applying.value = false;
  }
}

function emptyNode() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () =>
    canEdit ? '暂无菜单项 · 新增或同步清单' : '暂无菜单项',
  );
}

onMounted(() => {
  read();
  window.addEventListener('resize', read);
  void load();
  void loadModules();
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
    title="菜单资源"
    description="平台端与医院端两棵全局菜单树：目录、菜单、按钮的维护、按钮权限码与清单同步。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <RadioGroup
          :value="side"
          size="small"
          @change="(event: any) => switchSide(event.target.value)"
        >
          <RadioButton
            v-for="option in SIDE_OPTIONS"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </RadioButton>
        </RadioGroup>
        <Space>
          <Button
            data-testid="menu-sync-diff"
            :disabled="!canEdit"
            @click="openSync"
          >
            清单同步
          </Button>
          <Button
            v-if="canEdit"
            type="primary"
            data-testid="menu-create"
            @click="openCreate(null)"
          >
            新增菜单项
          </Button>
        </Space>
      </div>

      <Alert
        v-if="errorMessage"
        :message="`操作失败：${errorMessage}`"
        show-icon
        type="error"
        class="mb-4"
      />

      <Table
        :columns="columns"
        :data-source="tree"
        :loading="loading"
        :pagination="false"
        row-key="id"
        :default-expand-all-rows="true"
        :locale="{ emptyText: emptyNode() }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'title'">
            <span :class="{ 'menu-disabled': record.status === 'DISABLED' }">
              {{ record.title }}
            </span>
            <Tag v-if="record.hidden" class="ml-1">隐藏</Tag>
          </template>
          <template v-else-if="column.dataIndex === 'type'">
            {{ typeText(record.type) }}
          </template>
          <template v-else-if="column.dataIndex === 'permCode'">
            <code v-if="record.permCode">{{ record.permCode }}</code>
            <span v-else>—</span>
          </template>
          <template v-else-if="column.dataIndex === 'routeName'">
            {{ record.routeName ?? '—' }}
          </template>
          <template v-else-if="column.dataIndex === 'path'">
            {{ record.path ?? '—' }}
          </template>
          <template v-else-if="column.dataIndex === 'component'">
            {{ record.component ?? '—' }}
          </template>
          <template v-else-if="column.dataIndex === 'source'">
            {{ menuSourceText(record.source) }}
          </template>
          <template v-else-if="column.dataIndex === 'status'">
            <Tag :color="record.status === 'ENABLED' ? 'success' : 'default'">
              {{ record.status === 'ENABLED' ? '启用' : '停用' }}
            </Tag>
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <Space :size="4">
              <Button
                v-if="canEdit && record.type !== 'BUTTON'"
                type="link"
                size="small"
                @click="openCreate(record.id)"
              >
                新增子项
              </Button>
              <Button
                v-if="canEdit"
                type="link"
                size="small"
                @click="openEdit(record as MenuResourceNode)"
              >
                维护
              </Button>
              <Popconfirm
                v-if="canEdit"
                :title="`删除「${record.title}」？将移除各端角色授予（软删除）`"
                ok-text="删除"
                cancel-text="取消"
                @confirm="remove(record as MenuResourceNode)"
              >
                <Button type="link" size="small" danger>删除</Button>
              </Popconfirm>
            </Space>
          </template>
        </template>
      </Table>
    </Card>

    <EditDialog
      v-model:open="editOpen"
      :modules="modules"
      :preset-parent-id="presetParentId"
      :target="editTarget"
      :tree="tree"
      @done="load()"
    />

    <Modal
      v-model:open="syncOpen"
      title="菜单清单同步（模块发版比对）"
      ok-text="确认应用"
      cancel-text="关闭"
      :confirm-loading="applying"
      :ok-button-props="{ disabled: syncItems.length === 0 }"
      @ok="applySync"
    >
      <Alert
        v-if="syncError"
        :message="`同步失败：${syncError}`"
        show-icon
        type="error"
        class="mb-3"
      />
      <Alert
        v-if="applyMessage"
        :message="applyMessage"
        show-icon
        type="success"
        class="mb-3"
      />
      <p v-if="syncLoading">正在比对清单差异…</p>
      <p v-else-if="syncItems.length === 0">
        没有差异：库内菜单与随包模块清单一致（{{ sideText(side) }}树不受影响）。
      </p>
      <template v-else>
        <p class="mb-2">
          模块发版清单与库内差异（{{
            diffSummary(syncItems)
          }}）。确认后生效：新增插入、
          变更改结构字段（标题、排序等平台端维护值不覆盖）、下线项停用。
        </p>
        <ul class="diff-list">
          <li v-for="item in syncItems" :key="`${item.action}-${item.key}`">
            {{ diffLine(item) }}
          </li>
        </ul>
      </template>
    </Modal>
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.menu-disabled {
  color: rgb(0 0 0 / 45%);
}

.diff-list {
  max-height: 260px;
  padding-left: 18px;
  margin: 0;
  overflow-y: auto;
}
</style>
