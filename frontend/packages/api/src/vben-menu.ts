import type { MenuNode } from './index';

/** Vben backend 模式要的路由节点。component 为 BasicLayout 或 views 下的路径。 */
export interface VbenMenuRoute {
  children?: VbenMenuRoute[];
  component: string;
  meta: {
    activePath?: string;
    affixTab?: boolean;
    hideInMenu?: boolean;
    icon?: string;
    order?: number;
    title: string;
  };
  name: string;
  path: string;
}

/** Ant Design Outlined 图标名 → Iconify。已经是 iconify id 的原样返回。 */
export function toIconifyIcon(icon?: null | string) {
  if (!icon) return undefined;
  if (icon.includes(':')) return icon;
  const kebab = icon
    .replaceAll(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replaceAll(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
    .toLowerCase();
  return `ant-design:${kebab}`;
}

export function menuNodeToRoute(
  node: MenuNode,
  parentPath?: string,
): VbenMenuRoute {
  const children = (node.children ?? []).map((child) =>
    menuNodeToRoute(child, node.path),
  );
  const hideInMenu = Boolean(node.meta?.hideInMenu);
  const route: VbenMenuRoute = {
    component: node.component === 'LAYOUT' ? 'BasicLayout' : node.component,
    meta: {
      affixTab: node.path === '/dashboard',
      hideInMenu,
      icon: toIconifyIcon(node.meta?.icon ?? undefined),
      order: node.meta?.order ?? 0,
      title: node.meta?.title || node.name,
    },
    name: node.name,
    path: node.path,
  };
  // 隐藏路由（如患者详情 /followup/patient/:id）由页面动作打开：高亮落到父菜单
  if (hideInMenu && parentPath && parentPath !== '/') {
    route.meta.activePath = parentPath;
  }
  if (children.length > 0) {
    route.children = children;
  }
  return route;
}

export function menuNodesToRoutes(nodes: MenuNode[]) {
  return nodes.map((node) => menuNodeToRoute(node));
}
