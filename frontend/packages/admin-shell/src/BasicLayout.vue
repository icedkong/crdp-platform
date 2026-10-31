<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from './session'
import MenuBranch from './MenuBranch.vue'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const selected = computed(() => [route.path])
const openKeys = ref<string[]>([])
watch(
  () => session.menus,
  (menus) => {
    openKeys.value = menus.filter((item) => item.type === 'DIR').map((item) => `dir-${item.id}`)
  },
  { immediate: true },
)
const tenantLabel = computed(() => session.me?.tenantName || '平台端')
const product = computed(() => (session.mode === 'hospital' ? '医院端' : '平台端'))

function onOpen(keys: string[]) {
  openKeys.value = keys
}

function onClick(info: { key: string | number }) {
  const key = String(info.key)
  if (key.startsWith('/')) {
    router.push(key)
  }
}

async function logout() {
  await session.logout()
  router.replace('/login')
}
</script>

<template>
  <a-layout class="shell">
    <a-layout-sider class="sider" breakpoint="lg" collapsible :width="228" theme="dark">
      <div class="brand">
        <span class="mark">临</span>
        <div>
          <strong>临床科研数据一体化平台</strong>
          <small>{{ product }}</small>
        </div>
      </div>
      <a-menu
        theme="dark"
        mode="inline"
        :selected-keys="selected"
        :open-keys="openKeys"
        @open-change="onOpen"
        @click="onClick"
      >
        <MenuBranch :nodes="session.menus" />
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-header class="header">
        <span class="who">{{ tenantLabel }}</span>
        <a-dropdown>
          <a class="user" @click.prevent>{{ session.me?.displayName || '未登录' }}</a>
          <template #overlay>
            <a-menu>
              <a-menu-item key="logout" @click="logout">退出登录</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </a-layout-header>
      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}
.sider {
  background: #123c3e;
}
.sider :deep(.ant-layout-sider-trigger) {
  background: #0d3032;
}
.sider :deep(.ant-menu) {
  background: transparent;
}
.brand {
  display: flex;
  gap: 10px;
  align-items: center;
  min-height: 64px;
  padding: 12px 16px;
  color: #f4fbfa;
}
.brand strong,
.brand small {
  display: block;
  line-height: 1.25;
}
.brand strong {
  font-size: 13px;
  font-weight: 600;
}
.brand small {
  color: #b7d4d2;
}
.mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #0e7c73;
  font-weight: 700;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #e6eeed;
}
.who {
  color: #35504e;
}
.user {
  color: #123c3e;
}
.content {
  margin: 16px;
  padding: 20px;
  background: #fff;
  border-radius: 12px;
  min-height: calc(100vh - 96px);
}
@media (max-width: 800px) {
  .content {
    margin: 8px;
    padding: 14px;
  }
}
</style>
