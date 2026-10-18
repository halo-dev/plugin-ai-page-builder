<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { Toast, VAlert, VButton, VEmpty, VLoading, VModal } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import IconSparkles from '~icons/mingcute/sparkles-line'
import IconEdit from '~icons/mingcute/edit-2-line'
import IconHistory from '~icons/mingcute/history-line'
import IconLink from '~icons/mingcute/link-line'
import IconExternal from '~icons/mingcute/external-link-line'
import IconEye from '~icons/mingcute/eye-line'
import IconCode from '~icons/mingcute/code-line'
import IconDesktop from '~icons/mingcute/computer-line'
import IconTablet from '~icons/mingcute/pad-line'
import IconMobile from '~icons/mingcute/cellphone-line'
import { aiPageBuilderApi, revisionPreviewUrl } from '../api/clients'
import { pageKeys, queryClient } from '../lib/query-client'
import type { PageDocument, RevisionSummary } from '../api/generated'
import type { PreviewDevice } from '../editor/preview-canvas'
import SourceComparison from './SourceComparison.vue'
import FixedPreviewViewport from './FixedPreviewViewport.vue'
import { usePreviewFrame } from '../composables/usePreviewFrame'
import ApbSegmented from './ui/ApbSegmented.vue'

const props = defineProps<{
  name: string
  current: PageDocument
  busy: boolean
  draftRevision?: string
  publishedRevision?: string | null
}>()
const emit = defineEmits<{ close: []; restore: [revision: string] }>()
const selected = shallowRef<RevisionSummary>()
const revisionsQuery = useQuery({
  queryKey: computed(() => pageKeys.revisions(props.name)),
  queryFn: async () => (await aiPageBuilderApi.listPageRevisions({ name: props.name })).data,
  queryClient,
})
watch(
  () => revisionsQuery.data.value,
  (revisions) => {
    if (!selected.value && revisions?.length) selected.value = revisions[0]
  },
  // Data may already be cached when the panel reopens.
  { immediate: true },
)
const detailQuery = useQuery({
  queryKey: computed(() => pageKeys.revision(props.name, selected.value?.name || '')),
  queryFn: async () =>
    (await aiPageBuilderApi.getPageRevision({ name: props.name, revision: selected.value!.name }))
      .data,
  enabled: computed(() => !!selected.value),
  queryClient,
})
const document = computed(() => detailQuery.data.value?.document)

const view = shallowRef<'preview' | 'diff'>('preview')
const viewOptions = [
  { value: 'preview', label: '预览', icon: IconEye },
  { value: 'diff', label: '代码差异', icon: IconCode },
] as const
const device = shallowRef<PreviewDevice>('desktop')
const deviceOptions = [
  { value: 'desktop', icon: IconDesktop, title: '桌面 1280px' },
  { value: 'tablet', icon: IconTablet, title: '平板 768px' },
  { value: 'mobile', icon: IconMobile, title: '手机 390px' },
] as const

// The diff tab compares against the revision's own parent revision, not the current draft
// or the adjacent list entry: a failed save can leave an orphan revision in the
// chronologically sorted list, but spec.parentRevision always records the true parent.
// The first revision has no parent, so an empty document is used as "before".
// Taken from the list item (not the detail) so both detail queries can run in parallel.
const parentRevision = computed(() => selected.value?.parentRevision || undefined)
const previousDetailQuery = useQuery({
  queryKey: computed(() => pageKeys.revision(props.name, parentRevision.value || '')),
  queryFn: async () =>
    (
      await aiPageBuilderApi.getPageRevision({
        name: props.name,
        revision: parentRevision.value!,
      })
    ).data,
  enabled: computed(() => !!parentRevision.value && view.value === 'diff'),
  queryClient,
})
const previousDocument = computed(() => previousDetailQuery.data.value?.document)
const EMPTY_DOCUMENT: Pick<PageDocument, 'html' | 'css' | 'js'> = { html: '', css: '', js: '' }

