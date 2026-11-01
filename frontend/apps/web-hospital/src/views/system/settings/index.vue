<script lang="ts" setup>
import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { Button, Result, TabPane, Tabs } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import BrandPanel from './brand-panel.vue';
import DictPanel from './dict-panel.vue';
import ParamPanel from './param-panel.vue';

defineOptions({ name: 'HospitalSettings' });

/**
 * E06-T11 / E06-T10 · pages.md §10「机构设置」（/system/settings）：按权限展示页签——
 * 个性化（tenant:brand:edit）、租户参数（tenant:param:edit，PLT-CFG-004）、
 * 租户字典（tenant:dict:edit，PLT-CFG-002）；无对应权限的页签隐藏（前端隐藏不能代替后端校验）。
 * 通知模板 / Webhook / 安全设置页签随后续任务扩展。仅桌面 / 平板。
 */
const { isMobile } = useBreakpoint();
const { hasPermission } = usePermission();

const activeTab = ref('brand');

const canBrand = hasPermission('tenant:brand:edit');
const canParam = hasPermission('tenant:param:edit');
const canDict = hasPermission('tenant:dict:edit');

// 默认落在第一个有权限的页签（菜单本身按 tenant:brand:edit 下发，但按钮权限可能只有部分）
if (!canBrand) {
  activeTab.value = canParam ? 'param' : 'dict';
}

const noPermission = computed(() => !canBrand && !canParam && !canDict);
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
    v-else-if="!noPermission"
    title="机构设置"
    description="本院在登录页与系统内的呈现、租户参数与租户字典。保存后即时生效并记录操作日志。"
  >
    <Tabs v-model:active-key="activeTab">
      <TabPane v-if="canBrand" key="brand" tab="个性化">
        <BrandPanel />
      </TabPane>
      <TabPane v-if="canParam" key="param" tab="参数">
        <ParamPanel />
      </TabPane>
      <TabPane v-if="canDict" key="dict" tab="字典">
        <DictPanel />
      </TabPane>
    </Tabs>
  </Page>
  <Result v-else status="403" title="没有机构设置权限">
    <template #extra>
      <Button type="primary" @click="$router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
</template>
