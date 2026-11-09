<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

/**
 * E16-T10 / A-ATT-015、C-SCAN-008、C-NFR-006（pages.md §6.2 手机上传页，H5）：
 * 扫码进入（#/u/{一次性令牌}，15 分钟有效）→ 患者脱敏信息 → 选类型与位置（下拉、
 * 至少 1 个、二维码自动预选）→ 拍照或选图（可连续多张，上传前压缩：长边 ≤ 2560、
 * 转 JPEG、去 EXIF——@crdp/photo 与电脑端同一链路）→ 提交 →「上传成功」。
 * 令牌过期 / 已用：显示「二维码已过期，请在电脑上重新生成」（OQ-25）。
 * C 租户（h5_upload_login 打开）须先登录本院账号（C-NFR-006），登录态存
 * sessionStorage（关闭标签页即清）。手机端不留存文件（C-SCAN-008）——照片只在
 * 内存队列，提交后即弃。
 */
import type { H5LocationOption, H5TypeOption } from './api';
import type { PhotoItem, UploadFormState } from './upload-flow';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { compressImageFile } from '@crdp/photo';
import {
  Alert,
  Button,
  ConfigProvider,
  DatePicker,
  Form,
  FormItem,
  Input,
  InputPassword,
  message,
  Progress,
  Select,
} from 'ant-design-vue';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import dayjs from 'dayjs';

import {
  clearSessionToken,
  fetchContext,
  fetchInfo,
  H5ApiError,
  hospitalLogin,
  saveSessionToken,
  sessionToken,
  submitUpload,
  uploadPhoto,
} from './api';
import {
  canSubmit,
  countdownText,
  createPhotoItem,
  formErrors,
  initialForm,
  photoStatusText,
  QR_EXPIRED_MESSAGE,
  queueSummary,
  submitLocations,
  tokenFromHash,
} from './upload-flow';

type Phase =
  | 'error'
  | 'expired'
  | 'form'
  | 'invalid-link'
  | 'loading'
  | 'login'
  | 'submitting'
  | 'success';

const phase = ref<Phase>('loading');
const errorMessage = ref('');
const token = ref('');
const tenantName = ref('');
const patientLabel = ref('');
const patientMeta = ref('');
const expiresAt = ref(0);
const nowSecond = ref(Math.floor(Date.now() / 1000));
const createdSummary = ref('');
const loginUsername = ref('');
const loginPassword = ref('');
const loginLoading = ref(false);

const types = ref<H5TypeOption[]>([]);
const locationOptions = ref<H5LocationOption[]>([]);
const form = ref<UploadFormState>({
  typeCode: null,
  nameId: null,
  reportDate: null,
  locationKeys: [],
});
const photos = ref<PhotoItem[]>([]);
const showErrors = ref(false);
const cameraInput = ref<HTMLInputElement | null>(null);
const galleryInput = ref<HTMLInputElement | null>(null);
/** 登录租户编码（引导信息带回；登录表单预填）。 */
let contextTenantCode = '';
/** 原始文件暂存（失败重试从压缩重新开始需要原文件；提交 / 移除即弃置，不落盘）。 */
const pendingFiles = new Map<number, File>();

const sessionActive = computed(() => Boolean(sessionToken()));
const errors = computed(() => formErrors(form.value, types.value));
const typeOptions = computed(() =>
  types.value.map((type) => ({ label: type.name, value: type.code })),
);
const nameOptions = computed(() => {
  const type = types.value.find((item) => item.code === form.value.typeCode);
  return (type?.names ?? []).map((name) => ({
    label: name.name,
    value: name.id,
  }));
});
const locationSelectOptions = computed(() =>
  locationOptions.value.map((option) => ({
    label: option.label,
    value: option.key,
  })),
);
const selectedType = computed(
  () => types.value.find((item) => item.code === form.value.typeCode) ?? null,
);
const queue = computed(() => queueSummary(photos.value));
const countdown = computed(() =>
  expiresAt.value > 0 ? countdownText(expiresAt.value, nowSecond.value) : '',
);

let clock: ReturnType<typeof setInterval> | undefined;

onMounted(async () => {
  clock = setInterval(() => {
    nowSecond.value = Math.floor(Date.now() / 1000);
    if (expiresAt.value > 0 && nowSecond.value >= expiresAt.value) {
      phase.value = 'expired';
    }
  }, 1000);
  const parsed = tokenFromHash(window.location.hash);
  if (!parsed) {
    phase.value = 'invalid-link';
    return;
  }
  token.value = parsed;
  await bootstrap();
});

