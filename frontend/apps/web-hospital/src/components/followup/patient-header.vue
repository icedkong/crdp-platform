<script lang="ts" setup>
import type { ReferencePatient } from '#/reference/types';

import { ref } from 'vue';

import { Button, Drawer, Space } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

import MaskedField from './masked-field.vue';
import StatusTag from './status-tag.vue';

defineProps<{
  canContact?: boolean;
  canStop?: boolean;
  canUnplanned?: boolean;
  canUpload?: boolean;
  patient: ReferencePatient;
  referenceMode?: boolean;
}>();

const emit = defineEmits<{
  action: [type: 'contact' | 'stop' | 'unplanned' | 'upload'];
}>();

const { isMobile } = useBreakpoint();
const detailsOpen = ref(false);
const actionsOpen = ref(false);

function run(type: 'contact' | 'stop' | 'unplanned' | 'upload') {
  actionsOpen.value = false;
  emit('action', type);
}
</script>

<template>
  <header class="header" data-testid="patient-header">
    <div class="line">
      <strong class="name">
        <MaskedField
          field="name"
          :patient-id="patient.id"
          :reference-mode="referenceMode"
          revealable
          :value="patient.nameMasked"
        />
      </strong>
      <StatusTag :status="patient.status" variant="tag" />
      <span v-if="patient.nextVisit" class="meta">
        下次随访 {{ patient.nextVisit.label }} · 计划
        {{ patient.nextVisit.planDate }}
      </span>
      <Button
        v-if="isMobile"
        size="small"
        type="link"
        @click="detailsOpen = !detailsOpen"
      >
        {{ detailsOpen ? '收起 ▴' : '详情 ▾' }}
      </Button>
    </div>
    <div v-if="!isMobile || detailsOpen" class="line">
      <span>{{ patient.gender }} · {{ patient.age }} 岁</span>
      <span>编号 {{ patient.patientNo }}</span>
      <span v-if="patient.legacyNo">原系统编号 {{ patient.legacyNo }}</span>
      <span>病区 {{ patient.wardName }}</span>
      <span>负责医生 {{ patient.responsibleName }}</span>
      <span>
        电话
        <MaskedField
          field="phone"
          :patient-id="patient.id"
          :reference-mode="referenceMode"
          revealable
          :value="patient.phoneMasked"
        />
      </span>
    </div>
    <div v-if="!isMobile || detailsOpen" class="line">
      <span>基线资料完整度 {{ patient.baselineCompleteness }}%</span>
      <span>随访资料完整度 {{ patient.followupCompleteness }}%</span>
      <span v-if="patient.features.implant && patient.device">
        当前器械 {{ patient.device.typeLabel }} · 植入
        {{ patient.device.implantDuration }}
      </span>
      <span v-if="patient.features.eriCycle && patient.device">
        ERI：{{ patient.device.eri }}
      </span>
    </div>
    <Space v-if="!isMobile" wrap>
      <Button v-if="canUnplanned" @click="run('unplanned')">
        新建计划外随访
      </Button>
      <Button
        v-if="canUpload"
        data-testid="upload-open"
        ghost
        type="primary"
        @click="run('upload')"
      >
        上传报告单
      </Button>
      <Button v-if="canContact" @click="run('contact')">记录沟通</Button>
      <Button v-if="canStop" @click="run('stop')">停止随访 / 标记失访</Button>
    </Space>
    <Button
      v-else
      data-testid="upload-open"
      type="primary"
      @click="actionsOpen = true"
    >
      操作
    </Button>
    <Drawer
      v-model:open="actionsOpen"
      height="40vh"
      placement="bottom"
      title="操作"
    >
      <Space direction="vertical">
        <Button v-if="canUnplanned" block @click="run('unplanned')">
          新建计划外随访
        </Button>
        <Button v-if="canUpload" block type="primary" @click="run('upload')">
          上传报告单
        </Button>
        <Button v-if="canContact" block @click="run('contact')">
          记录沟通
        </Button>
        <Button v-if="canStop" block @click="run('stop')">
          停止随访 / 标记失访
        </Button>
      </Space>
    </Drawer>
  </header>
</template>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 8;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e9e9e9;
}

.line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
  margin-bottom: 8px;
}

.name {
  font-size: 16px;
  font-weight: 600;
}

.meta {
  color: #8c8c8c;
}
</style>
