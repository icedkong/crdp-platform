<script lang="ts" setup>
import type { PlatformAnnouncementView } from '@crdp/api';

import type { AnnouncementFormModel } from './notice';

import { computed, h, onMounted, onUnmounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { announcementStatusText } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { breakpoints } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Radio,
  RadioGroup,
  Result,
  Select,
  Table,
  Textarea,
} from 'ant-design-vue';

import {
  listAnnouncements,
  publishAnnouncement,
  revokeAnnouncement,
} from '#/api/core/announcement';
import { listTenants } from '#/api/core/tenant';

import {
  announcementTimeText,
  CONTENT_MAX,
  emptyFormModel,
  SCOPE_OPTIONS,
  tenantNamesText,
  tenantOptions,
  TITLE_MAX,
  validateFormModel,
} from './notice';

defineOptions({ name: 'PlatformNotice' });

/**
 * E05-T11 / PLT-CFG-009 · pages.md §11「系统配置」通知与公告（/platform/notice，
 * platform:notice:edit；通知渠道与模板随后续任务并入同一菜单——推断，待确认）：
 * 发布即生效（全部租户 / 指定租户），发布后不可编辑、更正走撤下 + 重新发布；
 * 撤下后医院端即刻不可见。仅桌面 / 平板。
 */
const isMobile = ref(false);

const loading = ref(false);
const loadError = ref('');
const items = ref<PlatformAnnouncementView[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(20);
const keyword = ref('');

const publishOpen = ref(false);
const publishing = ref(false);
const form = reactive<AnnouncementFormModel>(emptyFormModel());
const tenantOptionList = ref<Array<{ label: string; value: number }>>([]);
const tenantOptionsError = ref('');

const detailOpen = ref(false);
const detail = ref<null | PlatformAnnouncementView>(null);
const revoking = ref(false);

const columns = [
  { dataIndex: 'title', title: '标题' },
  { dataIndex: 'scope', title: '发布范围', width: 260 },
  { dataIndex: 'status', title: '状态', width: 100 },
  { dataIndex: 'publishedAt', title: '发布时间', width: 150 },
  { dataIndex: 'publishedByName', title: '发布人', width: 180 },
  { dataIndex: 'action', title: '操作', width: 150 },
];

const pagination = computed(() => ({
  current: pageNo.value,
  pageSize: pageSize.value,
  showSizeChanger: false,
  total: total.value,
  onChange: (page: number) => {
    pageNo.value = page;
    void load();
  },
}));

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const page = await listAnnouncements({
      keyword: keyword.value || undefined,
      pageNo: pageNo.value,
      pageSize: pageSize.value,
    });
    items.value = page.items;
    total.value = page.total;
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  pageNo.value = 1;
  void load();
}

// ---------------------------------------------------------------- 发布

async function openPublish() {
  Object.assign(form, emptyFormModel());
  publishOpen.value = true;
  // 租户下拉选项按需加载（platform:tenant:view 与公告维护通常同持；失败不打断发布全员公告）
  if (tenantOptionList.value.length === 0) {
    try {
      const page = await listTenants({ pageNo: 1, pageSize: 100 });
      tenantOptionList.value = tenantOptions(page.items);
    } catch {
      tenantOptionsError.value = '租户列表加载失败，可先发布面向全部租户的公告';
    }
  }
}

async function submitPublish() {
  const errors = validateFormModel(form);
  if (errors.length > 0) {
    message.warning(errors[0]);
    return;
  }
  publishing.value = true;
  try {
    const created = await publishAnnouncement({
      content: form.content.trim(),
      scope: form.scope,
      tenantIds: form.scope === 'TENANTS' ? form.tenantIds : null,
      title: form.title.trim(),
    });
    publishOpen.value = false;
    message.success(
      form.scope === 'ALL'
        ? '公告已发布，全部租户即时可见'
        : '公告已发布，指定租户即时可见',
    );
    detail.value = created;
    detailOpen.value = true;
    pageNo.value = 1;
    await load();
  } finally {
    publishing.value = false;
  }
}

// ---------------------------------------------------------------- 详情与撤下

function openDetail(row: PlatformAnnouncementView) {
  detail.value = row;
  detailOpen.value = true;
}

function confirmRevoke(row: PlatformAnnouncementView) {
  Modal.confirm({
    title: '撤下公告',
    content: `撤下后「${row.title}」对医院端即刻不可见，且不能恢复；如需更正请重新发布。`,
    okText: '撤下',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      revoking.value = true;
      try {
        await revokeAnnouncement(row.id);
        message.success('公告已撤下');
        detailOpen.value = false;
        await load();
      } finally {
        revoking.value = false;
      }
    },
  });
}

