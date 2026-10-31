import type { MenuNode } from '@fu/api'

export interface FlatRoute {
  name: string
  path: string
  component: string
  meta: MenuNode['meta']
}

export function collectRoutes(menus: MenuNode[]): FlatRoute[] {
  const routes: FlatRoute[] = []
  const walk = (nodes: MenuNode[]) => {
    for (const node of nodes) {
      if (node.type === 'MENU' && node.component && node.component !== 'LAYOUT') {
        routes.push({
          name: node.name,
          path: node.path,
          component: node.component,
          meta: node.meta,
        })
      }
      if (node.children?.length) {
        walk(node.children)
      }
    }
  }
  walk(menus)
  return routes
}

export function matchView(keys: string[], component: string) {
  const suffix = `/views/${component}.vue`
  return keys.find((key) => key.endsWith(suffix))
}
