<script lang="ts" setup>
import type {
  ViewRuleCatalog,
  ViewRuleItem,
  ViewRuleVersionItem,
} from '@crdp/api';

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
  message,
  Modal,
  Result,
  Space,
  Table,
  Tag,
  Textarea,
  Tooltip,
} from 'ant-design-vue';

import {
  approveViewRuleReview,
  getViewRuleCatalog,
  listPendingViewRuleReviews,
  listViewRules,
  rejectViewRuleReview,
  rollbackViewRule,
} from '#/api/core/view-rule';
import { usePermission } from '#/reference/use-permission';

import EditDialog from './edit-dialog.vue';
import { buildLayerTabs, filterByLayer, pendingChangeText } from './logic';
import PreviewDrawer from './preview-drawer.vue';
import VersionDrawer from './version-drawer.vue';

defineOptions({ name: 'PlatformViewRule' });

const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:view:rule:edit');
const canReview = hasPermission('platform:view:rule:review');

const isMobile = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const items = ref<ViewRuleItem[]>([]);
const catalog = ref<null | ViewRuleCatalog>(null);

/** 左栏选中层级（null = 全部）。 */
const activeLayer = ref<null | string>(null);

// 待复核（PLT-VIEW-014：高敏变更需第二人批准）
const pending = ref<ViewRuleVersionItem[]>([]);
const reviewOpen = ref(false);
const reviewTarget = ref<null | ViewRuleVersionItem>(null);
const reviewApprove = ref(true);
const reviewComment = ref('');
const reviewSubmitting = ref(false);

// 编辑对话框（新建 / 维护）与效果预览抽屉
const editOpen = ref(false);
const editTarget = ref<null | ViewRuleItem>(null);
const previewOpen = ref(false);

// 版本记录抽屉（PLT-VIEW-015）
const versionTarget = ref<null | ViewRuleItem>(null);

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
  { dataIndex: 'status', title: '状态', width: 96 },
  { dataIndex: 'version', title: '版本', width: 70 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 170 },
];

