<script lang="ts" setup>
import type {
  DuplicateNotice,
  ImplantForm,
  ImplantFormErrors,
  ImplantOptions,
  PatientDuplicateCheckResult,
  PatientRegistrationForm,
  PatientRegistrationOptions,
} from '@crdp/api';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  buildDuplicateCheckPayload,
  buildImplantPayload,
  buildRegistrationPayload,
  duplicateNoticeOf,
  emptyImplantForm,
  validateCRegistration,
  validateRegistration,
} from '@crdp/api';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Form,
  FormItem,
  Input,
  message,
  Result,
  Select,
  SelectOption,
  Spin,
  Tooltip,
} from 'ant-design-vue';

import { fetchImplantOptions } from '#/api/core/implant';
import {
  checkPatientDuplicate,
  createPatientRegistration,
  fetchPatientRegistrationOptions,
} from '#/api/core/patient';
import ImplantFields from '#/components/followup/implant-fields.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { useFeature } from '#/reference/use-feature';

import { noticeFromSubmitError, patientDetailPath } from './new-logic';

defineOptions({ name: 'FollowupPatientNew' });

/**
 * E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002 · 新建患者（出院登记）：
 * 第一步填最小必填集（病案号、姓名、性别、病区、手术 / 入院 / 出院日期、出院状态），
 * 其余基线分类保存后进入患者档案逐项补录（A 规格 §4 主要页面 2）。
 * 病案号实时查重：重复立即提示（红色，阻止保存）并给出已有患者链接；姓名 + 出生日期
 * 相同提示可能重复（橙色，不阻止）。仅桌面 / 平板（pages.md §0.1 响应式：否）。
 */
const router = useRouter();
const { isMobile } = useBreakpoint();

const loadingOptions = ref(false);
const options = ref<PatientRegistrationOptions>({
  wards: [],
  depts: [],
  genders: [
    { code: 'M', label: '男' },
    { code: 'F', label: '女' },
  ],
  dischargeStatuses: [],
});

const form = reactive<PatientRegistrationForm>({
  medicalRecordNo: '',
  name: '',
  gender: undefined,
  birthDate: null,
  wardId: undefined,
  deptId: null,
  surgeryDate: null,
  admissionDate: null,
  dischargeDate: null,
  dischargeStatus: undefined,
  legacyNo: '',
});

const errors = ref<Partial<Record<keyof PatientRegistrationForm, string>>>({});

// E21-T01 / OQ-71①：implant 开关开启 → 医院 C 登记口径（C-PAT-001 最小必填：
// 姓名、性别、出生日期、病历号、科室、病区 + 须同时建立第一条植入记录）；
// 关闭 → A 出院登记口径不变（A / B 租户不受影响）
const { isEnabled } = useFeature();
const implantMode = computed(() => isEnabled('implant'));
const implantForm = reactive<ImplantForm>(emptyImplantForm());
const implantOptions = ref<ImplantOptions | null>(null);
const implantErrors = ref<ImplantFormErrors>({});

watch(
  implantMode,
  (enabled) => {
    if (!enabled || implantOptions.value) return;
    fetchImplantOptions()
      .then((data) => {
        implantOptions.value = data;
      })
      .catch(() => message.error('植入选项加载失败，请刷新重试'));
  },
  { immediate: true },
);
const checking = ref(false);
const submitting = ref(false);
const duplicateNotice = ref<DuplicateNotice>(null);

/** 病案号重复（红色）时保存置灰；可能重复（橙色）只提示。 */
const blocked = computed(() => duplicateNotice.value?.kind === 'blocked');

// ---------------------------------------------------------------- 候选与查重

async function loadOptions() {
  loadingOptions.value = true;
  try {
    options.value = await fetchPatientRegistrationOptions();
  } catch {
    message.error('登记候选加载失败，请刷新重试');
  } finally {
    loadingOptions.value = false;
  }
}
void loadOptions();

/** 查重防抖（500ms）：病案号（或姓名 + 出生日期）变化即重新查。 */
let checkTimer: ReturnType<typeof setTimeout> | undefined;

function scheduleDuplicateCheck() {
  if (checkTimer) clearTimeout(checkTimer);
  checkTimer = setTimeout(() => void runDuplicateCheck(), 500);
}

async function runDuplicateCheck() {
  const payload = buildDuplicateCheckPayload(form);
  if (!payload) {
    duplicateNotice.value = null;
    return;
  }
  checking.value = true;
  try {
    const result: PatientDuplicateCheckResult =
      await checkPatientDuplicate(payload);
    duplicateNotice.value = duplicateNoticeOf(result);
  } catch {
    // 查重是辅助提示，失败不阻断填写；提交期 40902 仍会兜底阻止
    duplicateNotice.value = null;
  } finally {
    checking.value = false;
  }
}

watch(
  () => [form.medicalRecordNo, form.name, form.birthDate],
  () => {
    errors.value = {};
    scheduleDuplicateCheck();
  },
);