onBeforeUnmount(() => {
  if (clock) {
    clearInterval(clock);
  }
});

/** 引导：租户与登录要求 →（按需登录 →）页面信息。 */
async function bootstrap() {
  try {
    const context = await fetchContext(token.value);
    tenantName.value = context.tenantName || context.tenantCode;
    contextTenantCode = context.tenantCode;
    expiresAt.value = context.expiresAtEpochSecond;
    if (context.requireLogin && !sessionToken()) {
      phase.value = 'login';
      return;
    }
    await loadInfo();
  } catch (error) {
    handleFlowError(error);
  }
}

async function loadInfo() {
  try {
    const info = await fetchInfo(token.value);
    patientLabel.value = info.patientName;
    patientMeta.value = [
      info.wardName,
      info.medicalRecordNo ? `病案号 ${info.medicalRecordNo}` : '',
    ]
      .filter(Boolean)
      .join(' · ');
    types.value = info.types;
    locationOptions.value = info.locationOptions;
    form.value = initialForm(info);
    expiresAt.value = info.expiresAtEpochSecond;
    phase.value = 'form';
  } catch (error) {
    handleFlowError(error);
  }
}

/** 40101 → 过期页；40100 → 登录页；其余进错误页（原文案展示）。 */
function handleFlowError(error: unknown) {
  if (error instanceof H5ApiError) {
    if (error.code === 40_101) {
      phase.value = 'expired';
      return;
    }
    if (error.code === 40_100) {
      phase.value = 'login';
      return;
    }
    errorMessage.value = error.message;
    phase.value = 'error';
    return;
  }
  errorMessage.value = '网络异常，请重试';
  phase.value = 'error';
}

async function handleLogin() {
  if (!loginUsername.value || !loginPassword.value) {
    message.warning('请输入本院账号与密码');
    return;
  }
  loginLoading.value = true;
  try {
    const result = await hospitalLogin(
      contextTenantCode,
      loginUsername.value.trim(),
      loginPassword.value,
    );
    saveSessionToken(result.token);
    loginPassword.value = '';
    await loadInfo();
  } catch (error) {
    message.error(
      error instanceof H5ApiError ? error.message : '网络异常，请重试',
    );
  } finally {
    loginLoading.value = false;
  }
}

function logout() {
  clearSessionToken();
  phase.value = 'login';
}

// ---------------------------------------------------------------- 拍照 / 选图

function onPickFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = [...(input.files ?? [])];
  input.value = '';
  for (const file of files) {
    void processPhoto(file);
  }
}

/** 单张：压缩（@crdp/photo，与电脑端同一链路）→ 上传（进度）→ 完成；失败可重试。 */
async function processPhoto(file: File, retryItem?: PhotoItem) {
  const item = retryItem ?? createPhotoItem(file.name);
  if (!retryItem) {
    photos.value.push(item);
  }
  pendingFiles.set(item.id, file);
  item.status = 'compressing';
  item.progress = 0;
  item.error = null;
  try {
    const compressed = await compressImageFile(file);
    if (compressed.note === 'undecodable') {
      message.warning(`「${file.name}」当前浏览器无法压缩，将按原文件上传`);
    }
    item.status = 'uploading';
    const uploaded = await uploadPhoto(
      token.value,
      compressed.file,
      (percent) => {
        item.progress = percent;
      },
    );
    item.fileId = uploaded.fileId;
    item.progress = 100;
    item.status = 'done';
  } catch (error) {
    item.status = 'failed';
    item.error =
      error instanceof H5ApiError ? error.message : '网络异常，请重试';
    if (error instanceof H5ApiError && error.code === 40_101) {
      phase.value = 'expired';
    }
  }
}

function retryPhoto(item: PhotoItem) {
  const file = pendingFiles.get(item.id);
  if (!file) {
    message.warning('原照片已不可用，请重新拍摄');
    removePhoto(item);
    return;
  }
  void processPhoto(file, item);
}

function removePhoto(item: PhotoItem) {
  photos.value = photos.value.filter((photo) => photo.id !== item.id);
  pendingFiles.delete(item.id);
}

// ---------------------------------------------------------------- 提交

