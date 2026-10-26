<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  onMounted,
  shallowRef,
  useTemplateRef,
  watch,
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Toast, VLoading } from '@halo-dev/components'
import IconClose from '~icons/mingcute/close-line'
import IconRefresh from '~icons/mingcute/refresh-2-line'
import IconChat from '~icons/mingcute/chat-3-line'
import IconCanvas from '~icons/mingcute/layout-grid-line'
import { pagePreviewUrl, aiPageBuilderApi } from '../api/clients'
import { errorMessage } from '../utils/error'
import { confirmDialog } from '../utils/confirm'
import { pageKeys, queryClient } from '../lib/query-client'
import AiPanel from '../components/AiPanel.vue'
import PageSettings from '../components/PageSettings.vue'
import SourceEditor from '../components/SourceEditor.vue'
import ApbSegmented from '../components/ui/ApbSegmented.vue'
import EditorHeader from '../components/editor/EditorHeader.vue'
import CanvasToolbar from '../components/editor/CanvasToolbar.vue'
import { useWorkingDocument } from '../composables/useWorkingDocument'
import { usePageAnnotations } from '../composables/usePageAnnotations'
import { useThemePreview } from '../composables/useThemePreview'
import { usePublicSiteUrl } from '../composables/usePublicSiteUrl'
import { useUnsavedChangesGuard } from '../composables/useUnsavedChangesGuard'
import { useEditorShortcuts } from '../composables/useEditorShortcuts'
import type { AnnotationTarget, PreparedPage } from '../editor/annotations'
import type { StreamingPreview } from '../composables/useStreamingPreview'
import type { PreviewDevice } from '../editor/preview-canvas'
import RuntimePreview from '../components/RuntimePreview.vue'
import StreamingCanvas from '../components/chat/StreamingCanvas.vue'