function emptyNode() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () => '暂无公告');
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
      <Button type="primary" @click="$router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page
    v-else
    title="系统公告"
    description="平台向全部或指定租户发布公告，发布即生效；发布后不可编辑，更正走撤下后重新发布。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <Input
          v-model:value="keyword"
          class="keyword-input"
          allow-clear
          placeholder="按标题搜索"
          data-testid="announcement-keyword"
          @press-enter="search"
        />
        <Button
          type="primary"
          data-testid="announcement-publish"
          @click="openPublish"
        >
          发布公告
        </Button>
      </div>

      <Alert
        v-if="loadError"
        class="mb-4"
        :message="`加载失败：${loadError}`"
        show-icon
        type="error"
      />
      <Table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'scope'">
            {{ tenantNamesText(record as PlatformAnnouncementView) }}
          </template>
          <template v-else-if="column.dataIndex === 'status'">
            <StatusTag
              :status="
                announcementStatusText(
                  (record as PlatformAnnouncementView).status,
                )
              "
            />
          </template>
          <template v-else-if="column.dataIndex === 'publishedAt'">
            {{
              announcementTimeText(
                (record as PlatformAnnouncementView).publishedAt,
              )
            }}
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <Button
              size="small"
              type="link"
              @click="openDetail(record as PlatformAnnouncementView)"
            >
              查看
            </Button>
            <Button
              v-if="(record as PlatformAnnouncementView).status === 'PUBLISHED'"
              class="danger-link"
              danger
              size="small"
              type="link"
              @click="confirmRevoke(record as PlatformAnnouncementView)"
            >
              撤下
            </Button>
          </template>
        </template>
        <template #empty-text>
          <component :is="emptyNode" />
        </template>
      </Table>
    </Card>

    <Modal
      v-model:open="publishOpen"
      title="发布公告"
      :confirm-loading="publishing"
      ok-text="发布"
      cancel-text="取消"
      :width="640"
      @ok="submitPublish"
    >
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem label="标题" required>
          <Input
            v-model:value="form.title"
            :maxlength="TITLE_MAX"
            placeholder="如 平台升级维护通知"
            data-testid="announcement-title"
          />
        </FormItem>
        <FormItem label="正文" required>
          <Textarea
            v-model:value="form.content"
            :maxlength="CONTENT_MAX"
            :rows="6"
            show-count
            placeholder="面向医院用户的公告正文（发布即生效）"
          />
        </FormItem>
        <FormItem label="发布范围" required>
          <RadioGroup v-model:value="form.scope">
            <Radio
              v-for="option in SCOPE_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </Radio>
          </RadioGroup>
        </FormItem>
        <FormItem
          v-if="form.scope === 'TENANTS'"
          label="指定租户（只有选中的医院可见）"
          required
          :extra="tenantOptionsError"
        >
          <Select
            v-model:value="form.tenantIds"
            :options="tenantOptionList"
            mode="multiple"
            placeholder="选择医院（可搜索）"
            show-search
            option-filter-prop="label"
            data-testid="announcement-tenants"
          />
        </FormItem>
      </Form>
    </Modal>

    <Modal
      v-model:open="detailOpen"
      :title="detail ? `公告 · ${detail.title}` : '公告'"
      :footer="null"
      :width="640"
    >
      <template v-if="detail">
        <p class="detail-meta">
          {{ announcementStatusText(detail.status) }} ·
          {{ tenantNamesText(detail) }} · 发布于
          {{ announcementTimeText(detail.publishedAt) }} ·
          {{ detail.publishedByName ?? '—' }}
        </p>
        <p class="detail-content">{{ detail.content }}</p>
        <div v-if="detail.scope === 'TENANTS'" class="detail-tenants">
          <span class="detail-tenants__label">指定租户：</span>
          <span>
            {{
              detail.tenants
                .map((tenant) => `${tenant.tenantName}（${tenant.tenantCode}）`)
                .join('、') || '—'
            }}
          </span>
        </div>
        <div v-if="detail.status === 'PUBLISHED'" class="detail-actions">
          <Button
            danger
            :loading="revoking"
            data-testid="announcement-revoke"
            @click="confirmRevoke(detail)"
          >
            撤下公告
          </Button>
        </div>
      </template>
    </Modal>
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  margin-bottom: 16px;
}

.keyword-input {
  width: 240px;
  margin-right: auto;
}

.detail-meta {
  margin: 0 0 8px;
  color: rgb(0 0 0 / 45%);
}

.detail-content {
  margin: 0 0 16px;
  white-space: pre-wrap;
}

.detail-tenants {
  margin-bottom: 16px;
}

.detail-tenants__label {
  color: rgb(0 0 0 / 45%);
}

.detail-actions {
  padding-top: 8px;
  border-top: 1px solid rgb(0 0 0 / 6%);
}
</style>