async function handleSubmit() {
  showErrors.value = true;
  if (Object.keys(errors.value).length > 0) {
    message.warning(Object.values(errors.value)[0]);
    return;
  }
  if (!canSubmit(photos.value)) {
    message.warning('请等待全部照片上传完成（失败项可重试或移除）');
    return;
  }
  const { typeCode, reportDate } = form.value;
  if (!typeCode || !reportDate) {
    return;
  }
  phase.value = 'submitting';
  try {
    const created = await submitUpload(token.value, {
      typeCode,
      nameId: form.value.nameId,
      reportDate,
      locations: submitLocations(form.value, locationOptions.value),
    });
    createdSummary.value = `${created.typeName}${created.name ? ` · ${created.name}` : ''}（${created.fileCount} 张照片）`;
    photos.value = [];
    pendingFiles.clear();
    phase.value = 'success';
  } catch (error) {
    if (error instanceof H5ApiError && error.code === 40_101) {
      phase.value = 'expired';
      return;
    }
    message.error(
      error instanceof H5ApiError ? error.message : '网络异常，请重试',
    );
    phase.value = 'form';
  }
}

function onTypeChange(code: number | string | undefined) {
  form.value.typeCode = code ? String(code) : null;
  form.value.nameId = null;
}

function onDateChange(value: Dayjs | null | string) {
  form.value.reportDate = value ? dayjs(value).format('YYYY-MM-DD') : null;
}
</script>

<template>
  <ConfigProvider :locale="zhCN">
    <main class="page">
      <!-- 引导 / 加载 -->
      <section v-if="phase === 'loading'" class="state">
        <p>正在打开手机上传…</p>
      </section>

      <!-- 链接无效（不是扫码进入） -->
      <section v-else-if="phase === 'invalid-link'" class="state">
        <h1>请从电脑端二维码进入</h1>
        <p>本页只能通过「添加报告单 · 手机拍照上传」二维码打开。</p>
      </section>

      <!-- 二维码过期 / 已用（验收：二维码过期失效） -->
      <section
        v-else-if="phase === 'expired'"
        class="state"
        data-testid="qr-expired"
      >
        <h1>二维码已过期</h1>
        <p>{{ QR_EXPIRED_MESSAGE }}</p>
      </section>

      <!-- 错误 -->
      <section v-else-if="phase === 'error'" class="state">
        <h1>暂时无法上传</h1>
        <p>{{ errorMessage }}</p>
        <Button type="primary" @click="bootstrap">重试</Button>
      </section>

      <!-- C 租户：手机端登录本院账号（C-NFR-006） -->
      <section v-else-if="phase === 'login'" class="card" data-testid="login">
        <h1>{{ tenantName }}</h1>
        <p class="hint">请登录本院账号后上传（本院要求）</p>
        <Form layout="vertical" @submit.prevent>
          <FormItem label="账号">
            <Input
              v-model:value="loginUsername"
              autocomplete="username"
              placeholder="本院账号"
              size="large"
            />
          </FormItem>
          <FormItem label="密码">
            <InputPassword
              v-model:value="loginPassword"
              autocomplete="current-password"
              placeholder="密码"
              size="large"
              @press-enter="handleLogin"
            />
          </FormItem>
          <Button
            block
            :loading="loginLoading"
            size="large"
            type="primary"
            @click="handleLogin"
          >
            登录
          </Button>
        </Form>
      </section>

      <!-- 上传成功（pages.md §6.2：提交 →「上传成功」） -->
      <section
        v-else-if="phase === 'success'"
        class="state success"
        data-testid="success"
      >
        <h1>上传成功</h1>
        <p>{{ createdSummary }}</p>
        <p class="hint">请在电脑端继续处理；本页可安全关闭。</p>
      </section>

      <!-- 主表单 -->
      <template v-else>
        <section class="card" data-testid="upload-form">
          <header class="patient">
            <strong>{{ patientLabel }}</strong>
            <span class="hint">{{ patientMeta }}</span>
          </header>
          <p class="hint">照片将上传到该患者的报告单（{{ tenantName }}）</p>

          <Form layout="vertical" @submit.prevent>
            <FormItem
              label="资料类型"
              :help="showErrors ? errors.typeCode : undefined"
              :validate-status="
                showErrors && errors.typeCode ? 'error' : undefined
              "
            >
              <Select
                :options="typeOptions"
                :value="form.typeCode"
                placeholder="请选择资料类型"
                size="large"
                @change="onTypeChange"
              />
            </FormItem>
            <FormItem
              v-if="selectedType?.nameRequired"
              label="名称"
              :help="showErrors ? errors.nameId : undefined"
              :validate-status="
                showErrors && errors.nameId ? 'error' : undefined
              "
            >
              <Select
                v-model:value="form.nameId"
                :options="nameOptions"
                placeholder="请选择名称"
                size="large"
              />
            </FormItem>
            <FormItem
              label="报告时间"
              :help="showErrors ? errors.reportDate : undefined"
              :validate-status="
                showErrors && errors.reportDate ? 'error' : undefined
              "
            >
              <DatePicker
                :value="form.reportDate ? dayjs(form.reportDate) : null"
                size="large"
                style="width: 100%"
                @change="onDateChange"
              />
            </FormItem>
            <FormItem
              label="位置（至少 1 个，可多选）"
              :help="showErrors ? errors.locations : undefined"
              :validate-status="
                showErrors && errors.locations ? 'error' : undefined
              "
            >
              <Select
                v-model:value="form.locationKeys"
                :max-tag-count="2"
                :options="locationSelectOptions"
                mode="multiple"
                placeholder="请选择位置"
                size="large"
              />
            </FormItem>
          </Form>

          <div class="photo-actions">
            <Button size="large" @click="cameraInput?.click()">拍照</Button>
            <Button size="large" @click="galleryInput?.click()">
              相册选图
            </Button>
            <span class="hint">已上传 {{ queue.done }}/{{ queue.total }}</span>
          </div>
          <input
            ref="cameraInput"
            accept="image/*"
            capture="environment"
            hidden
            multiple
            type="file"
            @change="onPickFiles"
          />
          <input
            ref="galleryInput"
            accept="image/*"
            hidden
            multiple
            type="file"
            @change="onPickFiles"
          />

          <ul
            v-if="photos.length > 0"
            class="photo-list"
            data-testid="photo-list"
          >
            <li v-for="item in photos" :key="item.id">
              <div class="photo-line">
                <span class="photo-name">{{ item.name }}</span>
                <span class="photo-status" :class="[item.status]">
                  {{ item.error ?? photoStatusText(item.status) }}
                </span>
              </div>
              <Progress
                v-if="item.status === 'uploading'"
                :percent="item.progress"
                size="small"
                status="active"
              />
              <div v-if="item.status === 'failed'" class="photo-retry">
                <a @click="retryPhoto(item)">重试</a>
                <a class="danger" @click="removePhoto(item)">移除</a>
              </div>
            </li>
          </ul>
        </section>

        <Alert
          v-if="sessionActive"
          class="session-note"
          message="已登录本院账号"
          type="info"
          show-icon
        >
          <template #action>
            <a @click="logout">退出</a>
          </template>
        </Alert>

        <footer class="submit-bar">
          <span class="hint">二维码 {{ countdown }} 后过期</span>
          <Button
            :disabled="phase === 'submitting'"
            :loading="phase === 'submitting'"
            size="large"
            type="primary"
            @click="handleSubmit"
          >
            提交（{{ queue.done }} 张）
          </Button>
        </footer>
      </template>
    </main>
  </ConfigProvider>
