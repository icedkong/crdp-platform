import type { Preferences } from '@vben/preferences';

import {
  defineOverridesPreferences,
  definePreferencesExtension,
} from '@vben/preferences';

import { vbenPreferencesOverrides } from '@crdp/tokens';

interface WebAntdPreferencesExtension {
  defaultTableSize: number;
  enableFormFullscreen: boolean;
  reportTitle: string;
  tenantMode: 'multi' | 'single';
}

/**
 * 侧边栏、顶栏、标签栏保持 Vben 默认样式。
 * 颜色与圆角来自 docs/design/tokens.ts（主色 #3A75FF）。
 */
export const overridesPreferences = defineOverridesPreferences({
  ...vbenPreferencesOverrides,
  app: {
    ...vbenPreferencesOverrides.app,
    defaultHomePath: '/dashboard',
    enableCheckUpdates: false,
    enableRefreshToken: false,
    name: import.meta.env.VITE_APP_TITLE,
  },
  theme: {
    ...vbenPreferencesOverrides.theme,
    fontSize: 14,
  },
  widget: {
    fullscreen: true,
    globalSearch: false,
    languageToggle: false,
    lockScreen: false,
    // E09-T02 站内信铃铛（A-NTF-002 / C-NTF-002）：顶栏通知位
    notification: true,
    refresh: true,
    sidebarToggle: true,
    themeToggle: false,
    timezone: false,
  },
} as Partial<Preferences>);

export const preferencesExtension =
  definePreferencesExtension<WebAntdPreferencesExtension>({
    fields: [
      {
        component: 'switch',
        defaultValue: true,
        key: 'enableFormFullscreen',
        label: 'preferences.antd.fields.enableFormFullscreen.label',
        tip: 'preferences.antd.fields.enableFormFullscreen.tip',
      },
      {
        component: 'select',
        defaultValue: 'single',
        key: 'tenantMode',
        label: 'preferences.antd.fields.tenantMode.label',
        options: [
          {
            label: 'preferences.antd.fields.tenantMode.options.single.label',
            value: 'single',
          },
          {
            label: 'preferences.antd.fields.tenantMode.options.multi.label',
            value: 'multi',
          },
        ],
      },
      {
        component: 'number',
        componentProps: { max: 200, min: 10, step: 10 },
        defaultValue: 20,
        key: 'defaultTableSize',
        label: 'preferences.antd.fields.defaultTableSize.label',
      },
      {
        component: 'input',
        defaultValue: '',
        key: 'reportTitle',
        label: 'preferences.antd.fields.reportTitle.label',
        placeholder: 'preferences.antd.fields.reportTitle.placeholder',
      },
    ],
    tabLabel: 'preferences.antd.tabLabel',
    title: 'preferences.antd.title',
  });
