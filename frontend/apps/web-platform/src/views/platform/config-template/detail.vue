<script lang="ts" setup>
import type { ConfigTemplateDetail as DetailView } from '@crdp/api';

import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Result,
  Space,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  configTemplateDetail,
  configTemplatePackage,
} from '#/api/core/config-template';
import { usePermission } from '#/reference/use-permission';

import ApplyDialog from './apply-dialog.vue';
import {
  headerRows,
  packageFileBody,
  packageFileName,
  tableMetaRows,
} from './detail';

/**
 * 配置模板详情（E12-T04 / A-FLD-014）：头信息、每张配置表的入包元信息
 * （复制方式、行数、剔除行数），下载完整包（写审计）与应用到租户（导入前预览）。
 */
defineOptions({ name: 'PlatformConfigTemplateDetail' });

const route = useRoute();
const router = useRouter();
const templateId = Number(route.params.id);
const { hasPermission } = usePermission();
const canCreate = hasPermission('platform:tenant:create');

const detail = ref<DetailView | null>(null);
const loading = ref(false);
const errorMessage = ref('');

const applyOpen = ref(false);
const downloading = ref(false);
const downloadError = ref('');

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    detail.value = await configTemplateDetail(templateId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

async function downloadPackage() {
  if (!detail.value || downloading.value) return;
  downloading.value = true;
  downloadError.value = '';
  try {
    const pkg = await configTemplatePackage(templateId);
    const blob = new Blob([packageFileBody(pkg)], {
      type: 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = packageFileName(detail.value.template);
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    downloadError.value =
      error instanceof Error ? error.message : String(error);
  } finally {
    downloading.value = false;
  }
}

const columns = [
  { dataIndex: 'table', title: '配置表' },
  { dataIndex: 'category', title: '类别' },
  { dataIndex: 'module', title: '模块', width: 90 },
  { dataIndex: 'modeText', title: '复制方式', width: 110 },
  { dataIndex: 'rowsText', title: '包内行数', width: 190 },
  { dataIndex: 'presentText', title: '包内状态', width: 170 },
];

onMounted(() => {
  void load();
});
</script>

<template>
  <Page title="配置模板详情">
    <div class="toolbar">
      <Button type="link" @click="router.push('/platform/config-template')">
        返回列表
      </Button>
    </div>

    <Alert
      v-if="errorMessage && detail"
      :message="`加载失败：${errorMessage}`"
      show-icon
      type="error"
      class="mb-4"
    />
    <Spin :spinning="loading">
      <template v-if="detail">
        <Card :bordered="false" class="crdp-card mb-4">
          <div class="title-row">
            <span></span>
            <Space>
              <Button
                :loading="downloading"
                data-testid="download-package"
                @click="downloadPackage"
              >
                下载配置包 JSON
              </Button>
              <Button
                v-if="canCreate"
                data-testid="apply-template"
                type="primary"
                @click="applyOpen = true"
              >
                应用到租户
              </Button>
            </Space>
          </div>
          <Descriptions :column="2" bordered size="small">
            <DescriptionsItem
              v-for="row in headerRows(detail.template)"
              :key="row.key"
              :label="row.label"
            >
              {{ row.value }}
            </DescriptionsItem>
          </Descriptions>
          <Alert
            v-if="downloadError"
            :message="`下载失败：${downloadError}`"
            show-icon
            type="error"
            class="mt-4"
          />
        </Card>

        <Card :bordered="false" class="crdp-card" title="配置表（入包元信息）">
          <Table
            :columns="columns"
            :data-source="tableMetaRows(detail.tables)"
            :pagination="false"
            :row-key="
              (row: ReturnType<typeof tableMetaRows>[number]) => row.table
            "
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'modeText'">
                <Tag v-if="record.modeText === '只复制结构'" color="default">
                  {{ record.modeText }}
                </Tag>
                <Tag v-else color="blue">{{ record.modeText }}</Tag>
              </template>
              <template v-else-if="column.dataIndex === 'presentText'">
                <span v-if="record.presentText === '正常'">
                  {{ record.presentText }}
                </span>
                <span v-else class="absent">{{ record.presentText }}</span>
              </template>
            </template>
          </Table>
        </Card>
      </template>
      <Result
        v-else-if="!loading && errorMessage"
        status="error"
        title="模板不存在或不可访问"
      >
        <template #extra>
          <Button
            type="primary"
            @click="router.push('/platform/config-template')"
          >
            返回列表
          </Button>
        </template>
      </Result>
    </Spin>

    <ApplyDialog
      v-model:open="applyOpen"
      :template="detail?.template ?? null"
    />
  </Page>
</template>

<style scoped>
.toolbar {
  margin-bottom: 8px;
}

.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.absent {
  color: #fa8c16;
}
</style>
