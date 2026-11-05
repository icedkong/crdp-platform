import { onMounted, onUnmounted, ref } from 'vue';

/**
 * Ref<number>：底部固定操作栏距视口底部的偏移（px）。
 */
export type KeyboardSafeBottom = ReturnType<typeof useKeyboardSafeBottom>;

/**
 * E24-T02 / NFR-RESP-002 · 键盘弹出时底部操作栏跟随可视区域（pages.md §5.2
 * 「随 visualViewport 跟随键盘」）。
 *
 * 手机上软键盘弹出会压缩 visualViewport（布局视口不变）：固定 `bottom: 0` 的
 * 操作栏会被键盘盖住。这里按
 * `offset = window.innerHeight − visualViewport.height − visualViewport.offsetTop`
 * 实时计算操作栏应抬升的像素（键盘高度 + 可视区滚动偏移），页面把它绑到底部栏
 * 的 `bottom` 上。无 visualViewport（旧浏览器 / 桌面）恒为 0；SSR 安全
 * （不在模块顶层访问 window）。
 */
export function useKeyboardSafeBottom() {
  const offset = ref(0);

  function update() {
    if (typeof window === 'undefined') return;
    const viewport = window.visualViewport;
    if (!viewport) {
      offset.value = 0;
      return;
    }
    offset.value = Math.max(
      0,
      Math.round(window.innerHeight - viewport.height - viewport.offsetTop),
    );
  }

  if (typeof window !== 'undefined') update();

  onMounted(() => {
    update();
    window.visualViewport?.addEventListener('resize', update);
    window.visualViewport?.addEventListener('scroll', update);
    window.addEventListener('resize', update);
  });

  onUnmounted(() => {
    window.visualViewport?.removeEventListener('resize', update);
    window.visualViewport?.removeEventListener('scroll', update);
    window.removeEventListener('resize', update);
  });

  return offset;
}
