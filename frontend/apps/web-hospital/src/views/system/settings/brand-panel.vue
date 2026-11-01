<script lang="ts" setup>
import type { BrandSaveRequest, BrandView } from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';

import {
  isValidAssetUrl,
  THEME_COLOR_OPTIONS,
  themeColorHexOf,
} from '@crdp/api';
import { colors, sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  message,
  Result,
  Spin,
  Tag,
  Textarea,
} from 'ant-design-vue';

import { getBrand, saveBrand } from '#/api/core/brand';
import { useBreakpoint } from '#/reference/use-breakpoint';

defineOptions({ name: 'HospitalSettingsBrand' });

/**
 * E06-T11 / PLT-CFG-008 · pages.md §10「机构设置 · 个性化」：本院个性化（显示名称、Logo、
 * 登录页背景、预设主题色、版权信息）。保存即时生效（登录页 /auth/tenant 现读）并写审计。
 * 主题色只能从系统预设色板中选择（OQ-23：主色固定 #3A75FF）；素材地址在对象存储
 * （E10）接入前先存 URL（OQ-49），上传控件随 PLT-CFG-010 落地。
 */
const { isMobile } = useBreakpoint();

const loading = ref(false);
const saving = ref(false);
const loaded = ref(false);
const loadError = ref('');
const updatedAt = ref<null | string>(null);
const defaultName = ref('');
const formRef = ref();

const form = reactive({
  copyright: '',
  displayName: '',
  loginBgUrl: '',
  logoUrl: '',
  themeColor: 'BLUE' as string,
});

const displayNameMax = 128;
const copyrightMax = 255;
const textSecondary = colors.textSecondary;
const fieldWidth = sizes.fieldWidth.shortText;

const rules = {
  copyright: [{ max: copyrightMax, message: `不超过 ${copyrightMax} 个字符` }],
  displayName: [
    { required: true, message: '显示名称不能为空', whitespace: true },
    { max: displayNameMax, message: `不超过 ${displayNameMax} 个字符` },
  ],
  loginBgUrl: [{ validator: validateAsset('登录页背景地址') }],
  logoUrl: [{ validator: validateAsset('Logo 地址') }],
};

function validateAsset(label: string) {
  return (_rule: unknown, value: string, callback: (error?: Error) => void) => {
    if (!isValidAssetUrl(value)) {
      callback(new Error(`${label}必须是 http(s) 地址或站内路径`));
      return;
    }
    callback();
  };
}

const selectedHex = computed(() => themeColorHexOf(form.themeColor));

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const brand = await getBrand();
    applyBrand(brand);
    loaded.value = true;
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function applyBrand(brand: BrandView) {
  defaultName.value = brand.defaultName;
  form.displayName = brand.displayName ?? brand.defaultName;
  form.logoUrl = brand.logoUrl ?? '';
  form.loginBgUrl = brand.loginBgUrl ?? '';
  form.themeColor = brand.themeColor ?? 'BLUE';
  form.copyright = brand.copyright ?? '';
  updatedAt.value = brand.updatedAt;
}

async function handleSave() {
  const valid = await formRef.value?.validate();
  if (!valid) {
    return;
  }
  saving.value = true;
  try {
    const payload: BrandSaveRequest = {
      copyright: form.copyright.trim() === '' ? null : form.copyright.trim(),
      displayName: form.displayName.trim(),
      loginBgUrl: form.loginBgUrl.trim() === '' ? null : form.loginBgUrl.trim(),
      logoUrl: form.logoUrl.trim() === '' ? null : form.logoUrl.trim(),
      themeColor: form.themeColor,
    };
    const saved = await saveBrand(payload);
    applyBrand(saved);
    message.success('已保存，登录页与医院名称即时生效');
  } finally {
    saving.value = false;
  }
}

function handleReset() {
  form.displayName = defaultName.value;
  form.logoUrl = '';
  form.loginBgUrl = '';
  form.themeColor = 'BLUE';
  form.copyright = '';
}