const previewLink = computed(() =>
  selected.value ? revisionPreviewUrl(props.name, selected.value.name) : '',
)
const previewSrc = computed(() => (view.value === 'preview' ? previewLink.value : ''))
// Load the revision preview directly, exactly like the "运行" tab: the server sets a
// sandbox CSP (opaque origin, public APIs only), and the iframe adds allow-scripts without
// allow-same-origin. Scripts run in isolation, and theme assets load via the CORS filter.
const {
  loading: previewLoading,
  error: previewError,
  generation: previewGeneration,
  reload: reloadPreview,
} = usePreviewFrame(() => previewSrc.value)

const SOURCE_LABELS: Record<string, string> = {
  ai: 'AI 生成',
  manual: '手动保存',
  restore: '恢复',
}
const sourceLabel = (source: string) => SOURCE_LABELS[source] ?? source
const sourceIcon = (source: string) =>
  source === 'ai' ? IconSparkles : source === 'restore' ? IconHistory : IconEdit

const fullTime = (value: string) => new Date(value).toLocaleString()
const timeAgo = (value: string) => utils.date.timeAgo(value) ?? fullTime(value)

async function copyPreviewLink() {
  try {
    await navigator.clipboard.writeText(location.origin + previewLink.value)
    Toast.success('预览链接已复制，仅超级管理员可访问')
  } catch {
    Toast.error('复制失败，请通过「在新窗口打开预览」后复制地址栏链接')
  }
}
function openPreview() {
  window.open(previewLink.value, '_blank', 'noopener')
}
function refetchDetails() {
  detailQuery.refetch()
  if (parentRevision.value) previousDetailQuery.refetch()
}
</script>

