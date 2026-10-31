import { onScopeDispose, ref } from 'vue';

/**
 * 明文只活在这个组合函数的内存里。
 * 不写 localStorage / sessionStorage / IndexedDB / URL。
 */
export function usePlaintextReveal(options: {
  reveal: () => Promise<string>;
  ttlMs: number;
}) {
  const state = ref<'loading' | 'masked' | 'revealed'>('masked');
  const plaintext = ref('');
  let timer: ReturnType<typeof setTimeout> | undefined;

  function clear() {
    plaintext.value = '';
    state.value = 'masked';
    if (timer) {
      clearTimeout(timer);
      timer = undefined;
    }
  }

  async function confirm() {
    state.value = 'loading';
    plaintext.value = await options.reveal();
    state.value = 'revealed';
    timer = setTimeout(clear, options.ttlMs);
  }

  onScopeDispose(clear);

  return { clear, confirm, plaintext, state };
}
