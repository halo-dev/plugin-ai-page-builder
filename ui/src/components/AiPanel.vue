<script setup lang="ts">
import { computed, nextTick, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import { Dialog, VLoading } from '@halo-dev/components'
import IconSpinner from '~icons/mingcute/loading-line'
import type { PageDetail } from '../api/generated'
import { usePageChat } from '../composables/usePageChat'
import ChatComposer from './chat/ChatComposer.vue'
import ChatEmptyState from './chat/ChatEmptyState.vue'
import ChatMessageItem from './chat/ChatMessageItem.vue'
import ChatSessionHeader from './chat/ChatSessionHeader.vue'
import AnnotationTray from './chat/AnnotationTray.vue'
import { buildConversationDisplay } from '../editor/chat-display'
import type { AnnotationDraft, PreparedPage } from '../editor/annotations'
import type { StreamingPreview } from '../composables/useStreamingPreview'

const props = defineProps<{
  pageName: string
  disabled: boolean
  dirty?: boolean
  pageVersion?: number
  prepare: () => Promise<number | PreparedPage>
  document: PageDetail['document']
  annotations: AnnotationDraft[]
  // One-shot handoff from the create-page dialog: prefill and start the first run.
  initialPrompt?: string
  initialModel?: string
}>()
const emit = defineEmits<{
  committed: [page: PageDetail]
  running: [value: boolean]
  preview: [value: StreamingPreview | undefined]
  completed: []
  removeAnnotation: [id: string]
}>()
const prompt = shallowRef('')
const annotationsOpen = shallowRef(false)
watch(
  () => props.annotations.length,
  (count, previous) => {
    if (count > previous) annotationsOpen.value = true
  },
)
const feed = useTemplateRef<HTMLElement>('feed')
const {
  sessions,
  sessionId,
  sessionBusy,
  hasMore,
  loadingEarlier,
  selectSession,
  createSession,
  deleteSession,
  loadEarlier,
  messages,
  preview,
  recovery,
  resumeBlocked,
  canResume,
  resume,
  viewCheckpoint,
  previewLoading,
  viewingCheckpoint,
  loading,
  available,
  statusMessage,
  modelName,
  error,
  notice,
  running,
  activeRun,
  waitingForUser,
  pendingQuestion,
  questions,
  answering,
  answerError,
  answerQuestion,
  canSend,
  canSteer,
  steer,
  lastPrompt,
  recover,
  checkStatus,
  send,
  stop,
  reconcile,
  refreshError,
  refreshing,
  retryRefresh,
} = usePageChat({
  name: props.pageName,
  prepare: props.prepare,
  baseline: () => props.document,
  dirty: () => !!props.dirty,
  version: () => props.pageVersion,
  committed: (page) => emit('committed', page),
  completed: () => emit('completed'),
})
const visibleMessages = computed(() =>
  buildConversationDisplay({
    messages: messages.value || [],
    activeRunId: activeRun.value?.runId,
    questions: questions.value,
    running: running.value,
    waitingForUser: waitingForUser.value,
    error: error.value,
  }),
)
watch(preview, (value) => emit('preview', value), { immediate: true })
watch([running, loading], ([value, fetching]) => emit('running', value || fetching), {
  immediate: true,
})
// Streaming emits many deltas per paint; coalescing the layout read and the scroll
// into one animation frame keeps long conversations responsive.
let scrollFrame = 0
let nearBottom = true
watch(
  messages,
  () => {
    if (!feed.value) return
    const hadMessages = !!messages.value?.length
    // The empty state can be taller than the panel (the preset catalog), so a
    // fresh session must start at the top instead of inheriting the old scroll.
    if (!hadMessages) {
      feed.value.scrollTop = 0
      nearBottom = true
      return
    }
    if (scrollFrame) return
    nearBottom = feed.value.scrollHeight - feed.value.scrollTop - feed.value.clientHeight < 100
    // The question card positions its first question itself; keeping the feed at
    // the bottom here would push that question back above the fold.
    if (!nearBottom || waitingForUser.value) return
    scrollFrame = requestAnimationFrame(() => {
      scrollFrame = 0
      if (feed.value && nearBottom && !waitingForUser.value)
        feed.value.scrollTop = feed.value.scrollHeight
    })
  },
  { deep: true },
)
watch(sessionId, () => {
  prompt.value = ''
})
const currentSession = computed(() =>
  sessions.value.find((item) => item.sessionId === sessionId.value),
)
const defaultSession = computed(() => sessionId.value === props.pageName)
function confirmDelete() {
  Dialog.warning({
    title: defaultSession.value ? '清空默认会话？' : '删除当前会话？',
    description: '对话记录将被删除，当前页面内容保持不变。',
    confirmText: defaultSession.value ? '清空' : '删除',
    cancelText: '取消',
    confirmType: 'danger',
    onConfirm: deleteSession,
  })
}
async function earlier() {
  const height = feed.value?.scrollHeight || 0
  const top = feed.value?.scrollTop || 0
  await loadEarlier()
  await nextTick()
  if (feed.value) feed.value.scrollTop = top + feed.value.scrollHeight - height
}
onMounted(async () => {
  await recover()
  if (props.initialModel) modelName.value = props.initialModel
  if (props.initialPrompt) {
    prompt.value = props.initialPrompt
    await submit()
  }
})
async function submit() {
  if (props.disabled || !canSend.value) return
  const text = prompt.value
  const requestPrompt = text.trim() || (props.annotations.length ? '按照批注修改页面' : '')
  if (!requestPrompt) return
  // Clear right away: send() only resolves after the whole run finishes.
  prompt.value = ''
  annotationsOpen.value = false
  if (!(await send(requestPrompt))) prompt.value = text
}
async function submitSteer() {
  const text = prompt.value.trim()
  if (props.disabled || !canSteer.value || !text) return
  prompt.value = ''
  if (!(await steer(text))) prompt.value = text
}
async function retry() {
  if (!props.disabled && canSend.value && lastPrompt.value) {
    annotationsOpen.value = false
    await send(lastPrompt.value, true)
  }
}
const blankPage = computed(() => {
  const { html, css, js } = props.document
  return !html?.trim() && !css?.trim() && !js?.trim()
})
// Kept here so the chosen preset group survives the empty state unmounting during a load.
const activePresetGroup = shallowRef(0)
watch(blankPage, () => (activePresetGroup.value = 0))
</script>

<template>
  <aside class="h-full min-h-0 min-w-0 flex-col bg-white" aria-label="页面助手">
    <ChatSessionHeader
      :sessions="sessions"
      :current-session="currentSession"
      :session-id="sessionId"
      :default-session="defaultSession"
      :busy="sessionBusy"
      :disabled="disabled"
      @select="selectSession"
      @create="createSession"
      @delete="confirmDelete"
    />
    <div ref="feed" class="min-h-0 flex-1 overflow-auto px-4 py-4" role="log" aria-label="页面对话">
      <div v-if="hasMore" class="mb-4 text-center">
        <button
          type="button"
          class="apb-btn-ghost h-7 text-xs"
          :disabled="sessionBusy"
          @click="earlier"
        >
          <IconSpinner v-if="loadingEarlier" class="animate-spin text-sm" />
          加载更早历史
        </button>
      </div>
      <VLoading v-if="loading" />
      <ChatEmptyState
        v-else-if="!visibleMessages.length"
        v-model:active-preset-group="activePresetGroup"
        :blank-page="blankPage"
        :disabled="disabled"
        :available="available"
        @pick="prompt = $event"
      />
      <ChatMessageItem
        v-for="message in visibleMessages"
        :key="message.id"
        :message="message"
        :waiting-for-user="waitingForUser"
        :pending-question-id="pendingQuestion?.id"
        :answering="answering"
        :answer-error="answerError"
        @answer="answerQuestion"
      />
      <p v-if="waitingForUser && !loading" class="text-xs leading-5 text-gray-500" role="status">
        等待你的回答，提交后助手将继续。也可以点击下方停止按钮取消本轮。
      </p>
      <div
        v-else-if="running && !loading"
        class="flex items-center gap-2 text-xs text-gray-400"
        role="status"
      >
        <span class="flex gap-1">
          <span
            class="h-1.5 w-1.5 animate-bounce rounded-full bg-gray-400 [animation-delay:-0.3s]"
          />
          <span
            class="h-1.5 w-1.5 animate-bounce rounded-full bg-gray-400 [animation-delay:-0.15s]"
          />
          <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-gray-400" />
        </span>
        {{ activeRun?.status === 'committing' ? '正在保存本轮修改…' : '助手正在处理页面…' }}
      </div>
      <p v-else-if="notice" class="text-xs text-gray-400" role="status">{{ notice }}</p>
    </div>
    <div class="flex min-h-0 flex-col px-3 pb-3">
      <div class="min-h-0 flex-1 overflow-y-auto">
        <AnnotationTray
          v-model:open="annotationsOpen"
          :annotations="annotations"
          :running="running"
          :disabled="disabled"
          @remove="emit('removeAnnotation', $event)"
        />
        <div
          v-if="recovery && !running"
          class="mb-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900"
          role="status"
        >
          <p>进度已保留，尚未写入草稿。继续生成会使用原模型，开始新一轮调用。</p>
          <p v-if="resumeBlocked" class="mt-1">{{ resumeBlocked }}</p>
          <p v-else-if="error" class="mt-1">{{ error }}</p>
          <div class="mt-2 flex flex-wrap gap-3">
            <button
              type="button"
              class="font-medium underline"
              :disabled="disabled || !canResume"
              @click="resume"
            >
              继续生成
            </button>
            <button
              type="button"
              class="underline"
              :disabled="previewLoading || sessionBusy"
              @click="viewCheckpoint"
            >
              {{ viewingCheckpoint ? '返回草稿' : '查看进度' }}
            </button>
            <button type="button" class="underline" :disabled="disabled || !canSend" @click="retry">
              重新生成
            </button>
          </div>
        </div>
        <div
          v-if="refreshError"
          class="mb-2 flex items-start gap-2 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900"
          role="alert"
        >
          <p class="min-w-0 flex-1">{{ refreshError }}</p>
          <button
            type="button"
            class="shrink-0 font-medium underline"
            :disabled="refreshing || sessionBusy"
            @click="retryRefresh"
          >
            刷新会话
          </button>
        </div>
        <div
          v-if="error && (!recovery || running)"
          class="mb-2 flex items-start gap-2 rounded-lg bg-red-50 px-3 py-2 text-xs leading-5 text-red-700"
          role="alert"
        >
          <p class="min-w-0 flex-1">{{ error }}</p>
          <button
            v-if="activeRun"
            type="button"
            class="shrink-0 font-medium underline"
            @click="reconcile"
          >
            检查运行结果
          </button>
          <button
            v-else-if="canSend && lastPrompt"
            type="button"
            class="shrink-0 font-medium underline"
            :disabled="disabled"
            @click="retry"
          >
            重试
          </button>
          <button v-else type="button" class="shrink-0 font-medium underline" @click="recover">
            重新加载会话
          </button>
        </div>
        <div
          v-if="!loading && !available"
          class="mb-2 flex items-center gap-2 rounded-lg bg-gray-50 px-3 py-2 text-xs leading-5 text-gray-500"
        >
          <p class="min-w-0 flex-1">{{ statusMessage || 'AI 服务尚未启用，仍可手动编辑页面。' }}</p>
          <button
            type="button"
            class="shrink-0 font-medium text-gray-700 underline"
            @click="checkStatus"
          >
            重新检测
          </button>
        </div>
      </div>
      <ChatComposer
        class="shrink-0"
        v-model:prompt="prompt"
        v-model:model="modelName"
        :disabled="disabled"
        :running="running"
        :can-send="canSend"
        :can-steer="canSteer"
        :has-annotations="annotations.length > 0"
        :can-stop="!!activeRun && activeRun.status !== 'committing'"
        :available="available"
        @send="submit"
        @steer="submitSteer"
        @stop="stop"
      />
    </div>
  </aside>
</template>
