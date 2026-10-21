<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import type { PageDocument } from '../api/generated'

const DiffEditor = defineAsyncComponent(() => import('./DiffEditor.vue'))

defineProps<{
  before: Pick<PageDocument, 'html' | 'css' | 'js'>
  after: Pick<PageDocument, 'html' | 'css' | 'js'>
}>()

const fields = [
  { key: 'html', label: 'HTML', language: 'html' },
  { key: 'css', label: 'CSS', language: 'css' },
  { key: 'js', label: 'JS', language: 'js' },
] as const
</script>

<template>
  <div class="flex flex-col gap-4">
    <section v-for="field in fields" :key="field.key">
      <p class="mb-1.5 text-xs font-medium text-gray-500">
        {{ field.label }}
        <span v-if="(before[field.key] ?? '') === (after[field.key] ?? '')" class="font-normal"
          >（无变化）</span
        >
      </p>
      <DiffEditor
        v-if="(before[field.key] ?? '') !== (after[field.key] ?? '')"
        :original="before[field.key] ?? ''"
        :modified="after[field.key] ?? ''"
        :language="field.language"
      />
    </section>
  </div>
</template>
