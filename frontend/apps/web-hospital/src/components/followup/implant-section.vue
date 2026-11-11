<script lang="ts" setup>
import type { ImplantRecordView } from '@crdp/api';

import { computed, onMounted, ref } from 'vue';

import {
  IMPLANT_TYPE_LEAD_REVISION,
  IMPLANT_TYPE_REPLACE,
  implantCardLines,
} from '@crdp/api';
import {
  Button,
  Empty,
  message,
  Spin,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import { fetchPatientImplants } from '#/api/core/implant';
import ImplantExplantModal from '#/components/followup/implant-explant-modal.vue';
import ImplantFormModal from '#/components/followup/implant-form-modal.vue';
import ImplantReplaceModal from '#/components/followup/implant-replace-modal.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

/**
 * E21-T01 / C-PAT-004、C-PAT-005 · 基线资料页签「植入记录」区块（pages.md §4.4）：
 * 按时间列出全部植入记录与电极，当前器械置顶。E21-T02 / C-PAT-006 · 操作：新建（首条 /
 * 补录历史器械）、更换 / 升级、电极修正（同一弹窗，目标 = 当前器械，旧器械自动结束为
 * 已更换、电极逐根沿用 / 废弃留置 / 已拔除或新植）、取出、编辑。手机表格转卡片
 * （pages.md §4.10，OQ-114①）。写入按钮 followup:implant:edit（无权限隐藏，AGENTS §7）；
 * 区块由 useFeature('implant') 控制显隐（components.md §1.3）。
 */
const props = defineProps<{
  patientId: string;
}>();

const emit = defineEmits<{
  (event: 'changed'): void;
}>();

const { hasPermission } = usePermission();
const { isMobile } = useBreakpoint();
const canEdit = computed(() => hasPermission('followup:implant:edit'));

const loading = ref(false);
const records = ref<ImplantRecordView[]>([]);
const modalOpen = ref(false);
const editing = ref<ImplantRecordView | null>(null);
const replaceOpen = ref(false);
const replaceInitialType = ref<string>(IMPLANT_TYPE_REPLACE);
const explantOpen = ref(false);

/** 当前（在用）器械：更换 / 升级、电极修正、取出的目标（C-PAT-007）。 */
const currentRecord = computed(
  () => records.value.find((record) => record.current) ?? null,
);

async function load() {
  loading.value = true;
  try {
    const view = await fetchPatientImplants(props.patientId);
    records.value = view.items;
  } catch {
    message.error('植入记录加载失败，请刷新重试');
  } finally {
    loading.value = false;
  }
}

onMounted(() => void load());

function openCreate() {
  editing.value = null;
  modalOpen.value = true;
}

function openEdit(record: ImplantRecordView) {
  editing.value = record;
  modalOpen.value = true;
}

function openReplace(initialType: string) {
  replaceInitialType.value = initialType;
  replaceOpen.value = true;
}

function handleSaved() {
  void load();
  emit('changed');
}

const columns = computed(() => [
  { title: '序号', key: 'implantNo', width: 60 },
  { title: '日期', dataIndex: 'implantDate', width: 110 },
  { title: '植入类型', key: 'implantType', width: 130 },
  { title: '器械（类型 · 品牌 · 型号）', key: 'device' },
  { title: '序列号', dataIndex: 'serialNo', width: 140 },
  { title: '状态', key: 'status', width: 90 },
  { title: '操作', key: 'action', width: 130 },
]);

const leadColumns = [
  { title: '腔室', key: 'chamber', width: 120 },
  { title: '植入部位', key: 'position', width: 140 },
  { title: '型号', dataIndex: 'model' },
  { title: '植入日期', dataIndex: 'implantDate', width: 110 },
  { title: '状态', key: 'leadStatus', width: 100 },
];

/** 器械列显示：类型 · 品牌 · 型号（pages.md §4.4「器械」列）。 */
function deviceText(record: ImplantRecordView): string {
  const parts = [record.deviceTypeLabel || record.deviceType];
  if (record.brandLabel) parts.push(record.brandLabel);
  if (record.model) parts.push(record.model);
  return parts.join(' · ');
}

/** 行唯一键（展开行与 electrodes 渲染用）。 */
function rowKey(record: ImplantRecordView) {
  return record.id;
}
</script>

<template>
  <section
    id="crdp-anchor-implant-records"
    class="implant-section"
    data-testid="implant-section"
  >
    <h3 class="section-title">
      植入记录
      <span class="section-meta">当前器械置顶 · 电极挂在各植入记录下</span>
      <template v-if="canEdit">
        <Tooltip
          :title="currentRecord ? '' : '无在用器械，无法更换 / 升级或电极修正'"
        >
          <Button
            class="section-action"
            size="small"
            :disabled="!currentRecord"
            data-testid="implant-replace"
            @click="openReplace(IMPLANT_TYPE_REPLACE)"
          >
            更换 / 升级
          </Button>
        </Tooltip>
        <Button
          class="section-action"
          size="small"
          :disabled="!currentRecord"
          data-testid="implant-lead-revision"
          @click="openReplace(IMPLANT_TYPE_LEAD_REVISION)"
        >
          电极修正
        </Button>
        <Button
          class="section-action"
          size="small"
          type="primary"
          data-testid="implant-create"
          @click="openCreate"
        >
          新建植入记录
        </Button>
      </template>
    </h3>
    <Spin :spinning="loading">
      <Empty v-if="records.length === 0" description="暂无植入记录" />
      <!-- 手机：植入记录与电极表格改卡片（pages.md §4.10） -->
      <div v-else-if="isMobile" class="cards" data-testid="implant-cards">
        <div v-for="record in records" :key="record.id" class="implant-card">
          <div class="card-head">
            <span>第 {{ record.implantNo }} 次 · {{ record.implantDate }}</span>
            <Tag v-if="record.current" color="processing">当前器械</Tag>
            <Tag v-else>{{ record.statusLabel }}</Tag>
          </div>
          <div
            v-for="[label, value] in implantCardLines(record)"
            :key="label"
            class="card-line"
          >
            <span class="card-label">{{ label }}</span>
            <span>{{ value || '—' }}</span>
          </div>
          <div v-if="canEdit" class="card-actions">
            <Button
              v-if="record.current"
              size="small"
              @click="explantOpen = true"
            >
              取出
            </Button>
            <Button size="small" @click="openEdit(record)">编辑</Button>
          </div>
        </div>
      </div>
      <template v-else>
        <Table
          :columns="columns"
          :data-source="records"
          :pagination="false"
          :row-key="rowKey"
          size="small"
          data-testid="implant-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'implantNo'">
              <span>第 {{ (record as ImplantRecordView).implantNo }} 次</span>
            </template>
            <template v-else-if="column.key === 'implantType'">
              {{ (record as ImplantRecordView).implantTypeLabel }}
            </template>
            <template v-else-if="column.key === 'device'">
              <span>{{ deviceText(record as ImplantRecordView) }}</span>
            </template>
            <template v-else-if="column.key === 'status'">
              <Tag
                v-if="(record as ImplantRecordView).current"
                color="processing"
              >
                在用
              </Tag>
              <Tag v-else>{{ (record as ImplantRecordView).statusLabel }}</Tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <template v-if="canEdit">
                <Button
                  v-if="(record as ImplantRecordView).current"
                  size="small"
                  type="link"
                  data-testid="implant-explant"
                  @click="explantOpen = true"
                >
                  取出
                </Button>
                <Button
                  size="small"
                  type="link"
                  @click="openEdit(record as ImplantRecordView)"
                >
                  编辑
                </Button>
              </template>
            </template>
          </template>
          <!-- 电极挂在每条植入记录下（展开行；无电极的记录显示「无导线 / 未录」） -->
          <template #expandedRowRender="{ record }">
            <Table
              :columns="leadColumns"
              :data-source="(record as ImplantRecordView).leads"
              :pagination="false"
              size="small"
              class="lead-table"
            >
              <template #bodyCell="{ column, record: lead }">
                <template v-if="column.key === 'chamber'">
                  {{
                    (lead as ImplantRecordView['leads'][number]).chamberLabel
                  }}
                </template>
                <template v-else-if="column.key === 'position'">
                  {{
                    (lead as ImplantRecordView['leads'][number])
                      .positionLabel || '—'
                  }}
                </template>
                <template v-else-if="column.key === 'leadStatus'">
                  {{ (lead as ImplantRecordView['leads'][number]).statusLabel }}
                </template>
              </template>
            </Table>
            <span
              v-if="(record as ImplantRecordView).leads.length === 0"
              class="lead-empty"
            >
              无电极记录（无导线起搏器不录电极）
            </span>
          </template>
        </Table>
      </template>
    </Spin>
    <ImplantFormModal
      v-model:open="modalOpen"
      :patient-id="props.patientId"
      :record="editing"
      @saved="handleSaved"
    />
    <ImplantReplaceModal
      v-model:open="replaceOpen"
      :initial-type="replaceInitialType"
      :record="currentRecord"
      @saved="handleSaved"
    />
    <ImplantExplantModal
      v-model:open="explantOpen"
      :record="currentRecord"
      @saved="handleSaved"
    />
  </section>
</template>

<style scoped>
.implant-section {
  margin-top: 16px;
}

.section-title {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 16px;
}

.section-meta {
  flex: 1;
  font-size: 12px;
  font-weight: 400;
  color: rgb(0 0 0 / 45%);
}

.lead-table {
  background: #fafafa;
}

.lead-empty {
  color: rgb(0 0 0 / 45%);
}

.cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.implant-card {
  padding: 12px;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
}

.card-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}

.card-line {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 22px;
}

.card-label {
  min-width: 84px;
  color: rgb(0 0 0 / 45%);
}

.card-actions {
  margin-top: 8px;
  text-align: right;
}
</style>
