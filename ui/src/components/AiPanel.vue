<script setup lang="ts">
import { computed, nextTick, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import { Dialog, VDropdown, VDropdownItem, VLoading } from '@halo-dev/components'
import IconSparkles from '~icons/mingcute/sparkles-line'
import IconAdd from '~icons/mingcute/add-line'
import IconMore from '~icons/mingcute/more-2-line'
import IconArrowDown from '~icons/mingcute/down-line'
import IconCheck from '~icons/mingcute/check-line'
import IconError from '~icons/mingcute/warning-line'
import IconClose from '~icons/mingcute/close-line'
import IconSpinner from '~icons/mingcute/loading-line'
import IconChevron from '~icons/mingcute/right-line'
import type { PageDetail, PageChatQuestion } from '../api/generated'
import { usePageChat } from '../composables/usePageChat'
import ChatComposer from './chat/ChatComposer.vue'
import ChatMessageParts, { questionSlotCount } from './chat/ChatMessageParts.vue'
import ChatQuestions from './chat/ChatQuestions.vue'
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
const renaming = shallowRef(false)
const sessionTitle = shallowRef('')
const feed = useTemplateRef<HTMLElement>('feed')
const {
  sessions,
  sessionId,
  sessionBusy,
  hasMore,
  loadingEarlier,
  selectSession,
  createSession,
  renameSession,
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
} = usePageChat({
  name: props.pageName,
  prepare: props.prepare,
  baseline: () => props.document,
  dirty: () => !!props.dirty,
  version: () => props.pageVersion,
  committed: (page) => emit('committed', page),
  completed: () => emit('completed'),
})
function turnResult(role: string, metadata: unknown) {
  if (role !== 'assistant' || !metadata || typeof metadata !== 'object') return undefined
  const { status, error, revision, recovery } = metadata as Record<string, unknown>
  if (status === 'completed' && typeof revision === 'string' && revision) {
    return { failed: false, text: '本轮草稿已保存', error: '' }
  }
  const label =
    status === 'failed'
      ? '运行失败'
      : status === 'cancelled'
        ? '已停止'
        : status === 'incomplete'
          ? '本轮未完成'
          : status === 'interrupted'
            ? '运行中断'
            : ''
  if (!label) return undefined
  return {
    failed: status === 'failed' || status === 'interrupted',
    text: `${label} · ${recovery ? '进度已保留，草稿未更新' : '草稿未更新'}`,
    error: recovery ? '' : typeof error === 'string' ? error : '',
  }
}
const visibleMessages = computed(() =>
  (messages.value || []).map((message) => {
    const messageQuestions =
      message.role === 'assistant' &&
      activeRun.value &&
      message === messages.value?.[messages.value.length - 1]
        ? questions.value
        : (message.metadata as { questions?: PageChatQuestion[] } | undefined)?.questions || []
    // Questions pair with the message's ask_user calls in order and render where they
    // were asked; anything unpaired (e.g. a turn cancelled while waiting, whose tool
    // call never resolved) still renders after the message.
    const slots = questionSlotCount(message.parts)
    return {
      id: message.id,
      role: message.role,
      result: turnResult(message.role, message.metadata),
      message,
      questions: messageQuestions,
      fallbackQuestions: messageQuestions.slice(slots),
      active:
        running.value &&
        !waitingForUser.value &&
        !error.value &&
        !turnResult(message.role, message.metadata) &&
        message === messages.value?.[messages.value.length - 1],
      annotations:
        (
          message.metadata as
            { annotations?: { id: string; summary: string; comment: string }[] } | undefined
        )?.annotations || [],
    }
  }),
)
watch(preview, (value) => emit('preview', value), { immediate: true })
watch([running, loading], ([value, fetching]) => emit('running', value || fetching), {
  immediate: true,
})
watch(
  messages,
  async () => {
    const nearBottom =
      !feed.value || feed.value.scrollHeight - feed.value.scrollTop - feed.value.clientHeight < 100
    await nextTick()
    if (nearBottom && feed.value) feed.value.scrollTop = feed.value.scrollHeight
  },
  { deep: true },
)
watch(sessionId, () => {
  prompt.value = ''
  renaming.value = false
})
const currentSession = computed(() =>
  sessions.value.find((item) => item.sessionId === sessionId.value),
)
function startRename() {
  sessionTitle.value = currentSession.value?.title || ''
  renaming.value = true
}
async function saveTitle() {
  if (await renameSession(sessionTitle.value)) renaming.value = false
}
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
const suggestions = [
  '参考站点介绍和已有关于页，制作个人介绍页；缺少必要信息时先问我',
  '从站内文章整理一个专题导读页，保留真实链接，先确认专题',
  '使用已有附件和项目资料制作作品展示页，沿用当前主题风格',
]
</script>

<template>
  <aside class="h-full min-h-0 min-w-0 flex-col bg-white" aria-label="页面助手">
    <header class="flex h-12 shrink-0 items-center gap-1 border-b border-gray-100 px-3">
      <IconSparkles class="text-base text-gray-700" />
      <VDropdown>
        <button
          type="button"
          class="apb-btn-ghost h-7 max-w-44 gap-1 px-2 text-[13px]"
          :disabled="sessionBusy || disabled"
          :aria-label="`当前会话：${currentSession?.title || '新会话'}，点击切换`"
        >
          <span class="truncate">{{ currentSession?.title || '新会话' }}</span>
          <IconArrowDown class="shrink-0 text-xs text-gray-400" />
        </button>
        <template #popper>
          <VDropdownItem
            v-for="session in sessions"
            :key="session.sessionId"
            v-close-popper
            @click="selectSession(session.sessionId)"
          >
            <span class="flex items-center gap-1.5">
              <IconCheck v-if="session.sessionId === sessionId" class="text-xs text-emerald-600" />
              <span :class="{ 'ml-[18px]': session.sessionId !== sessionId }">
                {{ session.title || '新会话' }}
              </span>
            </span>
          </VDropdownItem>
        </template>
      </VDropdown>
      <div class="ml-auto flex items-center">
        <button
          type="button"
          class="apb-icon-btn h-7 w-7"
          title="新建会话"
          aria-label="新建会话"
          :disabled="sessionBusy || disabled"
          @click="createSession"
        >
          <IconAdd class="text-base" />
        </button>
        <VDropdown>
          <button
            type="button"
            class="apb-icon-btn h-7 w-7"
            title="会话操作"
            aria-label="会话操作"
            :disabled="sessionBusy || disabled || !sessionId"
          >
            <IconMore class="text-base" />
          </button>
          <template #popper>
            <VDropdownItem v-close-popper @click="startRename">重命名会话</VDropdownItem>
            <VDropdownItem v-close-popper type="danger" @click="confirmDelete">
              {{ defaultSession ? '清空会话' : '删除会话' }}
            </VDropdownItem>
          </template>
        </VDropdown>
      </div>
    </header>
    <form
      v-if="renaming"
      class="flex shrink-0 items-center gap-1.5 border-b border-gray-100 px-3 py-2"
      @submit.prevent="saveTitle"
    >
      <input
        v-model="sessionTitle"
        class="apb-input h-7 text-xs"
        aria-label="会话名称"
        maxlength="120"
        :disabled="sessionBusy"
        required
      />
      <button
        type="submit"
        class="apb-btn-outline h-7 px-2 text-xs"
        :disabled="sessionBusy || !sessionTitle.trim()"
      >
        保存
      </button>
      <button
        type="button"
        class="apb-icon-btn h-7 w-7"
        aria-label="取消重命名"
        :disabled="sessionBusy"
        @click="renaming = false"
      >
        <IconClose class="text-sm" />
      </button>
    </form>
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
      <div
        v-else-if="!visibleMessages.length"
        class="flex h-full flex-col items-center justify-center gap-3 text-center"
      >
        <span class="grid h-11 w-11 place-items-center rounded-2xl bg-gray-900 text-white">
          <IconSparkles class="text-xl" />
        </span>
        <h2 class="text-base font-semibold">从一个想法开始</h2>
        <p class="max-w-64 text-xs leading-6 text-gray-500">
          描述想要的页面，或告诉助手哪里需要调整。每次完成的修改都会保存为草稿，也可以直接在右侧画布中编辑。
        </p>
        <div class="mt-1 flex flex-col items-stretch gap-1.5">
          <button
            v-for="item in suggestions"
            :key="item"
            type="button"
            class="rounded-full border border-gray-200 px-3 py-1.5 text-xs text-gray-600 transition-colors hover:border-gray-300 hover:bg-gray-50 hover:text-gray-900"
            :disabled="disabled || !available"
            @click="prompt = item"
          >
            {{ item }}
          </button>
        </div>
      </div>
      <article v-for="message in visibleMessages" :key="message.id" class="mb-6 text-[13px]">
        <div v-if="message.role === 'user'" class="flex justify-end">
          <div class="max-w-[88%] rounded-2xl rounded-br-md bg-gray-100 px-3.5 py-2.5 leading-6">
            <p class="whitespace-pre-wrap">
              {{ message.message.parts.find((p) => p.type === 'text')?.text }}
            </p>
            <div
              v-if="message.annotations.length"
              class="mt-2 space-y-1 border-t border-gray-200 pt-2"
            >
              <div v-for="item in message.annotations" :key="item.id" class="text-xs text-gray-500">
                <span class="font-medium text-gray-600">{{ item.summary }}</span> ·
                {{ item.comment }}
              </div>
            </div>
          </div>
        </div>
        <template v-else>
          <ChatMessageParts
            :message="message.message"
            :active="message.active"
            :waiting="waitingForUser && message === visibleMessages[visibleMessages.length - 1]"
            :questions="message.questions"
            :pending-question-id="waitingForUser ? pendingQuestion?.id : undefined"
            :submitting="answering"
            :answer-error="answerError"
            @answer="answerQuestion"
          />
          <ChatQuestions
            v-for="question in message.fallbackQuestions"
            :key="question.id"
            :question="question"
            :pending="waitingForUser && pendingQuestion?.id === question.id"
            :submitting="answering"
            :error="pendingQuestion?.id === question.id ? answerError : ''"
            @answer="answerQuestion(question.id, $event)"
          />
          <div
            v-if="message.result"
            class="mt-3 flex items-start gap-1.5 text-xs"
            :class="message.result.failed ? 'text-red-600' : 'text-gray-400'"
          >
            <IconError v-if="message.result.failed" class="mt-0.5 shrink-0" />
            <IconCheck v-else class="mt-0.5 shrink-0 text-emerald-600" />
            <div>
              <p>{{ message.result.text }}</p>
              <p v-if="message.result.error" class="mt-1 break-all leading-5 text-gray-400">
                {{ message.result.error }}
              </p>
            </div>
          </div>
        </template>
      </article>
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
        {{ activeRun?.status === 'committing' ? '正在保存本轮草稿…' : '助手正在处理页面…' }}
      </div>
      <p v-else-if="notice" class="text-xs text-gray-400" role="status">{{ notice }}</p>
    </div>
    <div class="shrink-0 px-3 pb-3">
      <div v-if="annotations.length" class="mb-2">
        <button
          type="button"
          class="flex w-full items-center gap-1.5 rounded-lg border border-gray-200 bg-gray-50 px-2.5 py-1.5 text-xs text-gray-600"
          :aria-expanded="annotationsOpen"
          @click="annotationsOpen = !annotationsOpen"
        >
          <IconChevron
            class="shrink-0 text-gray-400 transition-transform"
            :class="{ 'rotate-90': annotationsOpen }"
          />
          <span class="shrink-0 font-medium">{{ annotations.length }} 条批注</span>
          <span class="min-w-0 truncate text-gray-400">
            {{ annotations[0]?.tag }} · {{ annotations[0]?.summary }}
          </span>
        </button>
        <div v-if="annotationsOpen" class="mt-1.5 space-y-1.5">
          <div
            v-for="item in annotations"
            :key="item.id"
            class="flex items-start gap-2 rounded-lg border px-2.5 py-1.5 text-xs leading-5"
            :class="
              item.stale
                ? 'border-amber-200 bg-amber-50 text-amber-700'
                : 'border-gray-200 bg-gray-50 text-gray-600'
            "
          >
            <div class="min-w-0 flex-1">
              <span class="font-medium">{{ item.tag }}</span> · {{ item.summary }}
              <p class="truncate text-gray-400">{{ item.comment }}</p>
              <p v-if="item.stale">内容已变化，请移除后重新选择</p>
            </div>
            <button
              type="button"
              class="apb-icon-btn h-6 w-6 shrink-0"
              aria-label="移除批注"
              :disabled="running || disabled"
              @click="emit('removeAnnotation', item.id)"
            >
              <IconClose class="text-xs" />
            </button>
          </div>
        </div>
      </div>
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
      <ChatComposer
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
