import { describe, expect, it } from 'vitest'
import { permissionCode } from './index'

describe('permissionCode', () => {
  it('joins module resource and action', () => {
    expect(permissionCode('followup', 'patient', 'view')).toBe('followup:patient:view')
  })

  it('rejects uppercase fragments', () => {
    expect(() => permissionCode('Followup', 'patient', 'view')).toThrow(/不合法/)
  })
})
