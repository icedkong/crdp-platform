import * as Icons from '@ant-design/icons-vue'
import type { Component } from 'vue'

const catalog = Icons as unknown as Record<string, Component>

export function iconOf(name?: string | null) {
  if (name && catalog[name]) {
    return catalog[name]
  }
  return Icons.AppstoreOutlined
}
