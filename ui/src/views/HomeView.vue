<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { useMutation } from '@tanstack/vue-query'
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VEmpty,
  VLoading,
  VPageHeader,
  VPagination,
} from '@halo-dev/components'
import IconAddCircle from '~icons/mingcute/add-circle-line'
import IconFileAi from '~icons/tabler/file-ai'
import { aiPageBuilderApi, pagePreviewUrl } from '../api/clients'
import type { PageSummary } from '../api/generated'
import { errorMessage } from '../utils/error'
import { confirmDialog } from '../utils/confirm'
import { usePageWrites } from '../composables/usePageWrites'
import { usePageListQuery } from '../composables/usePageListQuery'
import { usePageSettingsEditor } from '../composables/usePageSettingsEditor'
import { usePublicSiteUrl } from '../composables/usePublicSiteUrl'
import { pageKeys, queryClient } from '../lib/query-client'
import ClonePageModal from '../components/ClonePageModal.vue'
import PageCard from '../components/PageCard.vue'
import PageSettings from '../components/PageSettings.vue'
import CreatePagePanel from '../components/create/CreatePagePanel.vue'

const router = useRouter()
const { page, size, keyword, status, sort, listQuery, items, total, showToolbar } =
  usePageListQuery()
const statusItems = [
  { label: '全部', value: undefined },
  { label: '已发布', value: 'published' },
  { label: '未发布', value: 'unpublished' },
]
const sortItems = [
  { label: '默认（最近更新）', value: undefined },
  { label: '最近创建', value: 'created' },
]
const cloneTarget = shallowRef<PageSummary>()

function openEditor(name: string) {
  void router.push({ name: 'AiPageBuilderEditor', params: { name } })
}
function cloned(name: string) {
  cloneTarget.value = undefined
  openEditor(name)
}
// Published pages go straight to the public URL; drafts fall back to the theme preview.
const { pageUrl } = usePublicSiteUrl()
function open(item: PageSummary) {
  if (!item.publishedRevision) {
    window.open(pagePreviewUrl(item.name), '_blank', 'noopener')
    return
  }
  window.open(pageUrl(item.slug || item.name), '_blank', 'noopener')
}
const exportMutation = useMutation({
  mutationFn: async (item: PageSummary) => {
    const data = (await aiPageBuilderApi.exportPage({ name: item.name }, { responseType: 'blob' }))
      .data
    return { data, slug: item.slug || item.name }
  },
  onSuccess: ({ data, slug }) => {
    const url = URL.createObjectURL(new Blob([data], { type: 'application/zip' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${slug}.zip`
    document.body.append(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    Toast.success('已导出保存的草稿')
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
const removeMutation = useMutation({
  mutationFn: async (item: PageSummary) => {
    await aiPageBuilderApi.deletePage({ name: item.name, version: item.version })
  },
  onSuccess: () => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    Toast.success('页面已删除')
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
const pageWrites = usePageWrites()
async function publishUpdate(item: PageSummary) {
  pageWrites.reset(item)
  try {
    const detail = await pageWrites.refresh()
    if (!detail) return
    const result = await pageWrites.publishUpdate(
      async () =>
        !(detail.document.js ?? '').trim() ||
        (await confirmDialog(
          '发布包含脚本的页面？',
          '页面脚本会在访客浏览器中执行。请确认你信任这些内容。',
        )),
    )
    if (result) Toast.success('页面已发布')
  } catch (e) {
    Toast.error(errorMessage(e))
  }
}
const mutating = computed(
  () =>
    exportMutation.isPending.value || removeMutation.isPending.value || pageWrites.pending.value,
)
function remove(item: PageSummary) {
  Dialog.warning({
    title: `删除「${item.title}」？`,
    description: '页面会立即下线，所有历史版本和对话也会删除，此操作无法撤销。',
    confirmType: 'danger',
    confirmText: '删除',
    cancelText: '取消',
    onConfirm: () => removeMutation.mutate(item),
  })
}
async function confirmUnpublish(item: PageSummary) {
  pageWrites.reset(item)
  try {
    if (
      await pageWrites.unpublish(() => confirmDialog('取消发布此页面？', '访客将无法访问此页面。'))
    ) {
      Toast.success('已取消发布')
    }
  } catch (e) {
    Toast.error(errorMessage(e))
  }
}
const {
  detail: settingsDetail,
  saving: settingsSaving,
  open: openSettings,
  close: closeSettings,
  apply: applySettings,
  unpublish: unpublishFromSettings,
} = usePageSettingsEditor({ confirm: confirmDialog })
</script>
<template>
  <VPageHeader title="页面工坊">
    <template #icon><IconFileAi /></template>
    <template #actions>
      <VButton type="secondary" @click="router.push({ name: 'AiPageBuilderNew' })">
        <template #icon><IconAddCircle /></template>
        新建页面
      </VButton>
    </template>
  </VPageHeader>
  <div class="m-2 md:m-4">
    <div v-if="showToolbar" class="mb-6 flex flex-wrap items-center justify-between gap-3">
      <div class="flex w-full flex-1 items-center sm:w-auto">
        <SearchInput v-model="keyword" placeholder="搜索标题或路径" />
      </div>
      <div class="flex flex-wrap items-center gap-6">
        <FilterDropdown v-model="status" label="状态" :items="statusItems" />
        <FilterDropdown v-model="sort" label="排序" :items="sortItems" />
      </div>
    </div>
    <VLoading v-if="listQuery.isLoading.value" />
    <VAlert v-else-if="listQuery.isError.value" type="error" title="页面列表加载失败">
      <template #actions><VButton @click="listQuery.refetch()">重试</VButton> </template>
    </VAlert>
    <VEmpty
      v-else-if="!items.length && (keyword || status)"
      title="没有找到匹配的页面"
      message="试试其它标题、路径或筛选条件。"
    />
    <CreatePagePanel v-else-if="!items.length" class="py-10" />
    <div
      v-else
      class="grid grid-cols-[minmax(0,1fr)] gap-5 sm:grid-cols-[repeat(auto-fill,minmax(280px,1fr))]"
    >
      <PageCard
        v-for="item in items"
        :key="item.name"
        :item="item"
        :disabled="mutating"
        @open="openEditor(item.name)"
        @visit="open"
        @settings="openSettings"
        @update="publishUpdate"
        @unpublish="confirmUnpublish"
        @clone="cloneTarget = $event"
        @export="exportMutation.mutate($event)"
        @remove="remove"
      />
    </div>
    <div v-if="total" class="mt-6">
      <VPagination
        :page="page"
        :size="size"
        :total="total"
        class="bg-transparent"
        :size-options="[10, 20, 50]"
        @update:page="page = $event"
        @update:size="((size = $event), (page = 1))"
      />
    </div>
  </div>
  <ClonePageModal
    v-if="cloneTarget"
    :page="cloneTarget"
    @close="cloneTarget = undefined"
    @created="cloned"
  />
  <PageSettings
    v-if="settingsDetail"
    :key="settingsDetail.page.name"
    :page="settingsDetail.page"
    :disabled="settingsSaving"
    :published="!!settingsDetail.page.publishedRevision"
    @close="closeSettings"
    @apply="applySettings"
    @unpublish="unpublishFromSettings"
  />
</template>
