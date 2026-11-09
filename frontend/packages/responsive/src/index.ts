import './route-meta';

export {
  applyDeviceProfile,
  DEVICE_PROFILES,
  type DeviceEngineCapabilities,
  type DeviceProfile,
} from './device-matrix';
export { default as MobileBlocker } from './mobile-blocker.vue';
export {
  createMobileBlockedRoute,
  MOBILE_BLOCKED_PATH,
  MOBILE_BLOCKED_ROUTE_NAME,
  setupResponsiveGuard,
} from './responsive-guard';
export { useBreakpoint } from './use-breakpoint';
export { useKeyboardSafeBottom } from './use-keyboard-safe-bottom';
export { useResponsiveShell } from './use-responsive-shell';
export { viewportHeightUnit } from './viewport-units';
