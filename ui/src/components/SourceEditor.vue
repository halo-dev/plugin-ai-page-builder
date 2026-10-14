<script setup lang="ts">
import { shallowRef } from 'vue'
import type { PageDocument } from '../api/generated'
import ApbSegmented from './ui/ApbSegmented.vue'
const props = defineProps<{ document: PageDocument; disabled: boolean }>()
const emit = defineEmits<{ change: [value: Partial<Pick<PageDocument, 'html' | 'css' | 'js'>>] }>()
const language = shallowRef<'html' | 'css' | 'js'>('html')
const languages = [
  { value: 'html', label: 'HTML' },
  { value: 'css', label: 'CSS' },
  { value: 'js', label: 'JS' },
] as const
function change(value: string) {
  if (!props.disabled) emit('change', { [language.value]: value })
}
</script>
<template>
  <section class="flex h-full min-h-0 min-w-0 flex-col bg-white" aria-label="页面源码">
    <div class="flex h-11 shrink-0 items-center gap-2 border-b border-gray-200 px-3">
      <ApbSegmented v-model="language" :options="languages" :disabled="disabled" />
      <span class="ml-auto text-xs text-gray-400">编辑后自动校验</span>
    </div>
    <div class="apb-code min-h-0 flex-1 overflow-auto text-[13px]" :inert="disabled || undefined">
      <VCodemirror
        :key="language"
        :model-value="document[language] ?? ''"
        :language="language === 'js' ? 'javascript' : language"
        height="100%"
        @update:model-value="change"
      />
    </div>
  </section>
</template>

<!-- CodeMirror renders its own DOM; only :deep() can reach it. -->
<style scoped>
.apb-code :deep(.cm-editor) {
  min-height: 100%;
}
.apb-code :deep(.cm-scroller) {
  overflow: auto;
}
</style>
