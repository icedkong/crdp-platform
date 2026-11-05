<script lang="ts" setup>
import type {
  WebhookDeliveryView,
  WebhookEventOption,
  WebhookView,
} from '@crdp/api';

import type { WebhookEditModel } from './webhooks';

import { onMounted, reactive, ref } from 'vue';

import {
  WEBHOOK_EVENT_OPTIONS,
  webhookDeliveryStatusText,
  webhookEventLabel,
} from '@crdp/api';
import { colors } from '@crdp/tokens';
import {
  Alert,
  Button,
  CheckboxGroup,
  Empty,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Spin,
  Switch,
  Table,
  Tag,
  Textarea,
} from 'ant-design-vue';

import {
  createWebhook,
  deleteWebhook,
  listWebhookDeliveries,
  listWebhooks,
  redeliverWebhook,
  testWebhook,
  updateWebhook,
} from '#/api/core/webhook';

import {
  emptyWebhookModel,
  lastDeliveryText,
  toWebhookModel,
  toWebhookSaveRequest,
  validateWebhookModel,
  WEBHOOK_NAME_MAX,
  WEBHOOK_URL_MAX,
} from './webhooks';

defineOptions({ name: 'HospitalSettingsWebhook' });

/**
 * E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007 · pages.md §10「机构设置 · Webhook」
 * （tenant:webhook:edit）：配置本院 Webhook（回调地址仅 HTTPS 公网，内网 / 回环由后端
 * 拒绝；订阅事件按院勾选——C 不勾「患者死亡」即可），推送只含事件类型与对象 ID。
 * 签名密钥只在新建 / 更换时回显一次；「测试」立即推一条 WEBHOOK_TEST 并回显签名头值，
 * 接收方按 notify.md §6 校验（HMAC-SHA256）。失败投递可手动重投。
 */
const loading = ref(false);
const loadError = ref('');
const rows = ref<WebhookView[]>([]);
const events = ref<WebhookEventOption[]>(WEBHOOK_EVENT_OPTIONS);

// ---------------------------------------------------------------- 编辑弹窗
const editing = ref(false);
const saving = ref(false);
const editingId = ref<null | number>(null);
const model = reactive<WebhookEditModel>(emptyWebhookModel());
const errors = reactive({ events: '', name: '', secret: '', url: '' });
const secretOnce = ref('');
const testing = ref<null | number>(null);

// ---------------------------------------------------------------- 投递记录
const deliveriesOpen = ref(false);
const deliveriesLoading = ref(false);
const deliveries = ref<WebhookDeliveryView[]>([]);
const deliveriesTotal = ref(0);
const deliveriesPage = ref(1);
const deliveriesStatus = ref<string | undefined>(undefined);
const deliveriesWebhook = ref<null | WebhookView>(null);

const textSecondary = colors.textSecondary;

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    rows.value = await listWebhooks();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function openCreate() {
  editingId.value = null;
  Object.assign(model, emptyWebhookModel());
  clearErrors();
  secretOnce.value = '';
  editing.value = true;
}

function openEdit(row: WebhookView) {
  editingId.value = row.id;
  Object.assign(model, toWebhookModel(row));
  clearErrors();
  secretOnce.value = '';
  editing.value = true;
}

function clearErrors() {
  errors.name = '';
  errors.url = '';
  errors.events = '';
  errors.secret = '';
}

async function handleSave() {
  const found = validateWebhookModel(model);
  clearErrors();
  Object.assign(errors, found);
  if (Object.keys(found).length > 0) {
    return;
  }
  saving.value = true;
  try {
    const request = toWebhookSaveRequest(model);
    const result =
      editingId.value === null
        ? await createWebhook(request)
        : await updateWebhook(editingId.value, request);
    secretOnce.value = result.secretShownOnce ?? '';
    message.success('已保存，配置即时生效');
    await load();
    editing.value = false;
  } finally {
    saving.value = false;
  }
}

async function handleDelete(row: WebhookView) {
  await deleteWebhook(row.id);
  message.success('已删除，队列与投递记录一并清除');
  await load();
}

async function handleTest(row: WebhookView) {
  testing.value = row.id;
  try {
    const result = await testWebhook(row.id);
    if (result.status === 'SENT') {
      message.success('测试通过：接收方已应答 2xx');
    } else {
      Modal.error({
        content: `接收方未应答 2xx（${result.error ?? '未知原因'}）。签名头值：${result.signature}`,
        title: 'Webhook 测试失败',
      });
    }
    await load();
  } finally {
    testing.value = null;
  }
}

