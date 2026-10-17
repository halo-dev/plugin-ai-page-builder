<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef, useTemplateRef } from 'vue'
import {
  calculateFitScale,
  calculateWidthFitScale,
  PREVIEW_DEVICES,
  type PreviewDevice,
} from '../editor/preview-canvas'

const props = withDefaults(defineProps<{ device: PreviewDevice; fit?: 'contain' | 'width' }>(), {
  fit: 'contain',
})
const stage = useTemplateRef<HTMLDivElement>('stage')
const stageSize = shallowRef({ width: 0, height: 0 })
const viewport = computed(() => PREVIEW_DEVICES[props.device])
const scale = computed(() =>
  props.fit === 'width'
    ? calculateWidthFitScale(stageSize.value, viewport.value)
    : calculateFitScale(stageSize.value, viewport.value),
)
const wrapperStyle = computed(() => ({
  width: `${viewport.value.width * scale.value}px`,
  height: `${stageSize.value.height || viewport.value.height * scale.value}px`,
}))
const viewportStyle = computed(() => ({
  width: `${viewport.value.width}px`,
  height: `${stageSize.value.height ? stageSize.value.height / scale.value : viewport.value.height}px`,
  transform: `scale(${scale.value})`,
}))
let observer: ResizeObserver | undefined

onMounted(() => {
  const element = stage.value
  if (!element) return
  stageSize.value = { width: element.clientWidth, height: element.clientHeight }
  if (typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(([entry]) => {
    if (entry) {
      stageSize.value = { width: entry.contentRect.width, height: entry.contentRect.height }
    }
  })
  observer.observe(element)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div ref="stage" class="flex min-h-0 min-w-0 flex-1 items-center justify-center overflow-hidden">
    <div class="relative shrink-0" :style="wrapperStyle">
      <div class="absolute left-0 top-0 origin-top-left" :style="viewportStyle">
        <slot />
      </div>
    </div>
  </div>
</template>
