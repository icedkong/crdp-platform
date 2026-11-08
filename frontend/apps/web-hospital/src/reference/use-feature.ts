import { ref } from 'vue';

import { getFeatureSwitchesApi } from '#/api/core/auth';

/**
 * 功能开关（components.md §1.3 `useFeature(code)`；OQ-03 定稿：GET /api/hospital/auth/features
 * 返回 `{ featureCode: enabled }`）。开关存租户库 cfg_feature，登录后拉一次、会话内缓存；
 * 关闭的功能页签 / 按钮 / 卡片直接隐藏不置灰——后端每个接口仍按开关 40301 兜底（AGENTS §5.7）。
 *
 * 未配置的开关不在返回里，`isEnabled` 按默认关闭（false）处理，与后端「无行视为默认关闭」同口径。
 * 拉取失败（如弱网）不阻断页面：开关表保持空、入口隐藏，下次进入页面重试。
 */
const switches = ref<Record<string, boolean>>({});
let pending: null | Promise<void> = null;

function loadSwitches() {
  if (!pending) {
    pending = getFeatureSwitchesApi()
      .then((map) => {
        switches.value = map;
      })
      .catch(() => {
        // 失败不缓存结果：下次调用重试（入口维持隐藏，不做错误提示）
        pending = null;
      });
  }
  return pending;
}

export function useFeature() {
  void loadSwitches();
  function isEnabled(code: string): boolean {
    return switches.value[code] === true;
  }
  return { isEnabled, switches };
}
