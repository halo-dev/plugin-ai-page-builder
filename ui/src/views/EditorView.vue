<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  shallowRef,
  useTemplateRef,
  watch,
} from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Dialog, Toast, VLoading } from '@halo-dev/components'
import IconArrowLeft from '~icons/mingcute/arrow-left-line'
import IconUndo from '~icons/mingcute/back-line'
import IconRedo from '~icons/mingcute/forward-line'
import IconSettings from '~icons/mingcute/settings-3-line'
import IconHistory from '~icons/mingcute/history-line'
import IconExternal from '~icons/mingcute/external-link-line'
import IconSave from '~icons/mingcute/save-2-line'
import IconRocket from '~icons/mingcute/rocket-line'
import IconClose from '~icons/mingcute/close-line'
import IconRefresh from '~icons/mingcute/refresh-2-line'
import IconEdit from '~icons/mingcute/edit-2-line'
import IconCode from '~icons/mingcute/code-line'
import IconPlay from '~icons/mingcute/play-line'
import IconDesktop from '~icons/mingcute/computer-line'
import IconMobile from '~icons/mingcute/cellphone-line'
import IconTablet from '~icons/mingcute/pad-line'
import type { PreviewDevice } from '../editor/preview-canvas'
import IconSun from '~icons/mingcute/sun-line'
import IconMoon from '~icons/mingcute/moon-line'
import IconContrast from '~icons/mingcute/brightness-line'
import IconChat from '~icons/mingcute/chat-3-line'
import IconCanvas from '~icons/mingcute/layout-grid-line'
import IconSpinner from '~icons/mingcute/loading-line'
import { pagePreviewUrl, aiPageBuilderApi, aiPageBuilderChatApi } from '../api/clients'
import type { ThemeStyleContext } from '../api/generated'
import { errorMessage } from '../utils/error'
import { pageKeys, queryClient } from '../lib/query-client'
import AiPanel from '../components/AiPanel.vue'
import HistoryPanel from '../components/HistoryPanel.vue'
import PageSettings from '../components/PageSettings.vue'
import PublishPagePopper from '../components/PublishPagePopper.vue'
import SourceEditor from '../components/SourceEditor.vue'
import ApbSegmented from '../components/ui/ApbSegmented.vue'
import { useWorkingDocument } from '../composables/useWorkingDocument'
import { usePageAnnotations } from '../composables/usePageAnnotations'
import type { AnnotationTarget, PreparedPage } from '../editor/annotations'
import type { StreamingPreview } from '../composables/useStreamingPreview'
import RuntimePreview from '../components/RuntimePreview.vue'
import StreamingCanvas from '../components/chat/StreamingCanvas.vue'

