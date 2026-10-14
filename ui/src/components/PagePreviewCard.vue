<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { VButton } from '@halo-dev/components'
import { candidatePreview } from '../editor/preview'
import { loadCardPreview } from '../editor/card-preview'

const props = defineProps<{ name: string; revision: string; title: string }>()
const emit = defineEmits<{ open: [] }>()
const root = ref<HTMLElement>()
const content = ref<{ html: string; css: string }>()
const loading = ref(false)
const failed = ref(false)
const visible = ref(false)
const width = ref(320)
let generation = 0
let observer: IntersectionObserver | undefined
let resize: ResizeObserver | undefined
const srcdoc = computed(() => (content.value ? candidatePreview(content.value) : ''))
const empty = computed(() => !content.value?.html.trim())
async function load() {
  if (!visible.value) return
  const id = ++generation
  loading.value = true
  failed.value = false
  content.value = undefined
  try {
    const result = await loadCardPreview(props.name, props.revision)
    if (id === generation) content.value = result
  } catch {
    if (id === generation) failed.value = true
  } finally {
    if (id === generation) loading.value = false
  }
}
watch(() => [props.name, props.revision], load)
onMounted(() => {
  if (!root.value) return
  resize = new ResizeObserver(([entry]) => {
    if (entry) width.value = entry.contentRect.width
  })
  resize.observe(root.value)
  observer = new IntersectionObserver(
    ([entry]) => {
      if (entry?.isIntersecting && !visible.value) {
        visible.value = true
        void load()
      }
    },
    { rootMargin: '160px' },
  )
  observer.observe(root.value)
})
onBeforeUnmount(() => {
  generation++
  observer?.disconnect()
  resize?.disconnect()
})
</script>

<template>
  <div
    ref="root"
    class="relative aspect-[16/10] overflow-hidden border-b border-gray-200 bg-gray-100"
  >
    <iframe
      v-if="content && !empty"
      :key="`${name}:${revision}`"
      :title="`${title}的草稿预览`"
      :srcdoc="srcdoc"
      sandbox=""
      referrerpolicy="no-referrer"
      tabindex="-1"
      aria-hidden="true"
      class="pointer-events-none absolute inset-0 h-[675px] w-[1080px] border-0 bg-white [transform-origin:top_left]"
      :style="{ transform: `scale(${width / 1080})` }"
    />
    <div
      v-else
      class="flex h-full flex-col items-center justify-center gap-3 text-xs text-gray-400"
    >
      <template v-if="loading"><span class="h-2 w-20 rounded bg-gray-200" />正在加载预览</template>
      <template v-else-if="failed"
        >暂时无法预览<VButton size="sm" @click.stop="load">重试</VButton></template
      >
      <template v-else
        ><span
          class="grid h-[86px] w-[68px] place-content-center rounded border border-gray-200 bg-white text-2xl text-gray-300"
          >Aa</span
        >{{ content ? '从一张空白页面开始' : '页面预览' }}</template
      >
    </div>
    <button
      v-if="!failed"
      class="group absolute inset-0 grid cursor-pointer place-items-center border-0 bg-transparent focus-visible:outline-2 focus-visible:-outline-offset-3 focus-visible:outline-gray-900"
      :aria-label="`编辑${title}`"
      @click="emit('open')"
    >
      <span
        class="rounded-md bg-gray-900 px-3.5 py-2 text-xs text-white opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
        >打开编辑器 ↗</span
      >
    </button>
    <span
      class="pointer-events-none absolute bottom-2.5 left-2.5 rounded bg-white/90 px-1.5 py-0.5 text-[10px] text-gray-500"
      >草稿预览</span
    >
  </div>
</template>
