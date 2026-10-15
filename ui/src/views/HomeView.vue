<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useRouteQuery } from '@vueuse/router'
import { useMutation, useQuery } from '@tanstack/vue-query'
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VDropdown,
  VDropdownItem,
  VEmpty,
  VLoading,
  VPageHeader,
  VPagination,
  VStatusDot,
} from '@halo-dev/components'
import IconAddCircle from '~icons/mingcute/add-circle-line'
import IconGrid from '~icons/mingcute/layout-grid-line'
import IconMore from '~icons/mingcute/more-2-line'
import { pagePreviewUrl, aiPageBuilderApi } from '../api/clients'
import { errorMessage } from '../utils/error'
import { pageKeys, queryClient } from '../lib/query-client'
import type { PageSummary } from '../api/generated'
import ClonePageModal from '../components/ClonePageModal.vue'
import PagePreviewCard from '../components/PagePreviewCard.vue'
import PublishPagePopper from '../components/PublishPagePopper.vue'
import CreatePagePanel from '../components/create/CreatePagePanel.vue'

const router = useRouter()
const page = useRouteQuery<number>('page', 1, {
  transform: (value) => Math.max(1, Number(value) || 1),
})
const size = useRouteQuery<number>('size', 20, {
  transform: (value) => Math.max(1, Number(value) || 1),
})
const keyword = useRouteQuery<string>('keyword', '')
const listParams = computed(() => ({
  page: page.value,
  size: size.value,
  keyword: keyword.value,
}))
const listQuery = useQuery({
  queryKey: computed(() => pageKeys.list(listParams.value)),
  queryFn: async () => (await aiPageBuilderApi.listPages(listParams.value)).data,
  keepPreviousData: true,
  queryClient,
})
const items = computed(() => listQuery.data.value?.items || [])
const total = computed(() => listQuery.data.value?.total || 0)
const cloneTarget = ref<PageSummary>()