const VisualEditor = defineAsyncComponent(() => import('../components/VisualEditor.vue'))
const route = useRoute()
const router = useRouter()
// One-shot handoff from the create-page dialog: start the assistant right away.
// Passed via history.state so it never pollutes the URL; stripped once read so a
// refresh cannot re-trigger it.
const pendingAiPrompt = shallowRef(String(history.state?.aiPrompt || ''))
const pendingAiModel = shallowRef(String(history.state?.aiModel || ''))
if (pendingAiPrompt.value || pendingAiModel.value) {
  const state = { ...history.state }
  delete state.aiPrompt
  delete state.aiModel
  history.replaceState(state, '')
}
const {
  detail,
  document: working,
  busy,
  agentRunning,
  preparing,
  locked,
  dirty,
  error,
  validationError,
  validationState,
  valid,
  canUndo,
  canRedo,
  adopt,
  change,
  patch,
  move,
  save,
  updateSlug,
  updateLayout,
  prepareAgent,
} = useWorkingDocument()
const {
  annotations,
  add: addAnnotation,
  remove: removeAnnotation,
  snapshot: annotationSnapshot,
  clear: clearAnnotations,
} = usePageAnnotations(working)
const streamPreview = shallowRef<StreamingPreview>()
async function prepareChat(): Promise<number | PreparedPage> {
  return prepareAgent(async (page) => ({
    version: page.page.version,
    ...(await annotationSnapshot(page.document)),
  }))
}
async function annotate(target: AnnotationTarget, comment: string) {
  if (locked.value) return
  try {
    await addAnnotation(target, comment)
    mobilePanel.value = 'chat'
  } catch (e) {
    Toast.info(errorMessage(e))
  }
}
const themeContext = shallowRef<ThemeStyleContext>()
const previewMode = shallowRef<'default' | 'light' | 'dark'>('default')
const rootStates = computed(() =>
  themeContext.value?.colorMode?.strategy === 'explicit'
    ? themeContext.value.colorMode.rootStates
    : [],
)
const rootState = computed(() => rootStates.value.find((state) => state.mode === previewMode.value))
const themeModes = [
  { value: 'default', icon: IconContrast, title: '主题默认模式' },
  { value: 'light', icon: IconSun, title: '模拟亮色模式' },
  { value: 'dark', icon: IconMoon, title: '模拟暗色模式' },
] as const
function cyclePreviewMode() {
  const order = ['default', 'light', 'dark'] as const
  const next = order[(order.indexOf(previewMode.value) + 1) % order.length]!
  previewMode.value = next
}
const previewModeIcon = computed(
  () => themeModes.find((mode) => mode.value === previewMode.value) || themeModes[0],
)
queryClient
  .fetchQuery({
    queryKey: pageKeys.themeContext,
    queryFn: async () => (await aiPageBuilderChatApi.getThemeContext()).data,
    staleTime: 60_000,
    retry: false,
  })
  .then((context) => {
    themeContext.value = context
  })
  .catch(() => {
    // Theme context is optional; manual editing and validation stay independent.
  })