onBeforeUnmount(() => {
  if (checkTimer) clearTimeout(checkTimer);
});

/** 可能重复项的一行摘要（脱敏姓名 · 病区 · 出院日期）。 */
function matchLabel(item: {
  dischargeDate?: null | string;
  nameMasked: string;
  wardName?: null | string;
}): string {
  const parts = [item.nameMasked, item.wardName || '病区未知'];
  if (item.dischargeDate) parts.push(`出院 ${item.dischargeDate}`);
  return parts.join(' · ');
}

// ---------------------------------------------------------------- 保存

/** C 模式校验（患者 C 最小必填 + 首条植入记录），错误按前缀拆回两块表单。 */
function validateForMode() {
  if (!implantMode.value) {
    errors.value = validateRegistration(form);
    implantErrors.value = {};
    return;
  }
  const merged = validateCRegistration(form, implantForm, implantOptions.value);
  const patientErrors: Partial<Record<keyof PatientRegistrationForm, string>> =
    {};
  const leadErrors: ImplantFormErrors = {};
  for (const [key, value] of Object.entries(merged)) {
    if (key.startsWith('patient.'))
      patientErrors[key.slice(8) as keyof PatientRegistrationForm] = value;
    else if (key.startsWith('implant.')) leadErrors[key.slice(8)] = value;
  }
  errors.value = patientErrors;
  implantErrors.value = leadErrors;
}

