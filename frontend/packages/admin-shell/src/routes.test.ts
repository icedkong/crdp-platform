import { describe, expect, it } from 'vitest'
import type { MenuNode } from '@fu/api'
import { collectRoutes, matchView } from './routes'

const menu = (partial: Partial<MenuNode> & Pick<MenuNode, 'id' | 'type' | 'name'>): MenuNode => ({
  path: '',
  component: '',
  meta: { title: partial.name, order: 0, hideInMenu: false },
  children: [],
  ...partial,
})

describe('collectRoutes', () => {
  it('keeps pages and skips directories', () => {
    const routes = collectRoutes([
      menu({
        id: 20,
        type: 'DIR',
        name: 'Followup',
        path: '/followup',
        component: 'LAYOUT',
        children: [
          menu({
            id: 21,
            type: 'MENU',
            name: 'FollowupPatient',
            path: '/followup/patient',
            component: 'followup/patient/index',
            meta: {
              title: '患者档案',
              order: 10,
              hideInMenu: false,
              permission: 'followup:patient:view',
            },
          }),
        ],
      }),
    ])
    expect(routes).toHaveLength(1)
    expect(routes[0].name).toBe('FollowupPatient')
    expect(routes[0].meta.permission).toBe('followup:patient:view')
  })
})

describe('matchView', () => {
  it('maps a backend component to a collected view file', () => {
    const keys = ['./views/dashboard/index.vue', './views/followup/patient/index.vue']
    expect(matchView(keys, 'followup/patient/index')).toBe('./views/followup/patient/index.vue')
    expect(matchView(keys, 'missing/index')).toBeUndefined()
  })
})
