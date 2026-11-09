// E24-T07 真机兼容（iOS Safari / 微信内置浏览器）：全面屏 iPhone 上
// env(safe-area-inset-bottom) 只有在 <meta name="viewport"> 含 viewport-fit=cover
// 时才返回真实值（Safari / WKWebView 的既定行为）；缺了它恒为 0，表单底部操作栏、
// 手机上传页提交栏的 safe-area 内边距全部失效，按钮被 Home 指示条压住——真机阻断。
// 本用例静态校验三个外壳（医院端 / 平台端 / 手机上传 H5）的 index.html 都带
// viewport-fit=cover，防止后续被「清理」掉。
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const APP_INDEX_HTML: Array<{ expected: string; label: string }> = [
  {
    expected: '../../../apps/web-hospital/index.html',
    label: '医院端',
  },
  {
    expected: '../../../apps/web-platform/index.html',
    label: '平台端',
  },
  {
    expected: '../../../apps/mobile-upload/index.html',
    label: '手机上传 H5',
  },
];

describe('e24-T07 viewport-fit=cover（env(safe-area-inset-*) 生效前提）', () => {
  for (const app of APP_INDEX_HTML) {
    it(`${app.label} index.html 的 viewport 含 viewport-fit=cover`, () => {
      const htmlPath = fileURLToPath(new URL(app.expected, import.meta.url));
      const html = readFileSync(htmlPath, 'utf8');
      const viewport = html.match(
        /<meta\s+name="viewport"\s+content="([^"]*)"/u,
      )?.[1];
      expect(viewport, `${app.label} 必须声明 viewport meta`).toBeTruthy();
      expect(viewport).toContain('width=device-width');
      expect(viewport).toContain('viewport-fit=cover');
    });
  }
});
