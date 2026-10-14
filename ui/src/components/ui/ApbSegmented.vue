<script setup lang="ts" generic="T extends string">
import type { Component } from 'vue'

defineProps<{
  options: readonly { value: T; label?: string; icon?: Component; title?: string }[]
  disabled?: boolean
}>()
const model = defineModel<T>({ required: true })
</script>

<template>
  <div
    class="inline-flex shrink-0 items-center gap-0.5 rounded-lg bg-gray-100 p-0.5"
    role="tablist"
  >
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="tab"
      :aria-selected="model === option.value"
      :title="option.title || option.label"
      :disabled="disabled"
      class="inline-flex h-7 items-center gap-1.5 rounded-md px-2.5 text-[13px] font-medium transition-colors disabled:opacity-40"
      :class="
        model === option.value
          ? 'bg-white text-gray-900 shadow-sm'
          : 'text-gray-500 hover:text-gray-900'
      "
      @click="model = option.value"
    >
      <component :is="option.icon" v-if="option.icon" class="text-[15px]" />
      <span v-if="option.label">{{ option.label }}</span>
    </button>
  </div>
</template>
