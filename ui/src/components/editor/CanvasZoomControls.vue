<script setup lang="ts">
import { computed } from 'vue'
import IconZoomIn from '~icons/mingcute/zoom-in-line'
import IconZoomOut from '~icons/mingcute/zoom-out-line'
import { MAX_PREVIEW_SCALE, MIN_PREVIEW_SCALE } from '../../editor/preview-canvas'

const props = defineProps<{ scale: number }>()
const emit = defineEmits<{ zoom: [direction: -1 | 1]; fit: [] }>()
const scalePercent = computed(() => Math.round(props.scale * 100))
</script>

<template>
  <div
    class="absolute bottom-6 right-6 z-[5] flex items-center rounded-lg border border-gray-200 bg-white text-xs shadow-sm"
    role="group"
    aria-label="画布缩放"
  >
    <button
      type="button"
      class="apb-icon-btn h-7 w-7"
      aria-label="缩小"
      :disabled="scale <= MIN_PREVIEW_SCALE"
      @click="emit('zoom', -1)"
    >
      <IconZoomOut class="text-sm" />
    </button>
    <button
      type="button"
      class="h-7 min-w-12 px-1 text-center text-gray-600 hover:bg-gray-50"
      title="适应画布"
      aria-label="适应画布"
      @click="emit('fit')"
    >
      {{ scalePercent }}%
    </button>
    <button
      type="button"
      class="apb-icon-btn h-7 w-7"
      aria-label="放大"
      :disabled="scale >= MAX_PREVIEW_SCALE"
      @click="emit('zoom', 1)"
    >
      <IconZoomIn class="text-sm" />
    </button>
  </div>
</template>
