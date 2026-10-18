<script setup lang="ts">
import { watch } from 'vue'
import { VAlert, VButton, VLoading } from '@halo-dev/components'
import type { PageDocument } from '../api/generated'
import type { PreviewDevice } from '../editor/preview-canvas'
import { usePreviewFrame } from '../composables/usePreviewFrame'
import FixedPreviewViewport from './FixedPreviewViewport.vue'

const props = defineProps<{
  src: string
  document: PageDocument
  device: PreviewDevice
  themeLayout: boolean
}>()
const { loading, error, generation, reload } = usePreviewFrame(() => props.src)
// The endpoint renders the saved draft and layout, so reload when either changes.
watch(() => [props.document, props.themeLayout], reload)
defineExpose({ refresh: reload })
</script>

<template>
  <section class="flex h-full min-h-0 flex-col bg-gray-100 p-3" aria-label="主题运行预览">
    <VAlert v-if="error" type="error" title="预览加载失败" :description="error">
      <template #actions>
        <VButton @click="reload">重新加载</VButton>
      </template>
    </VAlert>
    <VLoading v-else-if="loading" />
    <FixedPreviewViewport v-else-if="src" :device="device">
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
