import 'ant-design-vue/dist/reset.css'
import type { SessionMode } from '@crdp/api'
import Antd from 'ant-design-vue'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import BasicLayout from './BasicLayout.vue'
import { ApiError } from './http'
import LoginPage from './LoginPage.vue'
import MissingView from './MissingView.vue'
import NotFound from './NotFound.vue'
import Root from './Root.vue'
import { collectRoutes, matchView } from './routes'
import { useSessionStore } from './session'

export interface ShellOptions {
  mode: SessionMode
  views: Record<string, () => Promise<unknown>>
}

export function bootstrap(options: ShellOptions) {
  const viewKeys = Object.keys(options.views)
  const pinia = createPinia()
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/login', name: 'Login', component: LoginPage, meta: { public: true } },
      { path: '/', name: 'Root', component: BasicLayout, children: [] },
      { path: '/not-found', name: 'NotFound', component: NotFound },
    ],
  })

  let installing = false

  async function installMenus() {
    if (installing) return
    installing = true
    const session = useSessionStore()
    try {
      if (!session.loaded) {
        await session.load()
      }
      for (const item of collectRoutes(session.menus)) {
        if (router.hasRoute(item.name)) continue
        const key = matchView(viewKeys, item.component)
        const component = key ? options.views[key] : () => Promise.resolve(MissingView)
        router.addRoute('Root', {
          path: item.path,
          name: item.name,
          component,
          meta: { ...item.meta },
        } as RouteRecordRaw)
      }
    } finally {
      installing = false
    }
  }

  router.beforeEach(async (to) => {
    const session = useSessionStore()
    if (to.meta.public) {
      return true
    }
    if (!session.token) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (!session.loaded) {
      try {
        await installMenus()
      } catch (error) {
        if (error instanceof ApiError && (error.code === 40100 || error.code === 40101)) {
          await session.logout()
        }
        return { path: '/login' }
      }
      return to.fullPath
    }
    if (to.path === '/') {
      return '/dashboard'
    }
    if (to.matched.length === 0) {
      return { name: 'NotFound' }
    }
    return true
  })

  const app = createApp(Root)
  app.use(pinia)
  app.use(router)
  app.use(Antd)
  useSessionStore().bindMode(options.mode)
  app.mount('#app')
}
