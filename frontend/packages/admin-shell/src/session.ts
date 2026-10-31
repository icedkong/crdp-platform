import type { Hello, LoginResult, Me, MenuNode, SessionMode } from '@crdp/api'
import { authBase } from '@crdp/api'
import { defineStore } from 'pinia'
import { request, setAuthToken } from './http'

const TOKEN_KEY = 'fu_token'

export const useSessionStore = defineStore('session', {
  state: () => ({
    mode: 'hospital' as SessionMode,
    token: sessionStorage.getItem(TOKEN_KEY) || '',
    me: null as Me | null,
    codes: [] as string[],
    menus: [] as MenuNode[],
    loaded: false,
  }),
  actions: {
    bindMode(mode: SessionMode) {
      this.mode = mode
      if (this.token) {
        setAuthToken(this.token)
      }
    },
    async login(payload: { tenantCode?: string; username: string; password: string }) {
      const result = await request<LoginResult>(
        `${authBase(this.mode)}/auth/login`,
        'POST',
        payload,
      )
      this.token = result.token
      sessionStorage.setItem(TOKEN_KEY, result.token)
      setAuthToken(result.token)
      this.loaded = false
    },
    async load() {
      const base = authBase(this.mode)
      const [me, codes, menus] = await Promise.all([
        request<Me>(`${base}/auth/me`),
        request<string[]>(`${base}/auth/codes`),
        request<MenuNode[]>(`${base}/menus`),
      ])
      this.me = me
      this.codes = codes
      this.menus = menus
      this.loaded = true
    },
    async hello() {
      return request<Hello>('/api/hospital/hello')
    },
    async logout() {
      try {
        await request<void>(`${authBase(this.mode)}/auth/logout`, 'POST')
      } finally {
        this.token = ''
        this.me = null
        this.codes = []
        this.menus = []
        this.loaded = false
        sessionStorage.removeItem(TOKEN_KEY)
        setAuthToken('')
      }
    },
  },
})