// ---------------------------------------------------------------- 投递记录
async function loadDeliveries(page = 1) {
  if (!deliveriesWebhook.value) {
    return;
  }
  deliveriesLoading.value = true;
  try {
    const result = await listWebhookDeliveries(deliveriesWebhook.value.id, {
      pageNo: page,
      pageSize: 20,
      status: deliveriesStatus.value,
    });
    deliveries.value = result.items;
    deliveriesTotal.value = result.total;
    deliveriesPage.value = result.pageNo;
  } finally {
    deliveriesLoading.value = false;
  }
}

function openDeliveries(row: WebhookView) {
  deliveriesWebhook.value = row;
  deliveriesStatus.value = undefined;
  deliveriesOpen.value = true;
  deliveries.value = [];
  deliveriesTotal.value = 0;
  void loadDeliveries(1);
}

async function handleRedeliver(row: WebhookDeliveryView) {
  if (!deliveriesWebhook.value) {
    return;
  }
  await redeliverWebhook(deliveriesWebhook.value.id, row.id);
  message.success('已重新排队，退避从第 1 次重计');
  await loadDeliveries(deliveriesPage.value);
}

const urlPlaceholder =
  'https://接收方地址/回调路径（仅 HTTPS 公网地址，内网与回环会被拒绝）';

const urlHelp =
  '保存与每次投递前都会校验：内网、回环、链路本地地址一律拒绝（防 SSRF）';
</script>