<template>
  <VModal
    title="历史版本"
    :width="1280"
    height="calc(100vh - 20px)"
    centered
    :body-class="['!p-0']"
    @close="emit('close')"
  >
    <!-- Wrap conditional content in a stable container for VModal's default slot. -->
    <div class="h-full">
      <VLoading v-if="revisionsQuery.isLoading.value" class="m-4" />
      <VAlert v-if="revisionsQuery.isError.value" type="error" title="历史版本加载失败" class="m-4"
        ><template #actions
          ><VButton @click="revisionsQuery.refetch()">重新加载</VButton></template
        ></VAlert
      >
      <VEmpty
        v-if="
          !revisionsQuery.isLoading.value &&
          !revisionsQuery.isError.value &&
          !revisionsQuery.data.value?.length
        "
        title="暂无历史版本"
        class="m-4"
      />
      <div
        v-if="revisionsQuery.data.value?.length"
        class="flex h-full min-h-0 flex-col md:flex-row"
      >
        <nav
          class="max-h-44 shrink-0 overflow-auto border-b border-gray-100 p-1.5 md:max-h-none md:w-60 md:border-b-0 md:border-r"
          aria-label="历史版本列表"
        >
          <div class="flex flex-col gap-0.5">
            <button
              v-for="revision in revisionsQuery.data.value"
              :key="revision.name"
              type="button"
              class="relative flex w-full flex-col gap-1 rounded-md px-2.5 py-2 text-left transition-colors"
              :class="selected?.name === revision.name ? 'bg-gray-100' : 'hover:bg-gray-50'"
              :aria-pressed="selected?.name === revision.name"
              @click="selected = revision"
            >
              <span
                v-if="selected?.name === revision.name"
                class="absolute inset-y-2 left-0 w-0.5 rounded-full bg-gray-900"
              />
              <span class="flex items-center gap-1.5">
                <strong class="min-w-0 flex-1 truncate text-[13px] font-medium text-gray-900">{{
                  revision.title
                }}</strong>
                <span
                  v-if="revision.name === draftRevision"
                  class="shrink-0 rounded bg-blue-50 px-1 text-[10px] leading-4 text-blue-600"
                  >当前草稿</span
                >
                <span
                  v-if="revision.name === publishedRevision"
                  class="shrink-0 rounded bg-green-50 px-1 text-[10px] leading-4 text-green-600"
                  >已发布</span
                >
              </span>
              <span
                class="flex items-center gap-1 text-[11px] text-gray-400"
                :title="fullTime(revision.createdAt)"
              >
                <component :is="sourceIcon(revision.source)" class="shrink-0" />
                <span class="truncate"
                  >{{ sourceLabel(revision.source) }} · {{ timeAgo(revision.createdAt) }} ·
                  {{ revision.createdBy }}</span
                >
              </span>
            </button>
          </div>
        </nav>
        <section v-if="selected" class="flex min-h-0 min-w-0 flex-1 flex-col">
          <header
            class="flex shrink-0 flex-wrap items-center gap-2 border-b border-gray-100 px-4 py-3"
          >
            <div class="min-w-0 flex-1 basis-40">
              <h3 class="truncate text-sm font-semibold text-gray-900">{{ selected.title }}</h3>
              <p class="truncate text-xs text-gray-500" :title="fullTime(selected.createdAt)">
                {{ timeAgo(selected.createdAt) }} · {{ sourceLabel(selected.source) }} ·
                {{ selected.createdBy }}
              </p>
            </div>
            <ApbSegmented v-model="view" :options="viewOptions" />
            <ApbSegmented v-if="view === 'preview'" v-model="device" :options="deviceOptions" />
            <div class="flex items-center gap-1">
              <button
                type="button"
                class="apb-icon-btn"
                title="复制预览链接"
                aria-label="复制预览链接"
                @click="copyPreviewLink"
              >
                <IconLink />
              </button>
              <button
                type="button"
                class="apb-icon-btn"
                title="在新窗口打开预览"
                aria-label="在新窗口打开预览"
                @click="openPreview"
              >
                <IconExternal />
              </button>
              <VButton
                size="sm"
                type="secondary"
                :disabled="!document || busy || detailQuery.isFetching.value"
                @click="emit('restore', selected.name)"
                >恢复为新草稿</VButton
              >
            </div>
          </header>
          <div v-if="view === 'preview'" class="relative flex min-h-0 flex-1 flex-col bg-gray-100 p-4">
            <VAlert
              v-if="previewError"
              type="error"
              title="预览加载失败"
              :description="previewError"
            >
              <template #actions>
                <VButton @click="reloadPreview">重新加载</VButton>
              </template>
            </VAlert>
            <VLoading v-else-if="previewLoading" class="m-auto" />
            <FixedPreviewViewport v-else :device="device" fit="width">
              <!-- 与「运行」一致的隔离方式：iframe 只放行脚本、不放行同源，服务端预览响应
                   另带 sandbox CSP，脚本在不透明源中执行，无法读取 Console 或站点数据。 -->
              <iframe
                :key="`${selected.name}-${previewGeneration}`"
                :src="previewLink"
                class="block h-full w-full rounded-lg border-0 bg-white shadow-sm"
                title="历史版本预览"
                sandbox="allow-scripts"
                referrerpolicy="no-referrer"
              />
            </FixedPreviewViewport>
          </div>
          <div v-else class="min-h-0 flex-1 overflow-auto p-4">
            <VLoading v-if="detailQuery.isFetching.value || previousDetailQuery.isFetching.value" />
            <VAlert
              v-else-if="detailQuery.isError.value || previousDetailQuery.isError.value"
              type="error"
              title="版本内容加载失败"
              description="无法获取该版本的源码，暂不能对比或恢复。"
              ><template #actions
                ><VButton @click="refetchDetails">重新加载</VButton></template
              ></VAlert
            >
            <template v-else-if="document"
              ><p class="mb-3 text-xs text-gray-500">
                {{ parentRevision ? '上一版本 → 选中版本' : '首个版本，修改前无内容' }}
              </p>
              <p class="mb-3 text-xs text-gray-500">
                标题：{{ previousDocument?.title || '无' }} → {{ document.title }}<br />描述：{{
                  previousDocument?.description || '无'
                }}
                → {{ document.description || '无' }}
              </p>
              <SourceComparison :before="previousDocument ?? EMPTY_DOCUMENT" :after="document"
            /></template>
          </div>
        </section>
      </div>
    </div>
    <template #footer><VButton @click="emit('close')">关闭</VButton></template>
  </VModal>
</template>
