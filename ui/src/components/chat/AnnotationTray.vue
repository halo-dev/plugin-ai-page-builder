<script setup lang="ts">
import IconChevron from '~icons/mingcute/right-line'
import IconClose from '~icons/mingcute/close-line'
import type { AnnotationDraft } from '../../editor/annotations'

defineProps<{
  annotations: AnnotationDraft[]
  running: boolean
  disabled: boolean
}>()
const emit = defineEmits<{ remove: [id: string] }>()
const open = defineModel<boolean>('open', { default: false })
</script>

<template>
  <div v-if="annotations.length" class="mb-2">
    <button
      type="button"
      class="flex w-full items-center gap-1.5 rounded-lg border border-gray-200 bg-gray-50 px-2.5 py-1.5 text-xs text-gray-600"
      :aria-expanded="open"
      @click="open = !open"
    >
      <IconChevron
        class="shrink-0 text-gray-400 transition-transform"
        :class="{ 'rotate-90': open }"
      />
      <span class="shrink-0 font-medium">{{ annotations.length }} 条批注</span>
      <span class="min-w-0 truncate text-gray-400">
        {{ annotations[0]?.tag }} · {{ annotations[0]?.summary }}
      </span>
    </button>
    <div v-if="open" class="mt-1.5 space-y-1.5">
      <div
        v-for="item in annotations"
        :key="item.id"
        class="flex items-start gap-2 rounded-lg border px-2.5 py-1.5 text-xs leading-5"
        :class="
          item.stale
            ? 'border-amber-200 bg-amber-50 text-amber-700'
            : 'border-gray-200 bg-gray-50 text-gray-600'
        "
      >
        <div class="min-w-0 flex-1">
          <span class="font-medium">{{ item.tag }}</span> · {{ item.summary }}
          <p class="truncate text-gray-400">{{ item.comment }}</p>
          <p v-if="item.stale">内容已变化，请移除后重新选择</p>
        </div>
        <button
          type="button"
          class="apb-icon-btn h-6 w-6 shrink-0"
          aria-label="移除批注"
          :disabled="running || disabled"
          @click="emit('remove', item.id)"
        >
          <IconClose class="text-xs" />
        </button>
      </div>
    </div>
  </div>
</template>
