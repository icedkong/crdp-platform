<script lang="ts" setup>
import type { DashboardRoleCard } from '@crdp/api';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { moveRoleCard, roleLayoutSaveOf, toggleRoleCard } from '@crdp/api';
import {
  Alert,
  Button,
  Checkbox,
  message,
  Modal,
  Result,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
} from 'ant-design-vue';

import {
  fetchDashboardRoleLayout,
  resetDashboardRoleLayout,
  saveDashboardRoleLayout,
} from '#/api/core/dashboard';
import { useBreakpoint } from '#/reference/use-breakpoint';

import { visibleRoleCards } from './logic';

defineOptions({ name: 'FollowupDashboardLayout' });

/**
 * E19-T06 / A-DASH-013 · 首页卡片配置（/system/dashboard-layout，pages.md §10「选角色 →
 * 左卡片目录勾选 / 右顺序上下移 / 预览 / 恢复默认」；权限 followup:dashboard:config，
 * 平台库 V60 建菜单、种子 V130 授 TENANT_ADMIN）。配置只决定显示与顺序，不放宽数据
 * 权限：每张卡片照常受卡片权限与病区隔离限制（角色无卡片权限或功能开关关闭时，
 * 该卡片对该角色成员不出现——目录行标记「开关未开启」）。保存全量覆盖、下一次进入
 * 首页即生效（读取实时查库，无缓存）。
 *
 * 「允许用户隐藏」逐卡可设（A 规格 §9 关键字段，默认允许）：关闭后该角色成员不能在
 * 首页「卡片设置」中隐藏此卡片（A-DASH-014 的前置）。仅桌面 / 平板。
 */
const { isMobile } = useBreakpoint();

const loading = ref(false);
const loadError = ref('');
const saving = ref(false);

const roles = ref<Array<{ label: string; value: number }>>([]);
const roleId = ref<number | undefined>();
const roleName = ref('');
const configured = ref(false);
const cards = ref<DashboardRoleCard[]>([]);
/** 保存中未落库的本地编辑态（目录顺序 = 已选在前、未选在后）。 */
const dirty = ref(false);

const selectedCards = computed(() => visibleRoleCards(cards.value));

