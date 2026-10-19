<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
import { aiPageBuilderApi, getPublicSiteUrl, pagePreviewUrl } from '../api/clients'
import { errorMessage } from '../utils/error'
import { pageKeys, queryClient } from '../lib/query-client'
import type { PageDetail, PageDocument, PageSummary } from '../api/generated'
import ClonePageModal from '../components/ClonePageModal.vue'
import PagePreviewCard from '../components/PagePreviewCard.vue'
import PageSettings from '../components/PageSettings.vue'
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
const status = useRouteQuery<'published' | 'unpublished' | undefined>('status')
const sort = useRouteQuery<'created' | undefined>('sort')
watch([status, sort], () => {
  page.value = 1
})
const statusItems = [
  { label: '全部', value: undefined },
  { label: '已发布', value: 'published' },
  { label: '未发布', value: 'unpublished' },
]
const sortItems = [
  { label: '默认（最近更新）', value: undefined },
  { label: '最近创建', value: 'created' },
]
const listParams = computed(() => ({
  page: page.value,
  size: size.value,
  keyword: keyword.value,
  status: status.value,
  sort: sort.value,
}))
const listQuery = useQuery({
  queryKey: computed(() => pageKeys.list(listParams.value)),
  queryFn: async () => (await aiPageBuilderApi.listPages(listParams.value)).data,
  keepPreviousData: true,
  queryClient,
})
const items = computed(() => listQuery.data.value?.items || [])
const total = computed(() => listQuery.data.value?.total || 0)
// The empty workspace shows the create panel instead; keep the toolbar only
// when there is something to search or filter, or while loading/erroring.
const showToolbar = computed(
  () =>
    !!items.value.length ||
    !!keyword.value ||
    !!status.value ||
    listQuery.isLoading.value ||
    listQuery.isError.value,
)
const cloneTarget = ref<PageSummary>()

