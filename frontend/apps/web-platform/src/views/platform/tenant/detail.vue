<script lang="ts" setup>
import type { TenantDetail } from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  Modal,
  Row,
  Space,
  Spin,
  TabPane,
  Tabs,
} from 'ant-design-vue';

import { tenantDetail, updateTenant } from '#/api/core/tenant';
import { usePermission } from '#/reference/use-permission';

import {
  expireText,
  orDash,
  statusText,
  toFormModel,
  validateTenantForm,
} from './detail';

defineOptions({ name: 'PlatformTenantDetail' });

const route = useRoute();
const router = useRouter();
const tenantId = Number(route.params.id);
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:tenant:edit');

const detail = ref<null | TenantDetail>(null);
const loading = ref(false);
const editOpen = ref(false);
const saving = ref(false);
const errorMessage = ref('');
const saveError = ref('');

const form = reactive(toFormModel(blankDetail()));
const errors = computed(() => validateTenantForm(form));

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
    id: tenantId,
    name: '',
    orgType: null,
    region: null,
    remark: null,
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
                {{ expireText(detail) }}（到期维护随到期管理提供）
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
  </Page>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
</style>