const loading = shallowRef(true)
const settingsOpen = shallowRef(false)
const historyOpen = shallowRef(false)
// First publish also saves title/path; lock editing until all writes finish.
const publishPending = shallowRef(false)
function onPublishPending(pending: boolean) {
  publishPending.value = pending
  busy.value = pending
}
const view = shallowRef<'visual' | 'source' | 'runtime'>('visual')
const runtimePreview = useTemplateRef<{ refresh: () => void }>('runtimePreview')
const viewOptions = [
  { value: 'visual', label: '编辑', icon: IconEdit },
  { value: 'source', label: '源码', icon: IconCode },
  { value: 'runtime', label: '运行', icon: IconPlay, title: '运行已保存的草稿，脚本在沙箱中执行' },
] as const
const publicUrl = computed(() =>
  detail.value?.page.publishedRevision && detail.value.page.slug
    ? `${window.location.origin}/${detail.value.page.slug}`
    : '',
)
function openPublicPage() {
  if (publicUrl.value) window.open(publicUrl.value, '_blank', 'noopener')
}
const mobilePanel = shallowRef<'chat' | 'canvas'>('canvas')
const mobilePanelOptions = [
  { value: 'chat', label: '对话', icon: IconChat },
  { value: 'canvas', label: '画布', icon: IconCanvas },
] as const
const device = shallowRef<PreviewDevice>('desktop')
const deviceOptions = [
  { value: 'desktop', icon: IconDesktop, title: '桌面 1280px' },
  { value: 'tablet', icon: IconTablet, title: '平板 768px' },
  { value: 'mobile', icon: IconMobile, title: '手机 390px' },
] as const
let loadId = 0
async function load() {
  const name = String(route.params.name)
  const id = ++loadId
  loading.value = true
  error.value = ''
  try {
    const result = await queryClient.fetchQuery({
      queryKey: pageKeys.detail(name),
      queryFn: async () => (await aiPageBuilderApi.getPage({ name })).data,
      staleTime: 30_000,
    })
    if (id === loadId) {
      adopt(result, true)
      streamPreview.value = undefined
      view.value = 'visual'
      settingsOpen.value = false
      historyOpen.value = false
    }
  } catch (e) {
    if (id === loadId) error.value = errorMessage(e)
  } finally {
    if (id === loadId) loading.value = false
  }
}
watch(() => route.params.name, load, { immediate: true })
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
async function leave() {
  if (locked.value) {
    Toast.info('请等待当前操作完成，或先停止助手。')
    return false
  }
  return !dirty.value || confirm('离开工作台？', '当前修改尚未保存，离开将丢失这些修改。')
}
onBeforeRouteLeave(leave)
onBeforeRouteUpdate((to, from) => to.params.name === from.params.name || leave())
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value || locked.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
function keydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey)) return
  if (event.key.toLowerCase() === 's') {
    event.preventDefault()
    void saveDraft()
  }
  const target = event.target instanceof HTMLElement ? event.target : undefined
  const nativeInput = target?.closest('input, textarea, [contenteditable="true"]')
  if (
    event.key.toLowerCase() === 'z' &&
    !settingsOpen.value &&
    (!nativeInput || target?.closest('.apb-code'))
  ) {
    event.preventDefault()
    event.stopPropagation()
    move(event.shiftKey ? 'redo' : 'undo')
  }
}
window.addEventListener('beforeunload', beforeUnload)
window.addEventListener('keydown', keydown, true)
onBeforeUnmount(() => {
  loadId++
  window.removeEventListener('beforeunload', beforeUnload)
  window.removeEventListener('keydown', keydown, true)
})
async function reload() {
  if (locked.value) return
  if (
    dirty.value &&
    !(await confirm('载入最新版本？', '当前未保存修改会被替换。取消可继续保留本地修改。'))
  )
    return
  const name = String(route.params.name)
  await queryClient.invalidateQueries({ queryKey: pageKeys.detail(name) })
  await load()
}
async function saveDraft() {
  try {
    if (await save()) Toast.success('草稿已保存')
  } catch (e) {
    error.value = errorMessage(e)
  }
}
function preview() {
  if (!detail.value) return
  if (dirty.value) Toast.info('主题预览显示上一次保存的草稿。')
  window.open(pagePreviewUrl(detail.value.page.name), '_blank', 'noopener')
}
async function publish(unpublish = false) {
  if (!detail.value || locked.value || (!unpublish && (dirty.value || !valid.value))) return
  // First publish goes through the popper for the slug; this path is 更新发布/取消发布.
  if (!unpublish && !detail.value.page.publishedRevision) return
  if (
    !unpublish &&
    detail.value.document.js &&
    !(await confirm('发布包含脚本的页面？', '页面脚本会在访客浏览器中执行。请确认你信任这些内容。'))
  )
    return
  if (unpublish && !(await confirm('取消发布？', '访客将无法访问此页面。'))) return
  busy.value = true
  error.value = ''
  try {
    const request = {
      name: detail.value.page.name,
      mutationRequest: { version: detail.value.page.version },
    }
    const result = (
      await (unpublish
        ? aiPageBuilderApi.unpublishPage(request)
        : aiPageBuilderApi.publishPage(request))
    ).data
    detail.value = result
    queryClient.setQueryData(pageKeys.detail(result.page.name), result)
    Toast.success(unpublish ? '已取消发布' : '页面已发布')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
function published(result: NonNullable<typeof detail.value>) {
  adopt(result)
  queryClient.setQueryData(pageKeys.detail(result.page.name), result)
}
async function restore(revision: string) {
  if (!detail.value || locked.value || !valid.value) return
  if (
    !(await confirm(
      '恢复选中版本？',
      '当前修改会先保存，选中版本将复制为新草稿。线上发布版本不会改变。',
    ))
  )
    return
  try {
    const preserved = await save()
    if (!preserved) return
    busy.value = true
    const result = (
      await aiPageBuilderApi.restorePageRevision({
        name: preserved.page.name,
        restoreRequest: { version: preserved.page.version, revision },
      })
    ).data
    adopt(result)
    queryClient.setQueryData(pageKeys.detail(result.page.name), result)
    historyOpen.value = false
    Toast.success('已恢复为新草稿')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
function applySettings(value: Parameters<typeof patch>[0]) {
  patch(value)
  settingsOpen.value = false
}
</script>

<template>
  <!-- position:fixed takes the workbench out of document flow: it is always exactly the
       viewport, independent of the console shell (html/body/#app only have min-height:100vh,
       so any in-flow height scheme — 100dvh, flex-1, height:100% — lets long chat content
       stretch the shell and makes the document scrollable). overflow:clip (not hidden) also
       forbids wheel-chained/programmatic scrolling of this container -->
  <main
    class="flex w-full flex-col overflow-hidden bg-gray-50 text-gray-900"
    style="position: fixed; inset: 0; overflow: clip; overscroll-behavior: contain"
  >
    <header
      class="flex shrink-0 flex-wrap items-center gap-1.5 border-b border-gray-200 bg-white px-3 py-1.5 md:h-12 md:flex-nowrap md:py-0"
    >
      <button
        type="button"
        class="apb-icon-btn"
        aria-label="返回页面列表"
        title="返回页面列表"
        @click="router.push({ name: 'AiPageBuilderPages' })"
      >
        <IconArrowLeft class="text-base" />
      </button>
      <div class="min-w-0">
        <h1 class="max-w-56 truncate text-[13px] font-semibold leading-4">
          {{ working?.title || '页面工作台' }}
        </h1>
      </div>
      <span
        v-if="detail"
        class="apb-chip ml-1 shrink-0"
        :class="dirty ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'"
      >
        <span class="h-1.5 w-1.5 rounded-full" :class="dirty ? 'bg-amber-500' : 'bg-emerald-500'" />
        {{ busy ? '保存中…' : dirty ? '未保存' : '已保存' }}
      </span>
      <div
        v-if="detail && !loading"
        class="ml-auto flex max-w-full items-center gap-1 overflow-x-auto max-md:w-full"
      >
        <button
          type="button"
          class="apb-icon-btn"
          :disabled="locked || !canUndo"
          title="撤销（⌘/Ctrl Z）"
          aria-label="撤销"
          @click="move('undo')"
        >
          <IconUndo class="text-base" />
        </button>
        <button
          type="button"
          class="apb-icon-btn"
          :disabled="locked || !canRedo"
          title="重做（⌘/Ctrl Shift Z）"
          aria-label="重做"
          @click="move('redo')"
        >
          <IconRedo class="text-base" />
        </button>
        <span class="mx-1 h-4 w-px bg-gray-200" />
        <button
          type="button"
          class="apb-icon-btn"
          :disabled="locked"
          title="页面设置"
          aria-label="页面设置"
          @click="settingsOpen = true"
        >
          <IconSettings class="text-base" />
        </button>
        <button
          type="button"
          class="apb-icon-btn"
          :disabled="locked"
          title="历史版本"
          aria-label="历史版本"
          @click="historyOpen = true"
        >
          <IconHistory class="text-base" />
        </button>
        <button
          type="button"
          class="apb-icon-btn"
          :disabled="locked"
          title="主题预览（新窗口）"
          aria-label="主题预览"
          @click="preview"
        >
          <IconExternal class="text-base" />
        </button>
        <span class="mx-1 h-4 w-px bg-gray-200" />
        <button
          type="button"
          class="apb-btn-outline"
          :disabled="locked || !dirty || !valid"
          @click="saveDraft"
        >
          <IconSpinner v-if="busy" class="animate-spin text-sm" />
          <IconSave v-else class="text-sm" />
          保存
        </button>
        <PublishPagePopper
          v-if="!detail.page.publishedRevision"
          :name="detail.page.name"
          :title="detail.page.title"
          :slug="detail.page.slug"
          :version="detail.page.version"
          :disabled="locked || dirty || !valid"
          :scripted="!!working?.js"
          @published="published"
          @updated="published"
          @pending="onPublishPending"
        >
          <button type="button" class="apb-btn-primary" :disabled="locked || dirty || !valid">
            <IconRocket class="text-sm" />
            发布
          </button>
        </PublishPagePopper>
        <button
          v-else
          type="button"
          class="apb-btn-primary"
          :disabled="locked || dirty || !valid || busy"
          @click="publish()"
        >
          <IconRocket class="text-sm" />
          更新发布
        </button>
      </div>
    </header>
    <div
      v-if="error"
      class="flex shrink-0 flex-wrap items-center gap-2 border-b border-red-100 bg-red-50 px-4 py-2 text-xs text-red-700"
      role="alert"
    >
      <span class="min-w-0 flex-1">{{ error }}</span>
      <button
        type="button"
        class="apb-btn-outline h-7 border-red-200 bg-white text-xs"
        :disabled="locked"
        @click="reload"
      >
        <IconRefresh class="text-sm" />载入最新版本
      </button>
      <button
        type="button"
        class="apb-icon-btn h-7 w-7 text-red-400 hover:bg-red-100 hover:text-red-700"
        aria-label="关闭"
        @click="error = ''"
      >
        <IconClose class="text-sm" />
      </button>
    </div>
    <VLoading v-if="loading" />
    <template v-else-if="working && detail">
      <div class="border-b border-gray-200 bg-white px-3 py-2 md:hidden">
        <ApbSegmented v-model="mobilePanel" :options="mobilePanelOptions" aria-label="工作台区域" />
      </div>
      <div class="grid min-h-0 flex-1 grid-rows-[minmax(0,1fr)] md:grid-cols-[380px_minmax(0,1fr)]">
        <AiPanel
          :key="detail.page.name"
          class="border-r border-gray-200"
          :class="mobilePanel === 'chat' ? 'flex' : 'hidden md:flex'"
          :page-name="detail.page.name"
          :disabled="busy || publishPending || preparing || !valid || settingsOpen || historyOpen"
          :dirty="dirty"
          :page-version="detail.page.version"
          :prepare="prepareChat"
          :document="working"
          :annotations="annotations"
          :initial-prompt="pendingAiPrompt"
          :initial-model="pendingAiModel"
          @remove-annotation="removeAnnotation"
          @completed="clearAnnotations"
          @preview="streamPreview = $event"
          @running="agentRunning = $event"
          @committed="adopt($event)"
        />
        <section
          class="min-h-0 min-w-0 flex-col"
          :class="mobilePanel === 'canvas' ? 'flex' : 'hidden md:flex'"
          aria-label="页面编辑区"
        >
          <div
            class="flex shrink-0 flex-wrap items-center gap-2 border-b border-gray-200 bg-white px-3 py-1.5 md:h-11 md:flex-nowrap md:py-0"
          >
            <ApbSegmented v-model="view" :options="viewOptions" :disabled="locked" />
            <span
              class="apb-chip"
              :class="
                detail.page.publishedRevision
                  ? detail.page.hasUnpublishedChanges
                    ? 'bg-amber-50 text-amber-700'
                    : 'bg-blue-50 text-blue-700'
                  : 'bg-gray-100 text-gray-500'
              "
            >
              {{
                detail.page.publishedRevision
                  ? detail.page.hasUnpublishedChanges
                    ? '有未发布修改'
                    : '已发布'
                  : '草稿'
              }}
            </span>
            <span
              v-if="working?.js"
              class="apb-chip bg-purple-50 text-purple-700"
              title="页面包含 JavaScript，发布后会在访客浏览器中执行"
              >含脚本</span
            >
            <span
              v-if="view === 'runtime' && dirty"
              class="apb-chip bg-amber-50 text-amber-700"
              title="运行预览只读取已保存的草稿，请先保存再查看最新修改"
              >运行的是已保存草稿</span
            >
            <button
              v-if="publicUrl"
              type="button"
              class="apb-chip max-w-44 bg-blue-50 text-blue-700 hover:bg-blue-100"
              :title="`打开公开页面：${publicUrl}`"
              @click="openPublicPage"
            >
              <IconExternal class="shrink-0 text-xs" />
              <span class="truncate">/{{ detail.page.slug }}</span>
            </button>
            <div class="ml-auto flex items-center gap-2">
              <button
                v-if="view === 'runtime'"
                type="button"
                class="apb-icon-btn"
                title="重新运行"
                aria-label="重新运行"
                @click="runtimePreview?.refresh()"
              >
                <IconRefresh class="text-base" />
              </button>
              <button
                v-if="rootStates.length && view !== 'runtime'"
                type="button"
                class="apb-icon-btn"
                :title="previewModeIcon.title"
                :aria-label="previewModeIcon.title"
                @click="cyclePreviewMode"
              >
                <component :is="previewModeIcon.icon" class="text-base" />
              </button>
              <ApbSegmented
                v-if="view !== 'source' || streamPreview"
                v-model="device"
                :options="deviceOptions"
              />
            </div>
          </div>
          <div class="relative min-h-0 flex-1">
            <!-- overlay: the validation notice must never push the canvas up and down -->
            <div
              v-if="!valid"
              class="absolute inset-x-0 top-0 z-10 border-b px-4 py-2 text-xs leading-5"
              :class="
                validationState === 'invalid'
                  ? 'border-red-100 bg-red-50 text-red-700'
                  : 'border-amber-100 bg-amber-50 text-amber-700'
              "
              role="status"
            >
              {{ validationState === 'pending' ? '正在校验源码，画布暂未同步…' : validationError }}
              <span v-if="validationState === 'invalid'"> 画布保留上次有效内容，请修正源码。 </span>
            </div>
            <div class="h-full overflow-auto [&>*]:min-h-full">
              <StreamingCanvas
                v-if="streamPreview && view !== 'runtime'"
                :preview="streamPreview"
                :device="device"
                :preview-css="themeContext?.previewCss || ''"
                :root-state="rootState"
              />
              <RuntimePreview
                v-if="view === 'runtime'"
                ref="runtimePreview"
                :src="pagePreviewUrl(detail.page.name)"
                :document="detail.document"
                :device="device"
              />
              <VisualEditor
                v-show="view === 'visual' && !streamPreview"
                :name="detail.page.name"
                :document="working"
                :validated="valid"
                :disabled="locked || !valid"
                :device="device"
                :root-state="rootState"
                :root-states="rootStates"
                :annotation-count="annotations.length"
                @annotate="annotate"
                @update:document="change"
              /><SourceEditor
                v-if="view === 'source' && !streamPreview"
                :document="working"
                :disabled="locked"
                @change="patch"
              />
            </div>
            <p
              v-if="view === 'visual' && !streamPreview && working?.js"
              class="pointer-events-none absolute bottom-6 left-6 z-[6] rounded-md border border-gray-200 bg-white/95 px-2.5 py-1.5 text-xs leading-5 text-gray-500 shadow-sm"
            >
              编辑画布不执行页面脚本，交互效果请在「运行」中查看
            </p>
          </div>
        </section>
      </div>
      <PageSettings
        v-if="settingsOpen"
        :document="working"
        :slug="detail.page.slug"
        :theme-layout="detail.page.themeLayout"
        :save-slug="updateSlug"
        :save-layout="updateLayout"
        :disabled="locked"
        :published="!!detail.page.publishedRevision"
        @close="settingsOpen = false"
        @apply="applySettings"
        @unpublish="publish(true)"
      />
      <HistoryPanel
        v-if="historyOpen"
        :name="detail.page.name"
        :current="working"
        :busy="locked || !valid"
        :draft-revision="detail.page.draftRevision"
        :published-revision="detail.page.publishedRevision"
        @close="historyOpen = false"
        @restore="restore"
      />
    </template>
  </main>
</template>