function openEditor(name: string) {
  void router.push({ name: 'AiPageBuilderEditor', params: { name } })
}
function cloned(name: string) {
  cloneTarget.value = undefined
  openEditor(name)
}
// Published pages go straight to the public URL; drafts fall back to the theme preview.
// Resolve the site URL up front so opening a page stays inside the click's user gesture.
const siteBaseUrl = ref('')
onMounted(() => {
  void getPublicSiteUrl()
    .then((url) => {
      siteBaseUrl.value = url
    })
    .catch(() => {
      // Keep the Console origin as a best-effort fallback.
    })
})
function open(item: PageSummary) {
  if (!item.publishedRevision) {
    window.open(pagePreviewUrl(item.name), '_blank', 'noopener')
    return
  }
  const base = (siteBaseUrl.value || window.location.origin).replace(/\/$/, '')
  window.open(`${base}/${item.slug || item.name}`, '_blank', 'noopener')
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
function confirm(title: string, description: string): Promise<boolean> {
  return new Promise((resolve) =>
    Dialog.warning({
      title,
      description,
      confirmText: '继续',
      cancelText: '取消',
      onConfirm: () => resolve(true),
      onCancel: () => resolve(false),
    }),
  )
}
async function publishUpdate(item: PageSummary) {
  try {
    const detail = (await aiPageBuilderApi.getPage({ name: item.name })).data
    if (
      (detail.document.js ?? '').trim() &&
      !(await confirm(
        '发布包含脚本的页面？',
        '页面脚本会在访客浏览器中执行。请确认你信任这些内容。',
      ))
    )
      return
    // Publish the version we just read so an updated list entry does not cause a 409.
    publishMutation.mutate({ ...item, version: detail.page.version })
  } catch (e) {
    Toast.error(errorMessage(e))
  }
}
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
// Settings modal edits the saved page directly: slug/layout apply immediately,
// title/description are persisted as a saved draft on 应用.
const settingsDetail = ref<PageDetail>()
const settingsSaving = ref(false)
// Rapid clicks on different cards race their detail fetches; only the latest
// request may open or replace the modal.
let settingsGeneration = 0
// Each modal session gets an id: a mutation started in one session must never
// write its result into a later session, not even for the same page.
let settingsSession = 0
async function openSettings(item: PageSummary) {
  const generation = ++settingsGeneration
  try {
    // Always refetch: another writer may have bumped the optimistic-lock version.
    const detail = await queryClient.fetchQuery({
      queryKey: pageKeys.detail(item.name),
      queryFn: async () => (await aiPageBuilderApi.getPage({ name: item.name })).data,
      staleTime: 0,
    })
    if (generation === settingsGeneration) {
      settingsSession = generation
      settingsDetail.value = detail
    }
  } catch (e) {
    if (generation === settingsGeneration) Toast.error(errorMessage(e))
  }
}
function cacheSettingsDetail(result: PageDetail) {
  const key = pageKeys.detail(result.page.name)
  const cached = queryClient.getQueryData<PageDetail>(key)
  // A late response must not roll the cache back to an older version.
  if (cached && cached.page.version > result.page.version) return
  queryClient.setQueryData(key, result)
  void queryClient.invalidateQueries({ queryKey: pageKeys.all })
}
// Late results still update the cache, but only their own session's modal.
function applySettingsResult(result: PageDetail, session: number) {
  cacheSettingsDetail(result)
  if (session === settingsSession) showSettingsResult(result)
}
// Within a session the modal's version stays monotonic: a late save response
// must not overwrite a newer refresh (or vice versa).
function showSettingsResult(result: PageDetail) {
  if (
    settingsDetail.value?.page.name === result.page.name &&
    settingsDetail.value.page.version <= result.page.version
  )
    settingsDetail.value = result
}
const isConflict = (e: unknown) =>
  (e as { response?: { status?: number } })?.response?.status === 409
// After a version conflict the snapshot is stale; refetch so an in-modal retry
// uses the current version. The keyed modal is not remounted, so the form
// buffer survives.
async function refreshSettingsDetail(name: string, session: number) {
  try {
    const fresh = (await aiPageBuilderApi.getPage({ name })).data
    cacheSettingsDetail(fresh)
    if (session === settingsSession) showSettingsResult(fresh)
  } catch {
    // Keep the stale snapshot; reopening the modal always refetches.
  }
}
async function saveSettingsSlug(slug: string) {
  const current = settingsDetail.value
  if (!current) return undefined
  if (slug === current.page.slug) return current
  const session = settingsSession
  try {
    const result = (
      await aiPageBuilderApi.updatePageSlug({
        name: current.page.name,
        updatePageSlugRequest: { version: current.page.version, slug },
      })
    ).data
    applySettingsResult(result, session)
    return result
  } catch (e) {
    if (isConflict(e)) void refreshSettingsDetail(current.page.name, session)
    throw e
  }
}
async function saveSettingsLayout(themeLayout: boolean) {
  const current = settingsDetail.value
  if (!current) return undefined
  if (themeLayout === current.page.themeLayout) return current
  const session = settingsSession
  try {
    const result = (
      await aiPageBuilderApi.updatePageLayout({
        name: current.page.name,
        updatePageLayoutRequest: { version: current.page.version, themeLayout },
      })
    ).data
    applySettingsResult(result, session)
    return result
  } catch (e) {
    if (isConflict(e)) void refreshSettingsDetail(current.page.name, session)
    throw e
  }
}
async function applySettings(patch: Partial<Pick<PageDocument, 'title' | 'description'>>) {
  const current = settingsDetail.value
  if (!current) return
  if (!Object.keys(patch).length) {
    settingsDetail.value = undefined
    return
  }
  const session = settingsSession
  settingsSaving.value = true
  try {
    const result = (
      await aiPageBuilderApi.savePage({
        name: current.page.name,
        savePageRequest: {
          version: current.page.version,
          document: { ...current.document, ...patch },
          source: 'manual',
        },
      })
    ).data
    cacheSettingsDetail(result)
    if (session === settingsSession && settingsDetail.value?.page.name === current.page.name)
      settingsDetail.value = undefined
    Toast.success('设置已保存')
  } catch (e) {
    Toast.error(errorMessage(e))
    if (isConflict(e)) void refreshSettingsDetail(current.page.name, session)
  } finally {
    settingsSaving.value = false
  }
}
function unpublishFromSettings() {
  const name = settingsDetail.value?.page.name
  if (!name) return
  const session = settingsSession
  Dialog.warning({
    title: '取消发布此页面？',
    description: '访客将无法访问此页面。',
    confirmText: '确认',
    cancelText: '取消',
    onConfirm: async () => {
      // Re-read: slug/layout saves may have bumped the version while the
      // confirmation dialog was open.
      const current = settingsDetail.value
      if (session !== settingsSession || current?.page.name !== name) return
      settingsSaving.value = true
      try {
        const result = (
          await aiPageBuilderApi.unpublishPage({
            name,
            mutationRequest: { version: current.page.version },
          })
        ).data
        applySettingsResult(result, session)
        Toast.success('已取消发布')
      } catch (e) {
        Toast.error(errorMessage(e))
        if (isConflict(e)) void refreshSettingsDetail(name, session)
      } finally {
        settingsSaving.value = false
      }
    },
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
              {{ item.publishedRevision ? '访问页面 ↗' : '预览 ↗' }}
            </VButton>
            <VDropdown>
              <VButton size="sm" :disabled="mutating" :aria-label="`${item.title}的操作`">
                <IconMore />
              </VButton>
              <template #popper>
                <VDropdownItem v-close-popper :disabled="mutating" @click="openSettings(item)">
                  设置
                </VDropdownItem>
                <PublishPagePopper
                  v-if="!item.publishedRevision"
                  :name="item.name"
                  :title="item.title"
                  :slug="item.slug"
                  :version="item.version"
                  :disabled="mutating"
                  placement="left-start"
                  @published="published"
                  @updated="published"
                >
                  <VDropdownItem :disabled="mutating">发布</VDropdownItem>
                </PublishPagePopper>
                <VDropdownItem
                  v-else
                  v-close-popper
                  :disabled="mutating"
                  @click="publishUpdate(item)"
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
                <VDropdownItem v-close-popper :disabled="mutating" @click="cloneTarget = item">
                  克隆页面
                </VDropdownItem>
                <VDropdownItem
                  v-close-popper
                  :disabled="mutating"
                  @click="exportMutation.mutate(item)"
                  >导出</VDropdownItem
                >
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
  <PageSettings
    v-if="settingsDetail"
    :key="settingsDetail.page.name"
    :document="settingsDetail.document"
    :slug="settingsDetail.page.slug"
    :theme-layout="settingsDetail.page.themeLayout"
    :save-slug="saveSettingsSlug"
    :save-layout="saveSettingsLayout"
    :disabled="settingsSaving"
    :published="!!settingsDetail.page.publishedRevision"
    @close="settingsDetail = undefined"
    @apply="applySettings"
    @unpublish="unpublishFromSettings"
  />
</template>