function openEditor(name: string) {
  void router.push({ name: 'AiPageBuilderEditor', params: { name } })
}
function cloned(name: string) {
  cloneTarget.value = undefined
  openEditor(name)
}
// Published pages go straight to the public URL; drafts fall back to the theme preview.
function open(item: PageSummary) {
  const url = item.publishedRevision ? `/${item.slug || item.name}` : pagePreviewUrl(item.name)
  window.open(url, '_blank', 'noopener')
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
const publishMutation = useMutation({
  mutationFn: async (item: PageSummary) => {
    await aiPageBuilderApi.publishPage({
      name: item.name,
      mutationRequest: { version: item.version },
    })
  },
  onSuccess: () => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    Toast.success('页面已发布')
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
const unpublishMutation = useMutation({
  mutationFn: async (item: PageSummary) => {
    await aiPageBuilderApi.unpublishPage({
      name: item.name,
      mutationRequest: { version: item.version },
    })
  },
  onSuccess: () => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    Toast.success('已取消发布')
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
const mutating = computed(
  () =>
    exportMutation.isPending.value ||
    removeMutation.isPending.value ||
    publishMutation.isPending.value ||
    unpublishMutation.isPending.value,
)
function published() {
  void queryClient.invalidateQueries({ queryKey: pageKeys.all })
}
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
function confirmUnpublish(item: PageSummary) {
  Dialog.warning({
    title: '取消发布此页面？',
    description: '访客将无法访问此页面。',
    confirmText: '确认',
    cancelText: '取消',
    onConfirm: () => unpublishMutation.mutate(item),
  })
}
</script>
<template>
  <VPageHeader title="AI 页面构建">
    <template #icon><IconGrid /></template>
    <template #actions>
      <VButton type="secondary" @click="router.push({ name: 'AiPageBuilderNew' })">
        <template #icon><IconAddCircle /></template>
        新建页面
      </VButton>
    </template>
  </VPageHeader>
  <div class="m-2 md:m-4">
    <div class="mb-6">
      <SearchInput v-model="keyword" placeholder="搜索标题或路径" />
    </div>
    <VLoading v-if="listQuery.isLoading.value" />
    <VAlert v-else-if="listQuery.isError.value" type="error" title="页面列表加载失败">
      <template #actions><VButton @click="listQuery.refetch()">重试</VButton> </template>
    </VAlert>
    <VEmpty
      v-else-if="!items.length && keyword"
      title="没有找到匹配的页面"
      message="试试其它标题或路径。"
    />
    <CreatePagePanel v-else-if="!items.length" class="py-10" />
    <div
      v-else
      class="grid grid-cols-[minmax(0,1fr)] gap-5 sm:grid-cols-[repeat(auto-fill,minmax(280px,1fr))]"
    >
      <article
        v-for="item in items"
        :key="item.name"
        class="min-w-0 overflow-hidden rounded-lg border border-gray-200 bg-white transition-shadow hover:border-gray-300 hover:shadow-md"
      >
        <PagePreviewCard
          :name="item.name"
          :revision="item.draftRevision || ''"
          :title="item.title"
          @open="openEditor(item.name)"
        />
        <div class="p-4">
          <RouterLink
            :to="{ name: 'AiPageBuilderEditor', params: { name: item.name } }"
            class="block truncate text-sm font-semibold text-gray-800 hover:underline"
            >{{ item.title }}</RouterLink
          >
          <p class="mt-1 break-all text-xs text-gray-400">
            {{ item.slug ? '/' + item.slug : '未设置访问路径' }}
          </p>
          <div class="mt-3.5 flex items-center justify-between gap-2">
            <VStatusDot
              :state="
                item.publishedRevision
                  ? item.hasUnpublishedChanges
                    ? 'warning'
                    : 'success'
                  : 'default'
              "
              :text="
                item.publishedRevision
                  ? item.hasUnpublishedChanges
                    ? '有未发布修改'
                    : '已发布'
                  : '未发布'
              "
            />
            <span class="text-[11px] text-gray-400">
              {{ item.updatedAt ? new Date(item.updatedAt).toLocaleDateString() : '' }}
            </span>
          </div>
          <div class="mt-4 flex items-center gap-2 border-t border-gray-100 pt-3.5">
            <VButton size="sm" class="flex-1" @click="open(item)">
              {{ item.publishedRevision ? '访问页面 ↗' : '主题预览 ↗' }}
            </VButton>
            <VDropdown>
              <VButton size="sm" :disabled="mutating" :aria-label="`${item.title}的操作`">
                <IconMore />
              </VButton>
              <template #popper>
                <VDropdownItem v-close-popper :disabled="mutating" @click="cloneTarget = item">
                  克隆页面
                </VDropdownItem>
                <VDropdownItem
                  v-close-popper
                  :disabled="mutating"
                  @click="exportMutation.mutate(item)"
                  >导出草稿 ZIP</VDropdownItem
                >
                <PublishPagePopper
                  v-if="!item.publishedRevision"
                  :name="item.name"
                  :slug="item.slug"
                  :version="item.version"
                  placement="left-start"
                  @published="published"
                >
                  <VDropdownItem :disabled="mutating">发布</VDropdownItem>
                </PublishPagePopper>
                <VDropdownItem
                  v-else
                  v-close-popper
                  :disabled="mutating"
                  @click="publishMutation.mutate(item)"
                >
                  更新发布
                </VDropdownItem>
                <VDropdownItem
                  v-if="item.publishedRevision"
                  v-close-popper
                  :disabled="mutating"
                  @click="confirmUnpublish(item)"
                >
                  取消发布
                </VDropdownItem>
                <VDropdownItem
                  v-close-popper
                  type="danger"
                  :disabled="mutating"
                  @click="remove(item)"
                >
                  删除页面
                </VDropdownItem>
              </template>
            </VDropdown>
          </div>
        </div>
      </article>
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
</template>
