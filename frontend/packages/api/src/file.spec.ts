import { describe, expect, it } from 'vitest';

import { previewGrantExpired } from './file';

describe('e10-t04 预览链接过期判定', () => {
  const grant = {
    expiresAt: 1_799_000_000,
    format: 'mp3',
    inlinePlayable: true,
    mime: 'audio/mpeg',
    originalName: '通话录音（示例）.mp3',
    url: '/api/hospital/files/preview/t',
  };

  it('过期时刻之前不算过期', () => {
    expect(previewGrantExpired(grant, (grant.expiresAt - 1) * 1000)).toBe(
      false,
    );
  });

  it('到点即过期（前端重新取链接，不用旧链接硬拉）', () => {
    expect(previewGrantExpired(grant, grant.expiresAt * 1000)).toBe(true);
    expect(previewGrantExpired(grant, (grant.expiresAt + 1) * 1000)).toBe(true);
  });
});
