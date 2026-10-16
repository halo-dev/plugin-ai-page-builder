<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { Toast, VAlert, VButton, VEmpty, VLoading, VModal } from '@halo-dev/components'
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

const previewLink = computed(() =>
  selected.value ? revisionPreviewUrl(props.name, selected.value.name) : '',
)
const previewLoaded = shallowRef(false)
// The iframe is recreated when the revision or tab changes; reset the loading veil for both.
watch([selected, view], () => (previewLoaded.value = false))

const SOURCE_LABELS: Record<string, string> = {
  ai: 'AI 生成',
  manual: '手动保存',
  restore: '恢复',
}
const sourceLabel = (source: string) => SOURCE_LABELS[source] ?? source
const sourceIcon = (source: string) =>
  source === 'ai' ? IconSparkles : source === 'restore' ? IconHistory : IconEdit

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
</script>

<template>
  <VModal title="历史版本" :width="1280" @close="emit('close')">
    <!-- Wrap conditional content in a stable container for VModal's default slot. -->
    <div>
      <VLoading v-if="revisionsQuery.isLoading.value" />
      <VAlert v-if="revisionsQuery.isError.value" type="error" title="历史版本加载失败"
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
      />
      <div v-if="revisionsQuery.data.value?.length" class="flex h-[68vh] flex-col md:flex-row">
        <nav
          class="max-h-44 shrink-0 overflow-auto border-b border-gray-100 md:max-h-none md:w-60 md:border-b-0 md:border-r"
          aria-label="历史版本列表"
        >
          <button
            v-for="revision in revisionsQuery.data.value"
            :key="revision.name"
            type="button"
            class="relative flex w-full flex-col gap-1 border-b border-gray-100 px-4 py-3 text-left transition-colors hover:bg-gray-50"
            :class="{ 'bg-gray-50': selected?.name === revision.name }"
            :aria-pressed="selected?.name === revision.name"
            @click="selected = revision"
          >
            <span
              v-if="selected?.name === revision.name"
              class="absolute inset-y-2 left-0 w-0.5 rounded-full bg-gray-900"
            />
            <strong class="truncate text-[13px] text-gray-900">{{ revision.title }}</strong>
            <span class="text-xs text-gray-500">{{
              new Date(revision.createdAt).toLocaleString()
            }}</span>
            <span class="flex flex-wrap items-center gap-1.5">
              <span
                class="inline-flex items-center gap-1 rounded-full bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-600"
              >
                <component :is="sourceIcon(revision.source)" />
                {{ sourceLabel(revision.source) }} · {{ revision.createdBy }}
              </span>
              <span
                v-if="revision.name === draftRevision"
                class="rounded-full bg-blue-50 px-1.5 py-0.5 text-[11px] text-blue-600"
                >当前草稿</span
              >
              <span
                v-if="revision.name === publishedRevision"
                class="rounded-full bg-green-50 px-1.5 py-0.5 text-[11px] text-green-600"
                >已发布</span
              >
            </span>
          </button>
        </nav>
        <section v-if="selected" class="flex min-h-0 min-w-0 flex-1 flex-col">
          <header
            class="flex shrink-0 flex-wrap items-center gap-2 border-b border-gray-100 px-4 py-3"
          >
            <div class="min-w-0 flex-1 basis-40">
              <h3 class="truncate text-sm font-semibold text-gray-900">{{ selected.title }}</h3>
              <p class="truncate text-xs text-gray-500">
                {{ new Date(selected.createdAt).toLocaleString() }} ·
                {{ sourceLabel(selected.source) }} · {{ selected.createdBy }}
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
          <div v-if="view === 'preview'" class="relative flex min-h-0 flex-1 flex-col bg-gray-100">
            <div
              v-if="!previewLoaded"
              class="absolute inset-0 z-10 flex items-center justify-center"
            >
              <VLoading />
            </div>
            <FixedPreviewViewport :device="device" class="p-4">
              <!-- 内嵌预览必须隔离：同源无 sandbox 的 iframe 可通过 window.parent 操作已登录的
                   Console。不透明源下脚本仍可运行，但无法携带凭据请求同源接口；需要完整
                   保真（如依赖公开接口的页面）时使用「在新窗口打开预览」。 -->
              <iframe
                :key="selected.name"
                :src="previewLink"
                class="block h-full w-full rounded-lg border-0 bg-white shadow-sm"
                title="历史版本预览"
                sandbox="allow-scripts"
                referrerpolicy="no-referrer"
                @load="previewLoaded = true"
              />
            </FixedPreviewViewport>
          </div>
          <div v-else class="min-h-0 flex-1 overflow-auto p-4">
            <VLoading v-if="detailQuery.isFetching.value" />
            <VAlert
              v-else-if="detailQuery.isError.value"
              type="error"
              title="版本内容加载失败"
              description="无法获取该版本的源码，暂不能对比或恢复。"
              ><template #actions
                ><VButton @click="detailQuery.refetch()">重新加载</VButton></template
              ></VAlert
            >
            <template v-else-if="document"
              ><p class="mb-3 text-xs text-gray-500">当前编辑内容 → 选中版本</p>
              <p class="mb-3 text-xs text-gray-500">
                标题：{{ current.title }} → {{ document.title }}<br />描述：{{
                  current.description || '无'
                }}
                → {{ document.description || '无' }}
              </p>
              <SourceComparison :before="current" :after="document"
            /></template>
          </div>
        </section>
      </div>
    </div>
    <template #footer><VButton @click="emit('close')">关闭</VButton></template>
  </VModal>
</template>
