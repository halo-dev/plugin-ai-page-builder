<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import IconRefresh from '~icons/mingcute/refresh-2-line'
import type { PageDocument } from '../api/generated'
import type { PreviewRootState } from '../editor/preview'
import type { PreviewDevice } from '../editor/preview-canvas'
import FixedPreviewViewport from './FixedPreviewViewport.vue'
import { runtimePreview } from '../editor/runtime-preview'
import { errorMessage } from '../utils/error'

const props = defineProps<{
  document: PageDocument
  dirty: boolean
  device: PreviewDevice
  previewCss: string
  rootState?: PreviewRootState
}>()
const source = shallowRef('')
const error = shallowRef('')
const generation = shallowRef(0)
watch(
  () => [props.document, props.previewCss, props.rootState, generation.value] as const,
  async (_, __, onCleanup) => {
    let cancelled = false
    onCleanup(() => {
      cancelled = true
    })
    source.value = ''
    error.value = ''
    try {
      const next = await runtimePreview(props.document, props.previewCss, props.rootState)
      if (!cancelled) source.value = next
    } catch (e) {
      if (!cancelled) error.value = errorMessage(e)
    }
  },
  { immediate: true },
)
</script>

<template>
  <section class="flex h-full min-h-0 flex-col gap-3 p-4" aria-label="隔离运行预览">
    <div class="flex shrink-0 items-center justify-between gap-3 text-xs text-gray-500">
      <p class="min-w-0">
        {{ dirty ? '显示上次保存的草稿；保存后运行最新修改。' : '运行已保存草稿的完整脚本。' }}
        脚本无法读取控制台，fetch/XHR 等连接已禁用；页面仍可加载外部图片。
      </p>
      <button type="button" class="apb-btn-outline h-7 shrink-0 px-2 text-xs" @click="generation++">
        <IconRefresh class="text-sm" />重新运行
      </button>
    </div>
    <p v-if="error" role="alert" class="text-xs text-red-600">{{ error }}</p>
    <FixedPreviewViewport v-if="source" :device="device">
      <iframe
        :key="generation"
        class="block h-full w-full rounded-lg border-0 bg-white"
        title="页面运行预览"
        sandbox="allow-scripts"
        referrerpolicy="no-referrer"
        :srcdoc="source"
      />
    </FixedPreviewViewport>
  </section>
</template>
