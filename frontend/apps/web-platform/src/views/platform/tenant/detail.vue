<script lang="ts" setup>
import type { TenantDetail } from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { EXPIRE_TYPE_TEXT } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  InputNumber,
  Modal,
  Radio,
  RadioGroup,
  Row,
  Space,
  Spin,
  Switch,
  TabPane,
  Tabs,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import {
  applyTenantExpiry,
  tenantDetail,
  updateTenant,
} from '#/api/core/tenant';
import { usePermission } from '#/reference/use-permission';

import {
  expireText,
  orDash,
  remindDaysText,
  statusText,
  toFormModel,
  validateTenantForm,
} from './detail';
import {
  EMPTY_EXPIRY_FORM,
  toExpireRequest,
  toExpiryFormModel,
  validateExpiryForm,
  validateReason,
} from './lifecycle';

defineOptions({ name: 'PlatformTenantDetail' });

const route = useRoute();
const router = useRouter();
const tenantId = Number(route.params.id);
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:tenant:edit');
const canRenew = hasPermission('platform:tenant:renew');

const detail = ref<null | TenantDetail>(null);
const loading = ref(false);
const editOpen = ref(false);
const saving = ref(false);
const errorMessage = ref('');
const saveError = ref('');

const form = reactive(toFormModel(blankDetail()));
const errors = computed(() => validateTenantForm(form));

// 到期设置（E05-T02 / PLT-TEN-011）：展示 + 编辑（PUT /expire，写 TENANT_EXPIRE 审计）
const expiryOpen = ref(false);
const expirySaving = ref(false);
const expiryError = ref('');
const expiryForm = reactive({ ...EMPTY_EXPIRY_FORM });
const expiryReason = ref('');
const expiryReasonError = ref('');
const expiryErrors = computed(() => validateExpiryForm(expiryForm));
const expiryOkDisabled = computed(
  () =>
    !expiryReason.value.trim() ||
    Boolean(expiryReasonError.value) ||
    Object.keys(expiryErrors.value).length > 0,
);

