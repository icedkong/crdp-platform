import { effectScope } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { usePlaintextReveal } from './plaintext';

describe('usePlaintextReveal', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('clears plaintext after 60 seconds and on dispose', async () => {
    vi.useFakeTimers();
    const scope = effectScope();
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const api = scope.run(() =>
      usePlaintextReveal({
        reveal: async () => '测试患者01（示例）',
        ttlMs: 60_000,
      }),
    );
    if (!api) throw new Error('scope');
    await api.confirm();
    expect(api.plaintext.value).toBe('测试患者01（示例）');
    expect(api.state.value).toBe('revealed');
    expect(setItem).not.toHaveBeenCalled();
    vi.advanceTimersByTime(60_000);
    expect(api.plaintext.value).toBe('');
    expect(api.state.value).toBe('masked');
    await api.confirm();
    scope.stop();
    expect(api.plaintext.value).toBe('');
    setItem.mockRestore();
  });
});