</template>

<style>
:root {
  font-family: 'PingFang SC', 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
  color: #262626;
}

body {
  margin: 0;
  background: #f3f6f5;
}

.page {
  box-sizing: border-box;
  max-width: 32rem;
  min-height: 100vh;
  padding: 16px 16px 96px;
  margin: 0 auto;
}

.card {
  padding: 16px;
  background: #fff;
  border-radius: 8px;
}

.state {
  padding: 48px 16px;
  text-align: center;
}

.state h1 {
  margin-bottom: 8px;
  font-size: 20px;
}

.state.success h1 {
  color: #1dc29e;
}

.hint {
  font-size: 13px;
  color: #8c8c8c;
}

.patient {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 4px;
}

.photo-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 8px 0 12px;
}

.photo-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.photo-line {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  font-size: 14px;
}

.photo-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-status.done {
  color: #1dc29e;
}

.photo-status.failed {
  color: #ff4d4f;
}

.photo-retry {
  display: flex;
  gap: 16px;
  font-size: 13px;
}

.photo-retry .danger {
  color: #ff4d4f;
}

.submit-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 32rem;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  margin: 0 auto;
  background: #fff;
  border-top: 1px solid #e9e9e9;
}

.session-note {
  margin-top: 12px;
}

/* 手机输入 36 高、16 号字（design-system §12；触控 ≥ 40px 由 large 控件保证） */
.page :deep(.ant-select-selection-item),
.page :deep(.ant-input),
.page :deep(.ant-picker) {
  min-height: 36px;
  font-size: 16px;
}

.page :deep(.ant-select-single .ant-select-selector) {
  height: 40px;
}

.page :deep(.ant-btn) {
  min-height: 40px;
}
</style>