function blankDetail(): TenantDetail {
  return {
    code: '',
    contactEmail: null,
    contactName: null,
    contactPhone: null,
    contractNo: null,
    createdAt: '',
    creditCode: null,
    expireAt: null,
    expireType: 'LONG_TERM',
    graceDays: null,
    graceReadOnly: false,
    id: tenantId,
    name: '',
    orgType: null,
    region: null,
    remark: null,
    remindDays: null,
    shortName: null,
    status: 'PROVISIONING',
    updatedAt: null,
  };
}

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    detail.value = await tenantDetail(tenantId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function openEdit() {
  if (!detail.value) return;
  Object.assign(form, toFormModel(detail.value));
  saveError.value = '';
  editOpen.value = true;
}

async function save() {
  if (Object.keys(errors.value).length > 0) return;
  saving.value = true;
  saveError.value = '';
  try {
    const {
      contactEmail,
      contactName,
      contactPhone,
      contractNo,
      creditCode,
      name,
      orgType,
      region,
      remark,
      shortName,
    } = form;
    detail.value = await updateTenant(tenantId, {
      contactEmail,
      contactName,
      contactPhone,
      contractNo,
      creditCode,
      name: name.trim(),
      orgType,
      region,
      remark,
      shortName,
    });
    editOpen.value = false;
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : String(error);
  } finally {
    saving.value = false;
  }
}

function openExpiry() {
  if (!detail.value) return;
  Object.assign(expiryForm, toExpiryFormModel(detail.value));
  expiryReason.value = '';
  expiryReasonError.value = '';
  expiryError.value = '';
  expiryOpen.value = true;
}

function onExpiryDate(_date: unknown, date: null | string | string[]) {
  expiryForm.expireAt = Array.isArray(date) ? '' : (date ?? '');
}

function onExpiryReasonChange() {
  expiryReasonError.value = validateReason(expiryReason.value) ?? '';
}

async function saveExpiry() {
  if (expiryOkDisabled.value) return;
  expirySaving.value = true;
  expiryError.value = '';
  try {
    await applyTenantExpiry(
      tenantId,
      toExpireRequest(expiryForm, expiryReason.value),
    );
    expiryOpen.value = false;
    await load();
  } catch (error) {
    expiryError.value = error instanceof Error ? error.message : String(error);
  } finally {
    expirySaving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <Page
    :title="detail?.name ?? '租户详情'"
    description="租户基本信息与维护；模块、授权范围、到期设置、操作记录随对应任务提供。"
  >
    <Card :bordered="false" class="crdp-card">
      <Spin :spinning="loading">
        <Alert
          v-if="errorMessage"
          :message="`加载失败：${errorMessage}`"
          show-icon
          type="error"
          class="mb-4"
        />
        <Tabs v-if="detail" default-active-key="basic">
          <TabPane key="basic" tab="基本信息">
            <div class="toolbar">
              <Button
                v-if="canEdit"
                type="primary"
                data-testid="edit-tenant"
                @click="openEdit"
              >
                编辑
              </Button>
            </div>
            <Descriptions bordered :column="2" size="middle">
              <DescriptionsItem label="医院名称">
                {{ detail.name }}
              </DescriptionsItem>
              <DescriptionsItem label="租户编码">
                {{ detail.code }}（创建后不可改）
              </DescriptionsItem>
              <DescriptionsItem label="简称">
                {{ orDash(detail.shortName) }}
              </DescriptionsItem>
              <DescriptionsItem label="统一社会信用代码">
                {{ orDash(detail.creditCode) }}
              </DescriptionsItem>
              <DescriptionsItem label="机构类型与等级">
                {{ orDash(detail.orgType) }}
              </DescriptionsItem>
              <DescriptionsItem label="所在地区">
                {{ orDash(detail.region) }}
              </DescriptionsItem>
              <DescriptionsItem label="联系人">
                {{ orDash(detail.contactName) }}
              </DescriptionsItem>
              <DescriptionsItem label="联系电话">
                {{ orDash(detail.contactPhone) }}
              </DescriptionsItem>
              <DescriptionsItem label="联系邮箱">
                {{ orDash(detail.contactEmail) }}
              </DescriptionsItem>
              <DescriptionsItem label="合同编号">
                {{ orDash(detail.contractNo) }}
              </DescriptionsItem>
              <DescriptionsItem label="状态">
                <StatusTag :status="statusText(detail.status)" />
              </DescriptionsItem>
              <DescriptionsItem label="到期日">
                {{ expireText(detail) }}（维护在「到期设置」页签）
              </DescriptionsItem>
              <DescriptionsItem label="备注" :span="2">
                {{ orDash(detail.remark) }}
              </DescriptionsItem>
              <DescriptionsItem label="创建时间">
                {{ detail.createdAt ?? '—' }}
              </DescriptionsItem>
              <DescriptionsItem label="最近修改">
                {{ detail.updatedAt ?? '—' }}
              </DescriptionsItem>
            </Descriptions>
          </TabPane>
          <TabPane key="expiry" tab="到期设置">
            <div class="toolbar">
              <Button
                v-if="canRenew"
                type="primary"
                data-testid="edit-expiry"
                @click="openExpiry"
              >
                编辑到期设置
              </Button>
            </div>
            <Descriptions bordered :column="2" size="middle">
              <DescriptionsItem label="到期类型">
                {{ EXPIRE_TYPE_TEXT[detail.expireType] ?? detail.expireType }}
              </DescriptionsItem>
              <DescriptionsItem label="试用或服务到期日">
                {{ detail.expireType === 'FIXED' ? expireText(detail) : '—' }}
              </DescriptionsItem>
              <DescriptionsItem label="提醒节点">
                {{ remindDaysText(detail.remindDays) }}
              </DescriptionsItem>
              <DescriptionsItem label="宽限期天数">
                {{ detail.graceDays === null ? '—' : `${detail.graceDays} 天` }}
              </DescriptionsItem>
              <DescriptionsItem label="宽限期是否只读">
                {{
                  detail.graceReadOnly
                    ? '只读（只能查看）'
                    : '正常使用并提示续费'
                }}
              </DescriptionsItem>
              <DescriptionsItem label="提醒与宽限规则">
                长期有效租户不提醒、不进入宽限期；到期前按提醒节点通知，宽限期默认可正常使用
              </DescriptionsItem>
            </Descriptions>
          </TabPane>
        </Tabs>
      </Spin>
      <Space class="mt-6">
        <Button @click="router.push('/platform/tenant')">返回租户列表</Button>
      </Space>
    </Card>

    <!-- 信息维护（PLT-TEN-009）：弹窗 800、4 列、备注整行；每次修改后端写审计 -->
    <Modal
      v-model:open="editOpen"
      :confirm-loading="saving"
      title="编辑租户信息"
      :width="800"
      :ok-button-props="{ disabled: Object.keys(errors).length > 0 }"
      ok-text="保存"
      cancel-text="取消"
      @ok="save"
    >
      <Alert
        v-if="saveError"
        :message="`保存失败：${saveError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <Row :gutter="16">
          <Col :span="12">
            <FormItem
              label="医院名称"
              required
              :validate-status="errors.name ? 'error' : undefined"
              :help="errors.name"
            >
              <Input v-model:value="form.name" :maxlength="128" show-count />
            </FormItem>
          </Col>
          <Col :span="6">
            <FormItem label="简称">
              <Input v-model:value="form.shortName" :maxlength="64" />
            </FormItem>
          </Col>
          <Col :span="6">
            <FormItem label="合同编号">
              <Input v-model:value="form.contractNo" :maxlength="64" />
            </FormItem>
          </Col>
          <Col :span="6">
            <FormItem label="统一社会信用代码">
              <Input v-model:value="form.creditCode" :maxlength="32" />
            </FormItem>
          </Col>
          <Col :span="6">
            <FormItem label="机构类型与等级">
              <Input v-model:value="form.orgType" :maxlength="64" />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem label="所在地区">
              <Input v-model:value="form.region" :maxlength="128" />
            </FormItem>
          </Col>
          <Col :span="6">
            <FormItem label="联系人">
              <Input v-model:value="form.contactName" :maxlength="64" />
            </FormItem>
          </Col>
          <Col :span="6">
            <FormItem label="联系电话">
              <Input v-model:value="form.contactPhone" :maxlength="32" />
            </FormItem>
          </Col>
          <Col :span="12">
            <FormItem
              label="联系邮箱"
              :validate-status="errors.contactEmail ? 'error' : undefined"
              :help="errors.contactEmail"
            >
              <Input v-model:value="form.contactEmail" :maxlength="128" />
            </FormItem>
          </Col>
          <Col :span="24">
            <FormItem label="备注">
              <Input
                v-model:value="form.remark"
                :maxlength="255"
                show-count
                type="textarea"
              />
            </FormItem>
          </Col>
        </Row>
      </Form>
    </Modal>

    <!-- 到期设置（PLT-TEN-011）：选择长期有效时其余项隐藏；保存即 PUT /expire（宽限期/停用中续期即恢复） -->
    <Modal
      v-model:open="expiryOpen"
      :confirm-loading="expirySaving"
      title="编辑到期设置"
      :width="560"
      :ok-button-props="{ disabled: expiryOkDisabled }"
      ok-text="保存"
      cancel-text="取消"
      @ok="saveExpiry"
    >
      <Alert
        v-if="expiryError"
        :message="`保存失败：${expiryError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem label="到期类型" required>
          <RadioGroup v-model:value="expiryForm.expireType">
            <Radio value="FIXED">有到期日</Radio>
            <Radio value="LONG_TERM">长期有效</Radio>
          </RadioGroup>
        </FormItem>
        <template v-if="expiryForm.expireType === 'FIXED'">
          <FormItem
            label="试用或服务到期日"
            required
            :validate-status="expiryErrors.expireAt ? 'error' : undefined"
            :help="expiryErrors.expireAt"
          >
            <DatePicker
              :value="
                expiryForm.expireAt ? dayjs(expiryForm.expireAt) : undefined
              "
              placeholder="选择日期"
              style="width: 100%"
              value-format="YYYY-MM-DD"
              @change="onExpiryDate"
            />
          </FormItem>
          <FormItem
            label="提醒节点（到期前 N 天，逗号分隔）"
            :validate-status="expiryErrors.remindDays ? 'error' : undefined"
            :help="expiryErrors.remindDays ?? '默认 30,7,1；留空沿用当前配置'"
          >
            <Input
              v-model:value="expiryForm.remindDays"
              placeholder="如：30,7,1"
            />
          </FormItem>
          <FormItem
            label="宽限期天数"
            :validate-status="expiryErrors.graceDays ? 'error' : undefined"
            :help="expiryErrors.graceDays ?? '默认 15；留空沿用当前配置'"
          >
            <InputNumber
              v-model:value="expiryForm.graceDays"
              :max="365"
              :min="1"
              placeholder="15"
              style="width: 100%"
            />
          </FormItem>
          <FormItem label="宽限期是否只读">
            <Switch v-model:checked="expiryForm.graceReadOnly" />
            <span class="switch-hint">
              {{
                expiryForm.graceReadOnly
                  ? '宽限期内只能查看'
                  : '宽限期内正常使用并提示续费'
              }}
            </span>
          </FormItem>
        </template>
        <FormItem
          label="变更原因"
          required
          :validate-status="expiryReasonError ? 'error' : undefined"
          :help="expiryReasonError"
        >
          <Input
            v-model:value="expiryReason"
            :maxlength="200"
            placeholder="如：按合同调整（示例）"
            show-count
            type="textarea"
            @change="onExpiryReasonChange"
          />
        </FormItem>
      </Form>
    </Modal>
  </Page>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.switch-hint {
  margin-left: 12px;
  color: #4e5969;
}
</style>