async function submit() {
  validateForMode();
  if (
    Object.keys(errors.value).length > 0 ||
    Object.keys(implantErrors.value).length > 0
  ) {
    message.warning('请先补全必填项');
    return;
  }
  if (blocked.value) {
    message.warning('病案号已在本院登记，请核对后再保存');
    return;
  }
  submitting.value = true;
  try {
    const created = await createPatientRegistration({
      ...buildRegistrationPayload(form),
      // C-PAT-001：登记同时建立首条植入记录（implant 开关开启时后端必校验）
      ...(implantMode.value
        ? { implant: buildImplantPayload(implantForm) }
        : {}),
    });
    message.success('登记成功，正在进入患者档案');
    void router.push(patientDetailPath(created.id));
  } catch (error) {
    const notice = noticeFromSubmitError(error);
    if (notice) {
      // 并发登记 / 提交期发现重复：与实时查重同一口径（提示并阻止）
      duplicateNotice.value = notice;
      message.warning('病案号已在本院登记，已阻止保存');
    } else {
      const body = error as { message?: string };
      message.error(body?.message || '登记失败，请稍后重试');
    }
  } finally {
    submitting.value = false;
  }
}
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
    :title="implantMode ? '新建患者 · 患者登记' : '新建患者 · 出院登记'"
    class="patient-new"
  >
    <Card :bordered="false">
      <Spin :spinning="loadingOptions">
        <Alert
          v-if="duplicateNotice"
          :type="duplicateNotice.kind === 'blocked' ? 'error' : 'warning'"
          show-icon
          class="duplicate-alert"
          :message="
            duplicateNotice.kind === 'blocked'
              ? '病案号已存在（已阻止保存）'
              : '可能重复登记'
          "
        >
          <template #description>
            <p>{{ duplicateNotice.text }}</p>
            <template
              v-if="
                duplicateNotice.kind === 'blocked' && duplicateNotice.patient
              "
            >
              <Button
                size="small"
                type="primary"
                ghost
                @click="
                  router.push(patientDetailPath(duplicateNotice.patient!.id))
                "
              >
                打开已有患者档案
              </Button>
            </template>
            <template v-else-if="duplicateNotice.kind === 'possible'">
              <ul class="possible-list">
                <li v-for="item in duplicateNotice.matches" :key="item.id">
                  <span>{{ matchLabel(item) }}</span>
                  <Button
                    size="small"
                    type="link"
                    @click="router.push(patientDetailPath(item.id))"
                  >
                    查看
                  </Button>
                </li>
              </ul>
            </template>
          </template>
        </Alert>

        <Form layout="vertical" class="new-form">
          <div class="group-title">基本信息</div>
          <div class="form-grid">
            <FormItem
              label="病案号"
              required
              :validate-status="errors.medicalRecordNo ? 'error' : undefined"
              :help="errors.medicalRecordNo || '本院唯一；输入后自动查重'"
            >
              <Input
                v-model:value="form.medicalRecordNo"
                data-testid="mrn-input"
                :maxlength="64"
                placeholder="如 DEMO-A-0002（示例）"
                allow-clear
              >
                <template #suffix>
                  <Spin v-if="checking" size="small" />
                </template>
              </Input>
            </FormItem>
            <FormItem
              label="姓名"
              required
              :validate-status="errors.name ? 'error' : undefined"
              :help="errors.name"
            >
              <Input
                v-model:value="form.name"
                :maxlength="64"
                placeholder="如 张小明（示例）"
              />
            </FormItem>
            <FormItem
              label="性别"
              required
              :validate-status="errors.gender ? 'error' : undefined"
              :help="errors.gender"
            >
              <Select v-model:value="form.gender" placeholder="请选择">
                <SelectOption
                  v-for="g in options.genders"
                  :key="g.code"
                  :value="g.code"
                >
                  {{ g.label }}
                </SelectOption>
              </Select>
            </FormItem>
            <FormItem
              :required="implantMode"
              label="出生日期"
              :validate-status="errors.birthDate ? 'error' : undefined"
              :help="
                errors.birthDate ||
                (implantMode ? undefined : 'C 登记必填；A 可空')
              "
            >
              <DatePicker
                v-model:value="form.birthDate"
                value-format="YYYY-MM-DD"
                style="width: 100%"
                placeholder="如 1955-01-01"
              />
            </FormItem>
            <FormItem
              label="病区"
              required
              :validate-status="errors.wardId ? 'error' : undefined"
              :help="errors.wardId"
            >
              <Select v-model:value="form.wardId" placeholder="请选择">
                <SelectOption
                  v-for="w in options.wards"
                  :key="w.id"
                  :value="w.id"
                >
                  {{ w.name }}
                </SelectOption>
              </Select>
            </FormItem>
            <FormItem
              :required="implantMode"
              label="科室"
              :validate-status="errors.deptId ? 'error' : undefined"
              :help="errors.deptId"
            >
              <Select
                v-model:value="form.deptId"
                :options="
                  options.depts.map((d) => ({ label: d.name, value: d.id }))
                "
                allow-clear
                :placeholder="implantMode ? '请选择' : '可空'"
              />
            </FormItem>
          </div>

          <!-- C 登记口径无住院 / 出院字段（C 规格 §4：原表无此三项，附录 A 患者 #1–#21） -->
          <template v-if="!implantMode">
            <div class="group-title">住院与出院</div>
            <div class="form-grid">
              <FormItem
                label="入院日期"
                required
                :validate-status="errors.admissionDate ? 'error' : undefined"
                :help="errors.admissionDate"
              >
                <DatePicker
                  v-model:value="form.admissionDate"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                />
              </FormItem>
              <FormItem
                label="手术日期"
                required
                :validate-status="errors.surgeryDate ? 'error' : undefined"
                :help="errors.surgeryDate"
              >
                <DatePicker
                  v-model:value="form.surgeryDate"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                />
              </FormItem>
              <FormItem
                label="出院日期"
                required
                :validate-status="errors.dischargeDate ? 'error' : undefined"
                :help="errors.dischargeDate"
              >
                <DatePicker
                  v-model:value="form.dischargeDate"
                  value-format="YYYY-MM-DD"
                  style="width: 100%"
                />
              </FormItem>
              <FormItem
                label="出院状态"
                required
                :validate-status="errors.dischargeStatus ? 'error' : undefined"
                :help="errors.dischargeStatus"
              >
                <Select
                  v-model:value="form.dischargeStatus"
                  placeholder="请选择"
                >
                  <SelectOption
                    v-for="s in options.dischargeStatuses"
                    :key="s.code"
                    :value="Number(s.code)"
                  >
                    {{ s.label }}
                  </SelectOption>
                </Select>
              </FormItem>
              <FormItem label="原系统编号" help="历史数据迁移时保留，可空">
                <Input
                  v-model:value="form.legacyNo"
                  :maxlength="64"
                  placeholder="如 OLD-A-000123（示例）"
                />
              </FormItem>
            </div>
          </template>

          <template v-else>
            <div class="group-title group-title-implant">
              首条植入记录与电极（C-PAT-001 / C-PAT-004 / C-PAT-005）
            </div>
            <ImplantFields
              v-model:form="implantForm"
              :errors="implantErrors"
              :options="implantOptions"
            />
            <FormItem label="原系统编号" help="历史数据迁移时保留，可空">
              <Input
                v-model:value="form.legacyNo"
                :maxlength="64"
                placeholder="如 OLD-C-000123（示例）"
              />
            </FormItem>
          </template>
        </Form>

        <div class="form-actions">
          <Button @click="router.back()">取消</Button>
          <Tooltip
            :title="blocked ? '病案号已在本院登记，不能重复建档' : undefined"
          >
            <Button
              type="primary"
              data-testid="submit"
              :disabled="blocked"
              :loading="submitting"
              @click="submit"
            >
              保存
            </Button>
          </Tooltip>
        </div>
      </Spin>
    </Card>
  </Page>
</template>

<style scoped>
.patient-new {
  max-width: 1080px;
}

.duplicate-alert {
  margin-bottom: 16px;
}

.group-title {
  margin: 8px 0 16px;
  font-size: 14px;
  font-weight: 600;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}

.possible-list {
  padding-left: 18px;
  margin: 4px 0 0;
}

.form-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
