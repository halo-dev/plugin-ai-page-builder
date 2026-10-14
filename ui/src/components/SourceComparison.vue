<script setup lang="ts">
import type { PageDocument } from '../api/generated'
defineProps<{
  before: Pick<PageDocument, 'html' | 'css' | 'js'>
  after: Pick<PageDocument, 'html' | 'css' | 'js'>
}>()
const preClass =
  'max-h-80 overflow-auto whitespace-pre-wrap break-anywhere border border-gray-200 bg-gray-50 p-3 text-xs'
const labelClass = 'mb-1 mt-3 text-xs text-gray-500'
</script>

<template>
  <div class="grid grid-cols-[minmax(0,1fr)] gap-4 md:grid-cols-2">
    <section v-for="side in ['before', 'after'] as const" :key="side">
      <h3 class="text-sm font-semibold">{{ side === 'before' ? '修改前' : '修改后' }}</h3>
      <p :class="labelClass">HTML <span v-if="before.html === after.html">（无变化）</span></p>
      <pre :class="preClass">{{ side === 'before' ? before.html : after.html }}</pre>
      <p :class="labelClass">CSS <span v-if="before.css === after.css">（无变化）</span></p>
      <pre :class="preClass">{{ side === 'before' ? before.css : after.css }}</pre>
      <p :class="labelClass">
        JS <span v-if="(before.js ?? '') === (after.js ?? '')">（无变化）</span>
      </p>
      <pre :class="preClass">{{ (side === 'before' ? before.js : after.js) ?? '' }}</pre>
    </section>
  </div>
</template>
