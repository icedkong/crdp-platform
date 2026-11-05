import 'vue-router';

/**
 * E24-T01 / NFR-RESP-002：页面是否做了手机（<768）响应式。
 * 默认（不写或 false）手机上由响应式守卫整页替换为 MobileBlocker；
 * 已适配的页面（E24-T02～T06 的 5 个页面）置 true。
 */
declare module 'vue-router' {
  interface RouteMeta {
    responsive?: boolean;
  }
}
