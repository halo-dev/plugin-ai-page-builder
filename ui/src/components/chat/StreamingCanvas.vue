<script setup lang="ts">
import { computed } from 'vue'
import IconSpinner from '~icons/mingcute/loading-line'
import IconCheck from '~icons/mingcute/check-line'
import type { StreamingPreview } from '../../composables/useStreamingPreview'
import { candidatePreview, type PreviewRootState } from '../../editor/preview'
import type { PreviewDevice } from '../../editor/preview-canvas'
import FixedPreviewViewport from '../FixedPreviewViewport.vue'
const props = defineProps<{
  preview: StreamingPreview
  device: PreviewDevice
  previewCss: string
  rootState?: PreviewRootState
}>()
const source = computed(() =>
  props.preview.content
    ? candidatePreview(props.preview.content, props.previewCss, props.rootState)
    : '',
)
const status = computed(
  () =>
    ({
      generating: '生成中 · 临时只读预览',
      validating: '正在校验候选内容…',
      waiting: '等待有效内容 · 保留上次通过校验的临时画面',
      staged: '已暂存 · 等待本轮草稿保存',
    })[props.preview.status],
)
</script>
<template>
  <div class="flex h-full min-h-0 flex-col">
    <p
      class="flex shrink-0 items-center gap-1.5 border-b border-indigo-100 bg-indigo-50 px-4 py-2 text-xs text-indigo-700"
      role="status"
    >
      <IconCheck v-if="preview.status === 'staged'" />
      <IconSpinner v-else class="animate-spin" />
      {{ status }}
    </p>
    <details class="shrink-0 border-b border-gray-100 bg-white px-4 py-2 text-xs" open>
      <summary class="cursor-pointer select-none text-gray-500">
        {{ preview.toolName === 'patch_current_page' ? '局部修改源码' : 'HTML / CSS 源码' }}
      </summary>
      <pre
        class="max-h-48 overflow-auto whitespace-pre-wrap break-all pt-2 font-mono text-gray-600"
        >{{ preview.source || '等待工具输入…' }}</pre>
    </details>
    <div class="flex min-h-0 flex-1 flex-col bg-gray-100 p-3">
      <FixedPreviewViewport v-if="source" :device="device">
        <iframe
          class="block h-full w-full border-0 bg-white"
          title="生成中的临时只读预览"
          sandbox=""
          :srcdoc="source"
        />
      </FixedPreviewViewport>
      <p v-else class="px-4 py-12 text-center text-[13px] text-gray-500">
        内容通过校验后将在这里预览。当前草稿保持不变。
      </p>
    </div>
  </div>
</template>
