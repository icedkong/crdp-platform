<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from './http'
import { useSessionStore } from './session'

const session = useSessionStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const error = ref('')
const form = reactive({
  tenantCode: localStorage.getItem('crdp_tenant') || 'demoa',
  username: session.mode === 'hospital' ? 'nurse.demo' : 'platform.admin',
  password: '',
})

async function submit() {
  error.value = ''
  loading.value = true
  try {
    if (session.mode === 'hospital') {
      localStorage.setItem('crdp_tenant', form.tenantCode.trim())
    }
    await session.login({
      tenantCode: session.mode === 'hospital' ? form.tenantCode.trim() : undefined,
      username: form.username.trim(),
      password: form.password,
    })
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="intro">
      <p class="eyebrow">多租户医院随访</p>
      <h1>临床科研数据一体化平台</h1>
      <p>
        {{
          session.mode === 'hospital'
            ? '先填写医院租户编码，再使用本院账号登录。演示环境只有虚构数据。'
            : '平台管理员从这里进入控制面。医院账号不能登录平台端。'
        }}
      </p>
    </section>
    <section class="panel">
      <h2>{{ session.mode === 'hospital' ? '医院端登录' : '平台端登录' }}</h2>
      <a-alert v-if="error" type="error" :message="error" show-icon class="gap" />
      <a-form :model="form" layout="vertical" @finish="submit">
        <a-form-item v-if="session.mode === 'hospital'" label="租户编码" required>
          <a-input
            v-model:value="form.tenantCode"
            autocomplete="organization"
            placeholder="例如 demoa"
          />
        </a-form-item>
        <a-form-item label="用户名" required>
          <a-input v-model:value="form.username" autocomplete="username" />
        </a-form-item>
        <a-form-item label="密码" required>
          <a-input-password v-model:value="form.password" autocomplete="current-password" />
        </a-form-item>
        <a-button type="primary" html-type="submit" block :loading="loading">登录</a-button>
      </a-form>
      <a-collapse class="hint" ghost>
        <a-collapse-panel key="demo" header="查看虚构演示账号">
          <template v-if="session.mode === 'hospital'">
            <p>租户 <code>demoa</code>（虚构示例医院甲）或 <code>demob</code>（虚构示例医院乙）</p>
            <p>租户管理员 <code>nurse.demo</code>，随访医生 <code>doctor.demo</code></p>
          </template>
          <template v-else>
            <p>平台管理员 <code>platform.admin</code></p>
          </template>
          <p>口令 <code>Demo@2026</code>。这些账号只存在于本地种子数据。</p>
        </a-collapse-panel>
      </a-collapse>
    </section>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(280px, 42%) 1fr;
  background: #f3f6f5;
}
.intro {
  padding: 64px 48px;
  background: #123c3e;
  color: #f4fbfa;
}
.eyebrow {
  letter-spacing: 0.12em;
  color: #b7d4d2;
}
.intro h1 {
  margin: 8px 0 16px;
  max-width: 12em;
  font-size: 32px;
  line-height: 1.35;
  font-weight: 600;
}
.intro p {
  max-width: 360px;
  line-height: 1.7;
}
.panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 12vw;
}
.panel h2 {
  margin-bottom: 20px;
}
.gap {
  margin-bottom: 16px;
}
.hint {
  margin-top: 20px;
}
.hint code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
@media (max-width: 800px) {
  .login {
    grid-template-columns: 1fr;
  }
  .intro {
    padding: 28px 20px;
  }
  .intro h1 {
    font-size: 26px;
  }
  .panel {
    padding: 24px 20px 48px;
  }
}
</style>
