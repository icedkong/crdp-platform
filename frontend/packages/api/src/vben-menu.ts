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
    /** E24：手机（<768）响应式放行名单内为 true（守卫不重定向 /mobile）。 */
    responsive?: boolean;
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
  parentPath?: null | string,
  responsiveNames?: null | ReadonlySet<string>,
): VbenMenuRoute {
  // 单节点转换：隐藏子路由不挂在本节点下（提升口径见 menuNodesToRoutes），
  // hoisted 收集后丢弃——整树转换请用 menuNodesToRoutes。
  const hoisted: VbenMenuRoute[] = [];
  return convertNode(
    node,
    parentPath ?? null,
    responsiveNames ?? null,
    hoisted,
  );
}

/**
 * E15-T11（M2 验收发现）：可见路由只嵌套可见子路由，隐藏子路由收集到 hoisted
 * 由 {@link menuNodesToRoutes} 提升到顶层。原因：Vben generateAccessible 会给所有
 * 带子路由的父路由自动加 `redirect = firstChild.path`——列表页菜单（我的任务、
 * 患者档案、随访计划）的唯一子路由都是隐藏详情路由（如 /followup/record/:recordId），
 * 点击菜单会被重定向到字面量参数死路由，主流程侧栏导航全断。隐藏路由路径为绝对
 * 路径，本就全局匹配，提升到顶层不影响可达性；meta.activePath 保持侧栏高亮指向
 * 原父菜单。代价：详情页面包屑只剩本页一级（route.matched 不再含父路由）。
 */
function convertNode(
  node: MenuNode,
  parentPath: null | string,
  responsiveNames: null | ReadonlySet<string>,
  hoisted: VbenMenuRoute[],
): VbenMenuRoute {
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
  // E24-T02 / NFR-RESP-002：已做手机适配的页面（路由名登记，逐页打开）——
  // meta.responsive !== true 的路由在手机上被响应式守卫整页替换为 MobileBlocker。
  if (responsiveNames?.has(node.name)) {
    route.meta.responsive = true;
  }

  if (hideInMenu) {
    // 隐藏路由整棵子树保持嵌套（子树必然不可见），随根一起提升到顶层
    const children = (node.children ?? []).map((child) =>
      convertNode(child, node.path, responsiveNames, hoisted),
    );
    if (children.length > 0) {
      route.children = children;
    }
    return route;
  }

  const visibleChildren: VbenMenuRoute[] = [];
  for (const child of node.children ?? []) {
    const childRoute = convertNode(child, node.path, responsiveNames, hoisted);
    if (childRoute.meta.hideInMenu) {
      hoisted.push(childRoute);
    } else {
      visibleChildren.push(childRoute);
    }
  }
  if (visibleChildren.length > 0) {
    route.children = visibleChildren;
  }
  return route;
}

export function menuNodesToRoutes(
  nodes: MenuNode[],
  responsiveNames?: ReadonlySet<string>,
) {
  const hoisted: VbenMenuRoute[] = [];
  const routes = nodes.map((node) =>
    convertNode(node, null, responsiveNames ?? null, hoisted),
  );
  return [...routes, ...hoisted];
}