const HistoryPanel = defineAsyncComponent(() => import('../components/HistoryPanel.vue'))
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
  saving,
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
  applySettings,
  move,
  save,
  prepareAgent,
  restore: restoreDraft,
  resetSession,
  writes,
} = useWorkingDocument()
// The run keeps going on the server; only the brief pre-admission window must block leaving.
const agentAdmitted = shallowRef(true)
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
const {
  themeContext,
  rootStates,
  rootState,
  previewModeIcon,
  cyclePreviewMode,
  refreshThemeContext,
} = useThemePreview()
// The site theme can change in another tab while this editor stays open; reload the
// identity the mismatch banner compares against whenever the tab regains focus.
onMounted(() => window.addEventListener('focus', refreshThemeContext))
const loading = shallowRef(true)
const settingsOpen = shallowRef(false)
const historyOpen = shallowRef(false)
const view = shallowRef<'visual' | 'source' | 'runtime'>('visual')
const runtimePreview = useTemplateRef<{ refresh: () => void }>('runtimePreview')
const { pageUrl } = usePublicSiteUrl()
const publicUrl = computed(() => {
  const page = detail.value?.page
  if (!page?.publishedRevision || !page.slug) return ''
  return pageUrl(page.slug)
})
const themeMismatch = computed(() => {
  const current = themeContext.value
  const revisionTheme = detail.value?.revisionTheme
  if (!detail.value?.page.themeLayout || !revisionTheme || !current?.themeName) return undefined
  if (revisionTheme.name === current.themeName) return undefined
  return {
    saved: revisionTheme.displayName || revisionTheme.name,
    current: current.displayName || current.themeName,
  }
})
function openPublicPage() {
  if (publicUrl.value) window.open(publicUrl.value, '_blank', 'noopener')
}
const mobilePanel = shallowRef<'chat' | 'canvas'>('canvas')
const mobilePanelOptions = [
  { value: 'chat', label: '对话', icon: IconChat },
  { value: 'canvas', label: '画布', icon: IconCanvas },
] as const
const device = shallowRef<PreviewDevice>('desktop')
let loadId = 0
async function load() {
  const name = String(route.params.name)
  const id = ++loadId
  resetSession()
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
useUnsavedChangesGuard({
  dirty: () => dirty.value,
  blocked: () =>
    busy.value || preparing.value
      ? '请等待当前操作完成'
      : agentRunning.value && !agentAdmitted.value
        ? '正在提交给助手，请稍候'
        : '',
  confirm: () => confirmDialog('离开工作台？', '当前修改尚未保存，离开将丢失这些修改。'),
})
useEditorShortcuts({
  save: saveDraft,
  move,
  undoDisabled: () => settingsOpen.value,
})
onBeforeUnmount(() => {
  loadId++
  window.removeEventListener('focus', refreshThemeContext)
})
async function reload() {
  if (locked.value) return
  if (
    dirty.value &&
    !(await confirmDialog('载入最新版本？', '当前未保存修改会被替换。取消可继续保留本地修改。'))
  )
    return
  const name = String(route.params.name)
  await queryClient.invalidateQueries({ queryKey: pageKeys.detail(name) })
  await load()
}
async function saveDraft() {
  try {
    if (await save()) {
      // The saved revision snapshots the current theme, so drop any stale identity.
      refreshThemeContext()
      Toast.success('草稿已保存')
    }
  } catch (e) {
    Toast.error(errorMessage(e))
  }
}
function back() {
  if (agentRunning.value) Toast.info('助手将在后台继续生成，可在页面列表查看进度。')
  void router.push({ name: 'AiPageBuilderPages' })
}
function preview() {
  if (!detail.value) return
  if (dirty.value) Toast.info('预览显示上一次保存的草稿。')
  window.open(pagePreviewUrl(detail.value.page.name), '_blank', 'noopener')
}
async function publish(unpublish = false) {
  if (!detail.value || locked.value || (!unpublish && (dirty.value || !valid.value))) return
  if (!unpublish && !detail.value.page.publishedRevision) return
  const scripted = !!detail.value.document.js
  try {
    const result = unpublish
      ? await writes.unpublish(() => confirmDialog('取消发布？', '访客将无法访问此页面。'))
      : await writes.publishUpdate(
          async () =>
            !scripted ||
            (await confirmDialog(
              '发布包含脚本的页面？',
              '页面脚本会在访客浏览器中执行。请确认你信任这些内容。',
            )),
        )
    if (result) Toast.success(unpublish ? '已取消发布' : '页面已发布')
  } catch (e) {
    Toast.error(errorMessage(e))
  }
}
async function restore(revision: string) {
  try {
    const result = await restoreDraft(revision, () =>
      confirmDialog(
        '恢复选中版本？',
        '当前修改会先保存，选中版本将复制为新草稿。线上发布内容和当前页面属性不会改变。',
      ),
    )
    if (result) {
      historyOpen.value = false
      Toast.success('已恢复为新草稿')
    }
  } catch (e) {
    Toast.error(errorMessage(e))
  }
}
async function applySettingsAndClose(value: Parameters<typeof applySettings>[0]) {
  try {
    const result = await applySettings(value)
    if (result) {
      settingsOpen.value = false
      Toast.success('页面属性已保存')
    }
  } catch (e) {
    Toast.error(errorMessage(e))
    if ((e as { response?: { status?: number } }).response?.status === 409) {
      error.value = '页面已在别处修改，请载入最新版本后重试。当前未保存的内容仍保留。'
    }
  }
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
    <EditorHeader
      :page="detail?.page"
      :title="detail?.page.title || '页面工作台'"
      :busy="busy"
      :locked="locked"
      :dirty="dirty"
      :valid="valid"
      :can-undo="canUndo"
      :can-redo="canRedo"
      :ready="!!detail && !loading"
      :public-url="publicUrl"
      :scripted="!!working?.js"
      :writes="writes"
      @back="back"
      @undo="move('undo')"
      @redo="move('redo')"
      @settings="settingsOpen = true"
      @history="historyOpen = true"
      @preview="preview"
      @save="saveDraft"
      @publish="publish()"
      @open-public="openPublicPage"
    />
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
    <div
      v-if="themeMismatch"
      class="shrink-0 border-b border-amber-100 bg-amber-50 px-4 py-2 text-xs leading-5 text-amber-700"
      role="status"
    >
      当前草稿基于「{{ themeMismatch.saved }}」保存，站点当前主题为「{{
        themeMismatch.current
      }}」，页面样式可能已不适配。可以让 AI 调整以适配当前主题。
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
          :disabled="busy || preparing || !valid || settingsOpen || historyOpen"
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
          @admitted="agentAdmitted = $event"
          @committed="adopt($event)"
        />
        <section
          class="min-h-0 min-w-0 flex-col"
          :class="mobilePanel === 'canvas' ? 'flex' : 'hidden md:flex'"
          aria-label="页面编辑区"
        >
          <CanvasToolbar
            v-model:view="view"
            v-model:device="device"
            :locked="locked"
            :saving="saving"
            :dirty="dirty"
            :scripted="!!working?.js"
            :has-stream-preview="!!streamPreview"
            :root-state-count="rootStates.length"
            :preview-mode-icon="previewModeIcon"
            @refresh="runtimePreview?.refresh()"
            @cycle-preview-mode="cyclePreviewMode"
          />
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
                :theme-layout="detail.page.themeLayout"
              />
              <RuntimePreview
                v-if="view === 'runtime'"
                ref="runtimePreview"
                :src="pagePreviewUrl(detail.page.name)"
                :document="detail.document"
                :device="device"
                :page-version="detail.page.version"
              />
              <VisualEditor
                v-show="view === 'visual' && !streamPreview"
                :name="detail.page.name"
                :document="working"
                :page-version="detail.page.version"
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
          </div>
        </section>
      </div>
      <PageSettings
        v-if="settingsOpen"
        :page="detail.page"
        :disabled="locked"
        :published="!!detail.page.publishedRevision"
        @close="settingsOpen = false"
        @apply="applySettingsAndClose"
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
