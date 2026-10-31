<script setup lang="ts">
import type { Hello } from '@crdp/api'
import { useSessionStore } from '@crdp/admin-shell'
import { onMounted, ref } from 'vue'

const session = useSessionStore()
const hello = ref<Hello | null>(null)
const error = ref('')

onMounted(async () => {
  try {
    hello.value = await session.hello()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '工作台数据没有加载出来'
  }
})
</script>

<template>
  <section>
    <header class="head">
      <div>
        <h1>工作台</h1>
        <p>菜单由后端按角色下发。这里只确认租户路由，不展示患者数据。</p>
      </div>
    </header>
    <a-alert v-if="error" type="error" :message="error" show-icon />
    <a-spin v-else-if="!hello" />
    <template v-else>
      <a-descriptions bordered :column="1" size="middle">
        <a-descriptions-item label="医院"
          >{{ hello.tenantName }}（{{ hello.tenantCode }}）</a-descriptions-item
        >
        <a-descriptions-item label="当前用户"
          >{{ session.me?.displayName }} / {{ session.me?.username }}</a-descriptions-item
        >
        <a-descriptions-item label="角色">{{ session.me?.roles.join('、') }}</a-descriptions-item>
        <a-descriptions-item label="租户库">{{ hello.database }}</a-descriptions-item>
        <a-descriptions-item label="路由">{{ hello.message }}</a-descriptions-item>
      </a-descriptions>
      <a-row :gutter="[16, 16]" class="cards">
        <a-col :xs="24" :md="8">
          <a-card title="随访">患者档案、任务和报告单仍是占位页。</a-card>
        </a-col>
        <a-col :xs="24" :md="8">
          <a-card title="权限码">{{ session.codes.join('、') || '当前角色没有按钮权限码' }}</a-card>
        </a-col>
        <a-col :xs="24" :md="8">
          <a-card title="数据">种子数据全部虚构，不能当作医院资料。</a-card>
        </a-col>
      </a-row>
    </template>
  </section>
</template>

<style scoped>
.head h1 {
  margin: 0 0 4px;
  font-size: 22px;
}
.head p {
  margin: 0 0 16px;
  color: #5c706e;
}
.cards {
  margin-top: 16px;
}
</style>