async function loadRoleView(target?: number) {
  loading.value = true;
  loadError.value = '';
  try {
    const view = await fetchDashboardRoleLayout(target);
    roles.value = view.roles.map((role) => ({
      label: role.name,
      value: role.id,
    }));
    roleId.value = view.current.roleId;
    roleName.value = view.current.roleName;
    configured.value = view.current.configured;
    cards.value = [...view.current.cards];
    dirty.value = false;
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function handleRoleChange(value: number) {
  void loadRoleView(value);
}

function toggleCard(code: string, visible: boolean) {
  cards.value = toggleRoleCard(cards.value, code, visible);
  dirty.value = true;
}

function move(index: number, direction: -1 | 1) {
  cards.value = moveRoleCard(cards.value, index, direction);
  dirty.value = true;
}

function toggleAllowHide(card: DashboardRoleCard, allowUserHide: boolean) {
  cards.value = cards.value.map((item) =>
    item.code === card.code ? { ...item, allowUserHide } : item,
  );
  dirty.value = true;
}

async function handleSave() {
  if (roleId.value === undefined) {
    return;
  }
  saving.value = true;
  try {
    const view = await saveDashboardRoleLayout(
      roleId.value,
      roleLayoutSaveOf(cards.value),
    );
    roleName.value = view.current.roleName;
    configured.value = view.current.configured;
    cards.value = [...view.current.cards];
    dirty.value = false;
    message.success(
      `已保存「${roleName.value}」的首页卡片布局，成员下次进入首页即生效`,
    );
  } catch {
    message.error('保存失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

async function handleReset() {
  if (roleId.value === undefined) {
    return;
  }
  saving.value = true;
  try {
    const view = await resetDashboardRoleLayout(roleId.value);
    roleName.value = view.current.roleName;
    configured.value = view.current.configured;
    cards.value = [...view.current.cards];
    dirty.value = false;
    message.success(`已恢复「${roleName.value}」的默认布局`);
  } catch {
    message.error('恢复默认失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

// ---------------------------------------------------------------- 预览（该角色成员看到的卡片与顺序）

const previewOpen = ref(false);

onMounted(() => void loadRoleView());
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
    title="首页卡片配置"
    description="按角色设置首页显示哪些卡片及其顺序：左侧目录勾选显示，右侧已选卡片上移 / 下移调整顺序；未配置的角色使用默认布局。配置只决定显示与顺序，不放宽数据权限——每张卡片照常按成员的数据范围统计。"
  >
    <Spin :spinning="loading">
      <Alert
        v-if="loadError"
        class="mb-4"
        :message="loadError"
        show-icon
        type="error"
      />
      <template v-else>
        <div class="toolbar">
          <Space wrap>
            <span>角色</span>
            <Select
              v-model:value="roleId"
              :options="roles"
              :style="{ width: 220 }"
              data-testid="layout-role"
              @change="handleRoleChange"
            />
            <Tag v-if="configured" color="blue">已配置</Tag>
            <Tag v-else>默认布局</Tag>
          </Space>
          <Space>
            <Button
              :disabled="!roleId || selectedCards.length === 0"
              data-testid="layout-preview"
              @click="previewOpen = true"
            >
              预览
            </Button>
            <Button
              :disabled="!roleId || !configured"
              :loading="saving"
              data-testid="layout-reset"
              @click="handleReset"
            >
              恢复默认
            </Button>
            <Button
              :disabled="!roleId"
              :loading="saving"
              data-testid="layout-save"
              type="primary"
              @click="handleSave"
            >
              保存
            </Button>
          </Space>
        </div>
        <Alert
          v-if="dirty"
          class="mb-4"
          message="有未保存的修改：保存后对全部持有该角色的成员立即生效"
          show-icon
          type="warning"
        />

        <div class="columns">
          <!-- 左：卡片目录（勾选显示） -->
          <div class="catalog" data-testid="layout-catalog">
            <p class="panel-title">卡片目录</p>
            <Space direction="vertical" size="small">
              <div v-for="card in cards" :key="card.code" class="catalog-row">
                <Checkbox
                  :checked="card.visible"
                  :data-testid="`layout-catalog-${card.code}`"
                  @change="
                    (event: { target: { checked: boolean } }) =>
                      toggleCard(card.code, event.target.checked)
                  "
                >
                  {{ card.title }}
                  <span v-if="card.width === 'HALF'" class="hint">半行</span>
                </Checkbox>
                <Tag v-if="!card.featureOn" class="feature-tag">
                  开关未开启，配置暂不显示
                </Tag>
              </div>
            </Space>
          </div>

          <!-- 右：已选卡片（上移 / 下移 + 允许用户隐藏） -->
          <div class="selected" data-testid="layout-selected">
            <p class="panel-title">已选卡片（从上到下）</p>
            <p v-if="selectedCards.length === 0" class="hint">
              未勾选任何卡片：该角色成员的首页不显示卡片
            </p>
            <div
              v-for="(card, index) in selectedCards"
              :key="card.code"
              class="selected-row"
              :data-testid="`layout-selected-${card.code}`"
            >
              <span class="order">{{ index + 1 }}</span>
              <span class="name">
                {{ card.title }}
                <span v-if="card.width === 'HALF'" class="hint">半行</span>
              </span>
              <span class="allow-hide">
                <Switch
                  :checked="card.allowUserHide"
                  checked-children="允许隐藏"
                  un-checked-children="禁止隐藏"
                  size="small"
                  @change="(value: boolean) => toggleAllowHide(card, value)"
                />
              </span>
              <span class="moves">
                <Button
                  :disabled="index === 0"
                  size="small"
                  :data-testid="`layout-move-up-${card.code}`"
                  @click="move(index, -1)"
                >
                  上移
                </Button>
                <Button
                  :disabled="index === selectedCards.length - 1"
                  size="small"
                  :data-testid="`layout-move-down-${card.code}`"
                  @click="move(index, 1)"
                >
                  下移
                </Button>
              </span>
            </div>
          </div>
        </div>

        <Modal
          v-model:open="previewOpen"
          :footer="null"
          :title="`「${roleName}」成员看到的首页`"
          :width="480"
        >
          <p class="hint">
            卡片从上到下排列（相邻两张半行卡并排）；数字仍按各成员本人的数据范围统计。
          </p>
          <ol class="preview-list">
            <li v-for="card in selectedCards" :key="card.code">
              {{ card.title }}
              <span v-if="card.width === 'HALF'" class="hint">半行</span>
              <span v-if="!card.featureOn" class="hint">
                （开关未开启，暂不显示）
              </span>
            </li>
          </ol>
        </Modal>
      </template>
    </Spin>
  </Page>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.columns {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 24px;
}

@media (max-width: 991px) {
  .columns {
    grid-template-columns: 1fr;
  }
}

.panel-title {
  margin: 0 0 12px;
  font-weight: 600;
}

.catalog-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.feature-tag {
  font-size: 12px;
}

.selected-row {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 6px 8px;
  margin-bottom: 4px;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
}

.order {
  width: 20px;
  color: gray;
  text-align: right;
}

.name {
  flex: 1;
}

.moves {
  display: flex;
  gap: 4px;
}

.hint {
  font-size: 12px;
  color: gray;
}

.preview-list {
  padding-left: 20px;
}
</style>
