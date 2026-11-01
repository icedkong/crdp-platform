<script lang="ts" setup>
import type {
  RoleScopeType,
  RoleType,
  TenantUserView,
  UserEffectivePermissionsView,
  UserRoleBrief,
} from '@crdp/api';

import { computed, ref, watch } from 'vue';

import {
  flattenMenuPreview,
  scopeSummary,
  tenantRoleScopeTypeText,
  tenantRoleTypeText,
  userStatusText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { colors, sizes } from '@crdp/tokens';
import {
  Alert,
  Descriptions,
  DescriptionsItem,
  Drawer,
  Empty,
  Spin,
  Tag,
} from 'ant-design-vue';

import { effectivePermissions } from '#/api/core/user';

/**
 * E06-T09 / PLT-ORG-009、A-WARD-009、C-WARD-008：用户权限预览抽屉。展示某用户
 * 最终菜单（含按钮）、权限码与数据范围（范围类型 / 可见病区 / 负责科室），逐项标注
 * 来源角色，供排查「为什么看不到某菜单」。数据与登录会话同一计算入口（实时，不缓存）。
 */
defineOptions({ name: 'UserPermissionPreviewDrawer' });

const props = defineProps<{
  /** 目标用户（列表行） */
  user: null | TenantUserView;
}>();

const open = defineModel<boolean>({ default: false });

const loading = ref(false);
const view = ref<null | UserEffectivePermissionsView>(null);

watch(open, (value) => {
  if (value && props.user) {
    void load(props.user.id);
  }
});

async function load(userId: number) {
  loading.value = true;
  view.value = null;
  try {
    view.value = await effectivePermissions(userId);
  } catch {
    // 错误提示由统一拦截器弹出（含 traceId）
  } finally {
    loading.value = false;
  }
}

const menuRows = computed(() =>
  view.value ? flattenMenuPreview(view.value.menus) : [],
);

/** 病区 / 科室行标签：`名称（编码）`。 */
function nameWithCode(item: { code: string; name: string }): string {
  return `${item.name}（${item.code}）`;
}

const roleTypeColor = (role: UserRoleBrief): string =>
  role.status === 'ENABLED' ? colors.primary : colors.textSecondary;

const roleTypeText = (type: string) => tenantRoleTypeText(type as RoleType);

const drawerWidth = sizes.drawer.max;
const borderSecondary = colors.borderSecondary;
const textSecondary = colors.textSecondary;
const indentUnit = sizes.gutter;
</script>

<template>
  <Drawer
    v-model:open="open"
    :title="
      view ? `权限预览：${view.displayName}（${view.username}）` : '权限预览'
    "
    :width="drawerWidth"
    placement="right"
  >
    <Spin :spinning="loading">
      <template v-if="view">
        <Descriptions :column="2" size="small" class="mb-3">
          <DescriptionsItem label="状态">
            <StatusTag :status="userStatusText(view.status)" variant="dot" />
          </DescriptionsItem>
          <DescriptionsItem label="已开通模块">
            <Tag v-for="code in view.openedModules" :key="code">{{ code }}</Tag>
          </DescriptionsItem>
          <DescriptionsItem label="角色" :span="2">
            <Tag
              v-for="role in view.roles"
              :key="role.id"
              :color="roleTypeColor(role)"
            >
              {{ role.name }}（{{ roleTypeText(role.roleType) }}）{{
                role.status === 'ENABLED' ? '' : ' · 已停用'
              }}
            </Tag>
            <span v-if="view.roles.length === 0">—</span>
          </DescriptionsItem>
        </Descriptions>

        <h3 class="section-title">数据范围</h3>
        <Alert
          :message="`生效范围：${scopeSummary(view)}`"
          :description="
            view.scopeAll
              ? '任一启用角色为全院范围，不再按病区 / 科室过滤（租户管理员全院可见）。'
              : '多角色范围取并集；受控数据的查询按该范围过滤，范围为空时返回空结果。'
          "
          show-icon
          :type="view.scopeAll ? 'success' : 'info'"
        />
        <div v-if="!view.scopeAll" class="scope-list">
          <div
            v-for="item in view.scopeTypes"
            :key="item.type"
            class="scope-row"
          >
            <span class="scope-label">{{
              tenantRoleScopeTypeText(item.type as RoleScopeType)
            }}</span>
            <span>来源角色：</span>
            <Tag v-for="name in item.sourceRoles" :key="name">{{ name }}</Tag>
          </div>
        </div>

        <!-- 可见病区（A-WARD-009）：本人负责病区 × WARD 范围触发 -->
        <template v-if="view.wards.length > 0">
          <h3 class="section-title">可见病区</h3>
          <div class="scope-list">
            <div v-for="ward in view.wards" :key="ward.id" class="scope-row">
              <span class="scope-label">{{ nameWithCode(ward) }}</span>
              <span>来源角色：</span>
              <Tag v-for="name in ward.sourceRoles" :key="name">{{ name }}</Tag>
            </div>
          </div>
        </template>

        <template v-if="view.depts.length > 0">
          <h3 class="section-title">负责科室</h3>
          <div class="scope-list">
            <div v-for="dept in view.depts" :key="dept.id" class="scope-row">
              <span class="scope-label">{{ nameWithCode(dept) }}</span>
              <span>来源角色：</span>
              <Tag v-for="name in dept.sourceRoles" :key="name">{{ name }}</Tag>
            </div>
          </div>
        </template>

        <h3 class="section-title">菜单与按钮</h3>
        <div
          v-if="menuRows.length > 0"
          class="menu-rows"
          :style="{ border: `1px solid ${borderSecondary}` }"
        >
          <div
            v-for="(row, index) in menuRows"
            :key="`${row.node.id}-${index}`"
            :style="{ paddingLeft: `${row.indent * indentUnit}px` }"
            class="menu-row"
          >
            <span :class="{ 'menu-dir': row.node.type === 'DIR' }">
              {{ row.title }}
            </span>
            <Tag v-if="row.node.permCode" class="code-tag">
              {{ row.node.permCode }}
            </Tag>
            <span class="source" :style="{ color: textSecondary }">
              来源：
              <Tag
                v-for="name in row.node.sourceRoles"
                :key="name"
                :style="{ marginInlineEnd: '4px' }"
              >
                {{ name }}
              </Tag>
            </span>
          </div>
        </div>
        <Empty v-else description="没有生效菜单（角色未授权或模块未开通）" />

        <h3 class="section-title">
          权限码（{{ view.permissionCodes.length }}）
        </h3>
        <div v-if="view.permissionCodes.length > 0" class="codes">
          <Tag
            v-for="code in view.permissionCodes"
            :key="code"
            class="code-tag"
          >
            {{ code }}
          </Tag>
        </div>
        <Empty v-else description="没有生效权限码" />
      </template>
      <Empty v-else-if="!loading" description="暂无数据" />
    </Spin>
  </Drawer>
</template>

<style scoped>
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.scope-list {
  margin-top: 8px;
}

.scope-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  padding: 2px 0;
  font-size: 13px;
}

.scope-label {
  min-width: 180px;
}

.menu-rows {
  padding: 8px 12px;
  border-radius: 6px;
}

.menu-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 3px 0;
  font-size: 13px;
}

.menu-dir {
  font-weight: 600;
}

.source {
  font-size: 12px;
}

.code-tag {
  font-family: monospace;
  font-size: 12px;
}

.codes {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.mb-3 {
  margin-bottom: 12px;
}
</style>
