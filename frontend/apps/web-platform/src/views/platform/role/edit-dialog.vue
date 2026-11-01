<script lang="ts" setup>
import type { PlatformGrantMenuNode, PlatformRoleView } from '@crdp/api';

import type { GrantTreeNode, RoleEditModel } from './logic';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Form,
  FormItem,
  Input,
  Modal,
  RadioButton,
  RadioGroup,
  Switch,
  TabPane,
  Tabs,
  Textarea,
  Tree,
} from 'ant-design-vue';

import {
  createRole,
  getRole,
  listGrantableMenus,
  updateRole,
} from '#/api/core/role';

import {
  buildGrantTree,
  toRoleEditModel,
  toRoleSaveRequest,
  validateRoleModel,
} from './logic';

/**
 * 新建 / 维护平台角色（E06-T04 / PLT-ROLE-002、003 · pages.md §11 平台角色）：
 * 三个页签——基本信息、平台端菜单授权、租户视角权限（platform-spec §8 六字段）。
 * 预置角色权限可调整；「指定租户」清单由租户视角任务（E20-T01）提供，选项禁用。
 */
const props = defineProps<{
  open: boolean;
  role: null | PlatformRoleView;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'done'): void;
}>();

const form = ref<RoleEditModel>(toRoleEditModel(null, []));
const grantNodes = ref<PlatformGrantMenuNode[]>([]);
const grantTree = computed<GrantTreeNode[]>(() =>
  buildGrantTree(grantNodes.value),
);
const errors = ref<string[]>([]);
const submitting = ref(false);
const submitError = ref('');
const loadError = ref('');

const isCreate = computed(() => props.role === null);
const title = computed(() =>
  isCreate.value ? '新建平台角色' : `维护平台角色：${props.role?.name ?? ''}`,
);
const okDisabled = computed(() => errors.value.length > 0);

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    errors.value = [];
    submitError.value = '';
    loadError.value = '';
    try {
      grantNodes.value = await listGrantableMenus();
    } catch (error) {
      grantNodes.value = [];
      loadError.value = error instanceof Error ? error.message : String(error);
    }
    if (props.role) {
      try {
        const detail = await getRole(props.role.id);
        form.value = toRoleEditModel(detail, detail.menuIds ?? []);
      } catch {
        form.value = toRoleEditModel(props.role, []);
      }
    } else {
      form.value = toRoleEditModel(null, []);
    }
  },
);

function onFormChange() {
  errors.value = validateRoleModel(form.value);
}

async function submit() {
  if (okDisabled.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    const request = toRoleSaveRequest(form.value);
    await (props.role
      ? updateRole(props.role.id, request)
      : createRole(request));
    emit('update:open', false);
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
    :confirm-loading="submitting"
    :ok-button-props="{ disabled: okDisabled }"
    :title="title"
    :width="640"
    ok-text="保存"
    cancel-text="取消"
    @ok="submit"
    @cancel="emit('update:open', false)"
  >
    <Alert
      v-if="loadError"
      :message="`授权菜单加载失败：${loadError}`"
      show-icon
      type="error"
      class="mb-2"
    />
    <Tabs>
      <TabPane key="base" tab="基本信息">
        <Form layout="vertical" class="crdp-form">
          <FormItem v-if="!isCreate" label="编码">
            <span class="code">{{ role?.code }}</span>
            <span class="readonly-hint">（创建后不可修改）</span>
          </FormItem>
          <FormItem
            label="名称"
            required
            :validate-status="errors.length > 0 ? 'error' : undefined"
            :help="errors.join('；') || undefined"
          >
            <Input
              v-model:value="form.name"
              :maxlength="64"
              placeholder="如：巡检角色（示例）"
              show-count
              @change="onFormChange"
            />
          </FormItem>
          <FormItem label="说明">
            <Textarea
              v-model:value="form.description"
              :maxlength="255"
              placeholder="角色职责说明"
              show-count
              @change="onFormChange"
            />
          </FormItem>
          <FormItem label="状态">
            <RadioGroup v-model:value="form.status" @change="onFormChange">
              <RadioButton value="ENABLED">启用</RadioButton>
              <RadioButton value="DISABLED">停用</RadioButton>
            </RadioGroup>
          </FormItem>
          <Alert
            v-if="form.status === 'DISABLED'"
            message="停用后持有人立即失去该角色权限（在线会话在下一次请求时生效）；重新启用自动恢复。"
            show-icon
            type="warning"
          />
        </Form>
      </TabPane>
      <TabPane key="menus" tab="平台端菜单">
        <p class="hint">
          勾选该角色可用的平台端菜单与按钮；保存后持有人的菜单与权限码按并集重算。
        </p>
        <Tree
          v-if="grantTree.length > 0"
          v-model:checked="form.menuIds"
          :tree-data="grantTree"
          checkable
          default-expand-all
        />
        <p v-else class="hint">暂无可授权菜单。</p>
      </TabPane>
      <TabPane key="view" tab="租户视角">
        <Form layout="vertical" class="crdp-form">
          <FormItem label="可进入租户视角">
            <Switch
              v-model:checked="form.viewCanEnter"
              checked-children="可进入"
              un-checked-children="关闭"
            />
          </FormItem>
          <FormItem label="可见范围">
            <RadioGroup
              v-model:value="form.viewVisibleScope"
              :disabled="!form.viewCanEnter"
            >
              <RadioButton value="CONFIG_ONLY">仅配置类</RadioButton>
              <RadioButton value="CONFIG_AND_BUSINESS">
                配置类 + 业务数据
              </RadioButton>
            </RadioGroup>
          </FormItem>
          <FormItem label="租户范围">
            <RadioGroup
              v-model:value="form.viewTenantScope"
              :disabled="!form.viewCanEnter"
            >
              <RadioButton value="ALL">全部</RadioButton>
              <RadioButton value="RESPONSIBLE">负责的租户</RadioButton>
              <RadioButton value="SPECIFIED" disabled>指定租户</RadioButton>
            </RadioGroup>
            <p v-if="!form.viewCanEnter" class="hint">
              关闭「可进入」后，租户视角配置暂存但不生效。
            </p>
            <p v-if="form.viewTenantScope === 'SPECIFIED'" class="hint">
              指定租户清单由租户视角任务（E20-T01）提供，当前暂不可选。
            </p>
          </FormItem>
          <FormItem label="可代为配置">
            <Switch
              v-model:checked="form.viewCanConfigure"
              :disabled="!form.viewCanEnter"
            />
          </FormItem>
          <FormItem label="可代重置医院用户密码">
            <Switch
              v-model:checked="form.viewCanResetPassword"
              :disabled="!form.viewCanEnter"
            />
          </FormItem>
          <FormItem label="可编辑业务数据">
            <Switch
              v-model:checked="form.viewCanEditBusiness"
              :disabled="!form.viewCanEnter"
            />
            <p class="hint">
              生效还须命中视角规则中的「可编辑」规则（PLT-VIEW-018，默认仅平台管理员）。
            </p>
          </FormItem>
        </Form>
      </TabPane>
    </Tabs>
    <Alert
      v-if="submitError"
      :message="`保存失败：${submitError}`"
      show-icon
      type="error"
      class="mt-2"
    />
  </Modal>
</template>

<style scoped>
.code {
  font-family: monospace;
}

.readonly-hint {
  margin-left: 8px;
  color: #86909c;
}

.hint {
  margin-bottom: 8px;
  font-size: 13px;
  color: #86909c;
}
</style>