<template>
  <div>
    <Alert
      class="mb-4"
      message="Webhook 只推送事件类型与对象 ID，不含患者信息；接收方按 X-CRDP-Signature 验签（HMAC-SHA256，签名串 = 时间戳 + “.” + 请求体）。失败自动退避重试 3 次，耗尽后可在投递记录中手动重投。"
      show-icon
      type="info"
    />
    <Spin :spinning="loading">
      <Alert
        v-if="loadError"
        class="mb-4"
        :message="loadError"
        show-icon
        type="error"
      />
      <template v-else>
        <div class="mb-4 actions">
          <Button
            data-testid="webhook-create"
            type="primary"
            @click="openCreate"
          >
            新建 Webhook
          </Button>
        </div>
        <Table
          :columns="[
            { title: '名称', dataIndex: 'name', key: 'name' },
            { title: '回调地址', dataIndex: 'url', key: 'url' },
            { title: '订阅事件', key: 'events' },
            { title: '状态', key: 'enabled', width: 90 },
            { title: '最近投递', key: 'lastDeliveryAt', width: 170 },
            { title: '操作', key: 'actions', width: 280 },
          ]"
          :data-source="rows"
          :pagination="{ pageSize: 20, showSizeChanger: false }"
          row-key="id"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'url'">
              <span class="url">{{ record.url }}</span>
            </template>
            <template v-else-if="column.key === 'events'">
              <Tag v-for="event in record.events" :key="event">
                {{ webhookEventLabel(event) }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'enabled'">
              <Tag :color="record.enabled ? 'green' : 'default'">
                {{ record.enabled ? '启用' : '停用' }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'lastDeliveryAt'">
              <span :style="{ color: textSecondary }">
                {{ lastDeliveryText(record.lastDeliveryAt) }}
              </span>
            </template>
            <template v-else-if="column.key === 'actions'">
              <Button size="small" @click="openEdit(record)">编辑</Button>
              <Button
                class="ml-2"
                size="small"
                :loading="testing === record.id"
                @click="handleTest(record)"
              >
                测试
              </Button>
              <Button class="ml-2" size="small" @click="openDeliveries(record)">
                投递记录
              </Button>
              <Popconfirm
                title="删除后队列与投递记录一并清除，确定删除？"
                ok-text="删除"
                ok-type="danger"
                @confirm="handleDelete(record)"
              >
                <Button class="ml-2" danger size="small">删除</Button>
              </Popconfirm>
            </template>
          </template>
          <template #emptyText>
            <Empty
              description="暂无 Webhook，点击「新建 Webhook」配置回调地址"
            />
          </template>
        </Table>
      </template>
    </Spin>

    <!-- 新建 / 编辑 -->
    <Modal
      v-model:open="editing"
      :title="editingId === null ? '新建 Webhook' : '编辑 Webhook'"
      :width="640"
      :confirm-loading="saving"
      ok-text="保存"
      @ok="handleSave"
    >
      <Alert
        v-if="secretOnce"
        class="mb-4"
        :message="`签名密钥（仅本次显示，请立即配置到接收方）：${secretOnce}`"
        show-icon
        type="warning"
      />
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem
          label="名称"
          :validate-status="errors.name ? 'error' : ''"
          :help="errors.name"
        >
          <Input
            v-model:value="model.name"
            data-testid="webhook-name"
            :maxlength="WEBHOOK_NAME_MAX"
            placeholder="如：随访集成（示例）"
          />
        </FormItem>
        <FormItem
          label="回调地址"
          :validate-status="errors.url ? 'error' : ''"
          :help="errors.url || urlHelp"
        >
          <Input
            v-model:value="model.url"
            data-testid="webhook-url"
            :maxlength="WEBHOOK_URL_MAX"
            :placeholder="urlPlaceholder"
          />
        </FormItem>
        <FormItem
          label="订阅事件"
          :validate-status="errors.events ? 'error' : ''"
          :help="
            errors.events ||
            '只推送订阅的事件；医院 C 不需要「患者死亡」时不勾选即可'
          "
        >
          <CheckboxGroup
            v-model:value="model.events"
            data-testid="webhook-events"
            :options="
              events.map((option) => ({
                label: option.label,
                value: option.code,
              }))
            "
          />
        </FormItem>
        <FormItem
          :label="
            editingId === null
              ? '签名密钥（留空系统生成）'
              : '签名密钥（留空保持不变）'
          "
          :validate-status="errors.secret ? 'error' : ''"
          :help="
            errors.secret || 'HMAC-SHA256 密钥；填写即更换并只在保存后回显一次'
          "
        >
          <Textarea
            v-model:value="model.secret"
            :rows="2"
            :maxlength="128"
            placeholder="留空 = 系统生成 / 保持现有密钥"
          />
        </FormItem>
        <FormItem label="启用">
          <Switch
            v-model:checked="model.enabled"
            data-testid="webhook-enabled"
          />
          <span class="ml-2" :style="{ color: textSecondary }">
            停用后不再排队与投递
          </span>
        </FormItem>
      </Form>
    </Modal>

    <!-- 投递记录 -->
    <Modal
      v-model:open="deliveriesOpen"
      :title="`投递记录${deliveriesWebhook ? `：${deliveriesWebhook.name}` : ''}`"
      :width="900"
      :footer="null"
    >
      <div class="mb-4 filter">
        <Select
          allow-clear
          placeholder="按状态筛选"
          :style="{ width: '160px' }"
          :value="deliveriesStatus"
          :options="[
            { value: 'SENT', label: '已投递' },
            { value: 'PENDING', label: '待投递' },
            { value: 'FAILED', label: '失败' },
          ]"
          @change="() => loadDeliveries(1)"
          @update:value="deliveriesStatus = $event"
        />
      </div>
      <Spin :spinning="deliveriesLoading">
        <Table
          :columns="[
            {
              title: '事件',
              dataIndex: 'eventType',
              key: 'eventType',
              width: 130,
            },
            { title: '对象', key: 'object', width: 130 },
            { title: '状态', key: 'status', width: 90 },
            { title: '尝试', dataIndex: 'attempt', key: 'attempt', width: 70 },
            { title: '响应码', key: 'responseStatus', width: 80 },
            { title: '失败原因', key: 'error' },
            { title: '投递时间', key: 'sentAt', width: 170 },
            { title: '操作', key: 'actions', width: 100 },
          ]"
          :data-source="deliveries"
          :pagination="{
            pageSize: 20,
            showSizeChanger: false,
            total: deliveriesTotal,
            current: deliveriesPage,
            onChange: loadDeliveries,
          }"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'eventType'">
              {{ webhookEventLabel(record.eventType) }}
            </template>
            <template v-else-if="column.key === 'object'">
              {{ record.objectType }} {{ record.objectId ?? '' }}
            </template>
            <template v-else-if="column.key === 'status'">
              <Tag
                :color="
                  record.status === 'SENT'
                    ? 'green'
                    : record.status === 'FAILED'
                      ? 'red'
                      : 'default'
                "
              >
                {{ webhookDeliveryStatusText(record.status) }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'responseStatus'">
              {{ record.responseStatus ?? '—' }}
            </template>
            <template v-else-if="column.key === 'error'">
              <span :style="{ color: textSecondary }">{{
                record.error ?? '—'
              }}</span>
            </template>
            <template v-else-if="column.key === 'sentAt'">
              {{ record.sentAt ?? record.createdAt }}
            </template>
            <template v-else-if="column.key === 'actions'">
              <Button
                v-if="record.status === 'FAILED'"
                size="small"
                type="link"
                @click="handleRedeliver(record)"
              >
                重投
              </Button>
              <span v-else :style="{ color: textSecondary }">—</span>
            </template>
          </template>
          <template #emptyText>
            <Empty description="暂无投递记录" />
          </template>
        </Table>
      </Spin>
    </Modal>
  </div>
</template>

<style scoped>
.actions {
  display: flex;
  justify-content: flex-end;
}

.url {
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}

.event-option {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  margin-right: 16px;
}

.filter {
  display: flex;
  gap: 8px;
}
</style>
