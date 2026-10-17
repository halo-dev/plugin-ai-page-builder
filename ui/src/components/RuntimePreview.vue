<script setup lang="ts">
import { ref, watch } from 'vue'
import type { PageDocument } from '../api/generated'
import type { PreviewDevice } from '../editor/preview-canvas'
import FixedPreviewViewport from './FixedPreviewViewport.vue'

const props = defineProps<{
  src: string
  document: PageDocument
  device: PreviewDevice
}>()
const generation = ref(0)
function refresh() {
  generation.value++
}
// The endpoint renders the saved draft, so reload when that document changes.
watch(() => props.document, refresh)
defineExpose({ refresh })
</script>

<template>
  <section class="flex h-full min-h-0 flex-col bg-gray-100 p-3" aria-label="主题运行预览">
    <FixedPreviewViewport v-if="src" :device="device">
      <iframe
        :key="generation"
        :src="src"
        class="block h-full w-full border-0 bg-white"
        title="页面运行预览"
        sandbox="allow-scripts"
        referrerpolicy="no-referrer"
      />
    </FixedPreviewViewport>
  </section>
</template>