onMounted(load);
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="$router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <div v-else>
    <Spin :spinning="loading">
      <Alert
        v-if="loadError"
        class="mb-4"
        :message="loadError"
        show-icon
        type="error"
      />
      <template v-else-if="loaded">
        <Form
          ref="formRef"
          class="brand-form"
          :label-col="{ span: 0 }"
          layout="vertical"
          :model="form"
          :rules="rules"
          @keyup.enter.prevent
        >
          <FormItem label="显示名称" name="displayName">
            <Input
              v-model:value="form.displayName"
              :maxlength="displayNameMax"
              placeholder="医院对外名称，默认取开通时的医院名称"
              :style="{ width: `${fieldWidth}px` }"
            />
            <p class="hint" :style="{ color: textSecondary }">
              未配置时使用平台登记的医院名称：{{ defaultName }}（示例）
            </p>
          </FormItem>

          <FormItem label="Logo 地址" name="logoUrl">
            <Input
              v-model:value="form.logoUrl"
              allow-clear
              placeholder="PNG 或 SVG 素材地址（https://… 或 /assets/…），不超过 1MB"
              :style="{ width: `${fieldWidth}px` }"
            />
            <div v-if="form.logoUrl" class="preview">
              <img alt="Logo 预览" :src="form.logoUrl" />
            </div>
          </FormItem>

          <FormItem label="登录页背景地址" name="loginBgUrl">
            <Input
              v-model:value="form.loginBgUrl"
              allow-clear
              placeholder="未配置使用平台默认背景"
              :style="{ width: `${fieldWidth}px` }"
            />
            <div v-if="form.loginBgUrl" class="preview preview-bg">
              <img alt="背景预览" :src="form.loginBgUrl" />
            </div>
          </FormItem>

          <FormItem label="主题色（仅系统预设）">
            <div class="palette">
              <button
                v-for="color in THEME_COLOR_OPTIONS"
                :key="color.key"
                class="swatch"
                :class="{ active: form.themeColor === color.key }"
                :style="{ background: color.hex }"
                type="button"
                :aria-label="color.label"
                :title="color.label"
                @click="form.themeColor = color.key"
              ></button>
              <span class="hint" :style="{ color: textSecondary }">
                当前：{{ selectedHex }}（主色固定 #3A75FF，预设色不改变主色）
              </span>
            </div>
          </FormItem>

          <FormItem label="版权与备案信息" name="copyright">
            <Textarea
              v-model:value="form.copyright"
              :maxlength="copyrightMax"
              :rows="2"
              placeholder="显示在登录页底部，可留空"
              :style="{ width: `${fieldWidth}px` }"
            />
          </FormItem>

          <div class="actions">
            <Button
              type="primary"
              data-testid="brand-save"
              :loading="saving"
              @click="handleSave"
            >
              保存
            </Button>
            <Button data-testid="brand-reset" @click="handleReset">
              恢复默认
            </Button>
            <Tag v-if="updatedAt" class="ml-2"> 最近更新：{{ updatedAt }} </Tag>
          </div>
        </Form>
      </template>
    </Spin>
  </div>
</template>

<style scoped>
.hint {
  margin: 4px 0 0;
  font-size: 12px;
}

.brand-form {
  max-width: 560px;
}

.preview {
  margin-top: 8px;
}

.preview img {
  max-width: 160px;
  max-height: 64px;
  border: 1px solid #e9e9e9;
  border-radius: 6px;
}

.preview-bg img {
  max-width: 320px;
  max-height: 128px;
}

.palette {
  display: flex;
  gap: 8px;
  align-items: center;
}

.swatch {
  width: 24px;
  height: 24px;
  padding: 0;
  cursor: pointer;
  border: 2px solid transparent;
  border-radius: 6px;
}

.swatch.active {
  border-color: #262626;
}

.actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