const pendingColumns = [
  { dataIndex: 'ruleCode', title: '规则编号', width: 130 },
  { dataIndex: 'ruleName', title: '规则名称', width: 170 },
  { dataIndex: 'change', title: '变更内容（前 → 后）' },
  { dataIndex: 'proposedByName', title: '变更人', width: 110 },
  { dataIndex: 'proposedAt', title: '提交时间', width: 160 },
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
    const [rules, catalogData, pendingReviews] = await Promise.all([
      listViewRules(),
      getViewRuleCatalog(),
      listPendingViewRuleReviews().catch(() => [] as ViewRuleVersionItem[]),
    ]);
    items.value = rules;
    catalog.value = catalogData;
    pending.value = pendingReviews;
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

function openVersions(row: ViewRuleItem) {
  versionTarget.value = row;
}

/** 复核确认（同意 / 驳回共用对话框，意见可填 ≤500）。 */
function openReview(row: ViewRuleVersionItem, approve: boolean) {
  reviewTarget.value = row;
  reviewApprove.value = approve;
  reviewComment.value = '';
  reviewOpen.value = true;
}

async function submitReview() {
  if (!reviewTarget.value) return;
  reviewSubmitting.value = true;
  try {
    if (reviewApprove.value) {
      await approveViewRuleReview(reviewTarget.value.id, reviewComment.value);
      message.success('已同意，高敏变更生效');
    } else {
      await rejectViewRuleReview(reviewTarget.value.id, reviewComment.value);
      message.success('已驳回，规则保持原状');
    }
    reviewOpen.value = false;
    void load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    reviewSubmitting.value = false;
  }
}

async function submitRollback(row: ViewRuleItem, toVersion: number) {
  Modal.confirm({
    title: `回滚视角规则 ${row.code} 至 v${toVersion}？`,
    content:
      '以该历史版本的内容发起一次新变更；若回滚本身属放宽（如脱敏回滚到明文），同样需要另一名平台管理员复核。',
    okText: '回滚',
    cancelText: '取消',
    onOk: async () => {
      try {
        const outcome = await rollbackViewRule(row.id, toVersion);
        if (outcome.pendingReview) {
          message.info('回滚属高敏变更，已提交待复核（规则保持原状）');
        } else {
          message.success(
            `已回滚至 v${toVersion}（新版本 v${outcome.rule.version}）`,
          );
        }
        void load();
      } catch (error) {
        message.error(error instanceof Error ? error.message : String(error));
      }
    },
  });
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
    description="平台租户视角的脱敏、设盲、只读与导出规则：按「字段 > 模块 > 租户 > 全局默认」匹配，越具体越优先；同层命中多条（如账号有多个平台角色）按更严执行。规则由平台配置，医院不参与。高敏变更（改明文、取消设盲 / 只读、允许导出）需另一名平台管理员复核通过后生效。"
  >
    <!-- 待复核（PLT-VIEW-014，验收「高敏变更需第二人批准」） -->
    <Card
      v-if="canReview && pending.length > 0"
      :bordered="false"
      class="crdp-card mb-4"
      data-testid="view-rule-pending-card"
    >
      <template #title>
        待复核的高敏变更
        <Tag color="warning">{{ pending.length }}</Tag>
      </template>
      <div class="hint mb-2">
        将字段改为明文、取消设盲 /
        只读或允许导出的变更，复核通过前规则保持原状；发起人不能复核自己提交的变更。
      </div>
      <Table
        :columns="pendingColumns"
        :data-source="pending"
        :pagination="false"
        :row-key="(row: ViewRuleVersionItem) => row.id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.dataIndex === 'ruleCode'" class="code">
            {{ record.ruleCode }}（v{{ record.version }}）
          </span>
          <Tooltip v-else-if="column.dataIndex === 'change'">
            <template #title>
              {{ pendingChangeText(record as ViewRuleVersionItem) }}
            </template>
            <span class="change-cell">
              {{ pendingChangeText(record as ViewRuleVersionItem) }}
            </span>
          </Tooltip>
          <span v-else-if="column.dataIndex === 'proposedAt'">
            {{
              String(record.proposedAt ?? '')
                .slice(0, 19)
                .replace('T', ' ')
            }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0">
            <Button
              size="small"
              type="link"
              :data-testid="`view-rule-approve-${record.id}`"
              @click="openReview(record as ViewRuleVersionItem, true)"
            >
              同意
            </Button>
            <Button
              size="small"
              type="link"
              danger
              :data-testid="`view-rule-reject-${record.id}`"
              @click="openReview(record as ViewRuleVersionItem, false)"
            >
              驳回
            </Button>
          </Space>
        </template>
      </Table>
    </Card>

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
            生效中的规则即时生效；高敏变更经第二人复核通过后生效，进行中的视角会话在下一次请求按新规则执行。
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
              <Tag v-if="record.pendingChange" color="warning">待复核变更</Tag>
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
              <Button
                size="small"
                type="link"
                :data-testid="`view-rule-versions-${record.code}`"
                @click="openVersions(record as ViewRuleItem)"
              >
                版本记录
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
    <VersionDrawer
      :open="versionTarget !== null"
      :rule="versionTarget"
      :can-edit="canEdit"
      @close="versionTarget = null"
      @rollback="submitRollback"
    />

    <!-- 复核确认（同意 / 驳回共用） -->
    <Modal
      v-model:open="reviewOpen"
      :confirm-loading="reviewSubmitting"
      :ok-text="reviewApprove ? '同意并生效' : '驳回'"
      :title="`${reviewApprove ? '同意' : '驳回'}高敏变更：${reviewTarget?.ruleCode ?? ''} v${reviewTarget?.version ?? ''}`"
      :width="560"
      cancel-text="取消"
      @ok="submitReview"
    >
      <div v-if="reviewTarget" class="review-body">
        <p class="review-line">{{ pendingChangeText(reviewTarget) }}</p>
        <p class="hint">
          变更人：{{ reviewTarget.proposedByName ?? '—' }} · 提交于
          {{
            String(reviewTarget.proposedAt ?? '')
              .slice(0, 19)
              .replace('T', ' ')
          }}
        </p>
        <p v-if="reviewApprove" class="hint">
          同意后按上述内容写入规则行并立即生效；驳回则规则保持原状。
        </p>
      </div>
      <Textarea
        v-model:value="reviewComment"
        :maxlength="500"
        :rows="3"
        placeholder="复核意见（可空，≤500 字）"
        show-count
      />
    </Modal>
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

.change-cell {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  white-space: nowrap;
}

.review-body .review-line {
  margin: 0 0 8px;
}

.review-body .hint {
  margin: 0 0 8px;
}

.mb-2 {
  margin-bottom: 8px;
}

.mb-4 {
  margin-bottom: 16px;
}
</style>
