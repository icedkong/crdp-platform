import { describe, expect, it } from 'vitest';

import {
  applyTenantCodeMemory,
  normalizeTenantCode,
  recalledTenantCode,
} from './tenant-code-memory';

function memory() {
  const backing = new Map<string, string>();
  return {
    getItem: (key: string) => backing.get(key) ?? null,
    backing,
    removeItem: (key: string) => backing.delete(key),
    setItem: (key: string, value: string) => backing.set(key, value),
  };
}

describe('normalizeTenantCode', () => {
  it('trims and lowercases the unified login page input', () => {
    expect(normalizeTenantCode(' DemoA ')).toBe('demoa');
  });

  it('rejects values that are not valid tenant codes', () => {
    expect(normalizeTenantCode('1abc')).toBeNull();
    expect(normalizeTenantCode('Demo-1')).toBeNull();
    expect(normalizeTenantCode('')).toBeNull();
    expect(normalizeTenantCode(null)).toBeNull();
  });
});

describe('recalledTenantCode', () => {
  it('recalls a previously remembered code', () => {
    const storage = memory();
    storage.setItem('crdp.tenantCode', 'demoa');
    expect(recalledTenantCode(storage)).toBe('demoa');
  });

  it('ignores an empty slot and a tampered invalid value', () => {
    expect(recalledTenantCode(memory())).toBeNull();
    const tampered = memory();
    tampered.setItem('crdp.tenantCode', 'NOT A CODE');
    expect(recalledTenantCode(tampered)).toBeNull();
  });
});

describe('applyTenantCodeMemory', () => {
  it('stores only the code when remember is checked', () => {
    const storage = memory();
    applyTenantCodeMemory(storage, ' DemoA ', true);
    expect(storage.backing.get('crdp.tenantCode')).toBe('demoa');
  });

  it('clears the stored code when remember is unchecked', () => {
    const storage = memory();
    storage.setItem('crdp.tenantCode', 'demoa');
    applyTenantCodeMemory(storage, 'demoa', false);
    expect(storage.backing.has('crdp.tenantCode')).toBe(false);
  });

  it('never stores an invalid code', () => {
    const storage = memory();
    applyTenantCodeMemory(storage, 'NOT A CODE', true);
    expect(storage.backing.has('crdp.tenantCode')).toBe(false);
  });
});
