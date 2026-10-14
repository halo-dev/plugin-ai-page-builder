/// <reference types="vite/client" />
/// <reference types="unplugin-icons/types/vue" />

import type { DefineComponent } from 'vue'

declare module 'vue' {
  interface GlobalComponents {
    VCodemirror: DefineComponent<{
      modelValue?: string
      language?: 'html' | 'css' | 'javascript'
      height?: string
      'onUpdate:modelValue'?: (value: string) => void
    }>
  }
}
