import { computed, onScopeDispose, shallowRef, watch, type DeepReadonly } from 'vue'
import { useLocalStorage } from '@vueuse/core'
import { DefaultChatTransport, useChat, type UIMessage } from '@halo-dev/ai-foundation-sdk'
import {
  chatUrl,
  prepareChatRequest,
  aiPageBuilderAiApi,
  aiPageBuilderChatApi,
  type SilentRequestConfig,
} from '../api/clients'
import { errorMessage } from '../utils/error'
import type {
  PageDetail,
  PageChatRequest,
  PageChatSessionSummary,
  PageChatQuestion,
  PageChatRecovery,
} from '../api/generated'

import { authenticatedChatFetch } from '../api/stream'
import type { PreparedPage } from '../editor/annotations'
import { restoreConversation } from '../editor/chat-display'
import { useStreamingPreview, type StreamingPreview } from './useStreamingPreview'

import { activeRunStatuses as activeStatuses, useConversationRun } from './useConversationRun'

export function usePageChat(options: {
  name: string
  prepare: () => Promise<number | PreparedPage>
  baseline?: () => Pick<PageDetail['document'], 'html' | 'css' | 'js'> | undefined
  dirty?: () => boolean
  version?: () => number | undefined
  committed: (page: PageDetail) => void
  completed?: () => void
}) {
  const sessions = shallowRef<PageChatSessionSummary[]>([])
  const sessionId = shallowRef<string>()
  const lastSessionId = useLocalStorage(`ai-page-builder:last-session:${options.name}`, '')
  const hasMore = shallowRef(false)
  const loadingEarlier = shallowRef(false)
  let nextBefore: string | undefined
  const loading = shallowRef(true)
  const preparing = shallowRef(false)
  const available = shallowRef(false)
  const statusMessage = shallowRef('')
  const localError = shallowRef('')
  const recovery = shallowRef<PageChatRecovery>()
  const checkpointPreview = shallowRef<StreamingPreview>()
  const previewLoading = shallowRef(false)
  // Last used model survives reloads and is shared across pages.
  const modelName = useLocalStorage('ai-page-builder:model-name', '')
  const lastPrompt = shallowRef('')
  const recovered = shallowRef(false)
  let disposed = false
  let request: PageChatRequest | undefined
  let historicalAnnotations = false
  let sessionRefresh = 0
  let sessionsApplied = 0
  const run = useConversationRun({
    transport: {
      // Polling for admission treats 404 as "not yet"; mute it so an expected miss does not
      // raise a Console toast, and bound it so a hung request cannot defeat the deadline.
      get: async (runId) =>
        (
          await aiPageBuilderChatApi.getPageChatRun({ name: options.name, runId }, {
            mute: true,
            timeout: 3000,
          } as SilentRequestConfig)
        ).data,
      answer: async (runId, questionId, answers) =>
        (
          await aiPageBuilderChatApi.answerPageChatQuestion({
            name: options.name,
            runId,
            questionId,
            pageChatAnswerRequest: { answers },
          })
        ).data,
      cancel: async (runId) =>
        (await aiPageBuilderChatApi.cancelPageChatRun({ name: options.name, runId })).data,
    },
    streaming: () => chat.isLoading.value,
    stopStream: () => chat.stop(),
    discardPreview: () => streamPreview.discard(),
    refresh: async (current) => {
      await loadConversation(sessionId.value, false, current)
      if (current() && !activeRun.value) await refreshSessions(current)
    },
    committed: options.committed,
    completed: options.completed,
  })
  const {
    activeRun,
    admitted: runAdmitted,
    confirmed: runConfirmed,
    awaitAdmission,
    questions,
    waitingForUser,
    pendingQuestion,
    answering,
    answerError,
    reconcile,
    answerQuestion,
    stop,
    refreshError,
    refreshing,
    retryRefresh,
  } = run
  const error = computed(() => localError.value || run.error.value)
  const notice = run.notice
  const chat = useChat({
    id: `ai-page-builder-${options.name}`,
    transport: new DefaultChatTransport({
      api: chatUrl(options.name),
      fetch: authenticatedChatFetch,
      prepareSendMessagesRequest: () => {
        if (!request) throw new Error('本轮请求尚未准备完成。')
        return prepareChatRequest(options.name, request)
      },
    }),
    sendAutomaticallyWhen: () => false,
    onData(part) {
      if (disposed || !activeRun.value) return
      if (part.type === 'data-page-committed') run.committed(part.data as PageDetail)
      if (part.type === 'data-page-staged') streamPreview.accept(part.data)
      if (part.type === 'data-generation-recovery') {
        const data = part.data as { message: string }
        run.recovering(data.message)
        streamPreview.restartAttempt()
      }
      if (part.type === 'data-steer') {
        const data = part.data as { runId: string; index: number; text: string }
        if (data.runId === activeRun.value.runId)
          appendSteerMessage(data.runId, data.text, data.index)
      }
      if (part.type === 'data-user-question' || part.type === 'data-user-answer') {
        const data = part.data as { runId: string; question: PageChatQuestion }
        if (data.runId !== activeRun.value.runId) return
        run.questionEvent(data.runId, data.question, part.type === 'data-user-answer')
      }
    },
    onError(e) {
      run.streamFailed(e)
    },
  })
  // The agent names the conversation on every turn; refresh the session list as soon
  // as each rename lands so the sidebar stops showing the previous title.
  watch(
    () =>
      chat.messages.value?.reduce(
        (count, message) =>
          count +
          message.parts.filter(
            (part) =>
              'toolName' in part &&
              part.toolName === 'rename_session' &&
              part.state === 'output-available',
          ).length,
        0,
      ) ?? 0,
    (count, previous) => {
      if (count > (previous ?? 0) && activeRun.value) void refreshSessions().catch(() => {})
    },
  )
  const streamPreview = useStreamingPreview({
    messages: chat.messages,
    runId: computed(() => activeRun.value?.runId),
    baseline: () => options.baseline?.(),
  })
  const messages = computed<DeepReadonly<UIMessage[]>>(() => {
    const current = chat.messages.value || []
    const run = activeRun.value
    const last = current[current.length - 1]
    const lastRunId = (last?.metadata as { runId?: string } | undefined)?.runId
    if (
      !run ||
      (last?.role === 'assistant' &&
        (lastRunId === run.runId || (!lastRunId && chat.isLoading.value)))
    )
      return current
    // The active run can arrive before its first stream message or persisted display turn.
    return [
      ...current,
      {
        id: `${run.runId}-assistant`,
        role: 'assistant' as const,
        parts: [],
        metadata: { runId: run.runId },
      },
    ]
  })
  const running = computed(() => !!activeRun.value || chat.isLoading.value)
  // Leaving is safe only once the server owns the run; no active run is trivially safe.
  const admitted = computed(() => !activeRun.value || runAdmitted.value)
  // Only a confirmed run is known to survive the editor; the timeout fallback is not.
  const confirmed = computed(() => !activeRun.value || runConfirmed.value)
  const canSend = computed(
    () =>
      available.value &&
      !!modelName.value.trim() &&
      recovered.value &&
      !loading.value &&
      !running.value &&
      !preparing.value &&
      !loadingEarlier.value,
  )

  async function checkStatus() {
    try {
      const status = (await aiPageBuilderAiApi.getAiPageBuilderAiStatus()).data
      available.value = status.available
      statusMessage.value = status.message
    } catch (e) {
      available.value = false
      statusMessage.value = errorMessage(e)
    }
  }
  async function loadConversation(
    id = sessionId.value,
    refreshRun = true,
    current: () => boolean = () => !disposed,
  ) {
    const conversation = (
      await aiPageBuilderChatApi.getPageChat({ name: options.name, sessionId: id, size: 20 })
    ).data
    if (!current()) return
    const switched = sessionId.value !== conversation.sessionId
    if (switched || recovery.value?.checkpointVersion !== conversation.recovery?.checkpointVersion)
      checkpointPreview.value = undefined
    if (switched) run.reset()
    recovery.value = conversation.recovery ?? undefined
    sessionId.value = conversation.sessionId
    lastSessionId.value = conversation.sessionId
    hasMore.value = conversation.hasMore
    nextBefore = conversation.nextBefore ?? undefined
    const restored = restoreConversation(conversation.turns)
    chat.setMessages(restored.messages)
    recovered.value = true
    const active = activeRun.value || conversation.activeRun || undefined
    run.restore(
      conversation.activeRun ?? undefined,
      restored.questionsByRunId.get(active?.runId ?? '') || [],
    )
    const last = conversation.turns[conversation.turns.length - 1]
    if (last || switched) lastPrompt.value = last?.prompt || ''
    historicalAnnotations = !!last?.annotations?.length
    if (refreshRun && last && ['failed', 'interrupted', 'incomplete'].includes(last.status))
      localError.value = last.error || '上次运行中断，可以继续生成。'
  }
  const sessionBusy = computed(
    () => running.value || preparing.value || loading.value || loadingEarlier.value,
  )
  async function refreshSessions(current: () => boolean = () => !disposed) {
    const seq = ++sessionRefresh
    const items =
      (await aiPageBuilderChatApi.listPageChatSessions({ name: options.name })).data.items || []
    // A refresh started earlier can resolve later. Apply the newest successful response;
    // a newer refresh that fails must not discard an older one that succeeded.
    if (current() && seq > sessionsApplied) {
      sessionsApplied = seq
      sessions.value = items
    }
  }
  async function selectSession(id: string) {
    if (sessionBusy.value || id === sessionId.value) return
    loading.value = true
    localError.value = ''
    request = undefined
    streamPreview.discard()
    try {
      await loadConversation(id)
      if (activeRun.value) await reconcile()
    } catch (e) {
      localError.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }
  async function createSession() {
    if (sessionBusy.value) return
    loading.value = true
    localError.value = ''
    request = undefined
    streamPreview.discard()
    try {
      const created = (
        await aiPageBuilderChatApi.createPageChatSession({
          name: options.name,
          createPageChatSessionRequest: {},
        })
      ).data
      await refreshSessions()
      await loadConversation(created.sessionId)
    } catch (e) {
      localError.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }
  async function deleteSession() {
    const current = sessions.value.find((item) => item.sessionId === sessionId.value)
    if (sessionBusy.value || !current) return
    loading.value = true
    try {
      await aiPageBuilderChatApi.deletePageChatSession({
        name: options.name,
        sessionId: current.sessionId,
        version: current.version,
      })
      request = undefined
      lastPrompt.value = ''
      historicalAnnotations = false
      await refreshSessions()
      await loadConversation(sessions.value[0]?.sessionId)
    } catch (e) {
      localError.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }
  async function loadEarlier() {
    if (sessionBusy.value || !hasMore.value || !nextBefore) return
    loadingEarlier.value = true
    try {
      const earlier = (
        await aiPageBuilderChatApi.getPageChat({
          name: options.name,
          sessionId: sessionId.value,
          before: nextBefore,
          size: 20,
        })
      ).data
      if (disposed) return
      const currentIds = new Set((chat.messages.value || []).map((message) => message.id))
      chat.setMessages([
        ...restoreConversation(earlier.turns).messages.filter(
          (message) => !currentIds.has(message.id),
        ),
        ...(chat.messages.value || []),
      ] as UIMessage[])
      hasMore.value = earlier.hasMore
      nextBefore = earlier.nextBefore ?? undefined
    } catch (e) {
      localError.value = errorMessage(e)
    } finally {
      loadingEarlier.value = false
    }
  }
  async function recover() {
    if (preparing.value || loadingEarlier.value) return
    recovered.value = false
    loading.value = true
    localError.value = ''
    try {
      await Promise.all([checkStatus(), refreshSessions()])
      const active = sessions.value.find((item) =>
        activeStatuses.includes(item.activeRun?.status || ''),
      )
      const previous = sessions.value.find(
        (item) => item.sessionId === (sessionId.value || lastSessionId.value),
      )
      const target = active?.sessionId || previous?.sessionId || options.name
      try {
        await loadConversation(target)
      } catch (e) {
        if (
          target === options.name ||
          (e as { response?: { status?: number } }).response?.status !== 404
        )
          throw e
        await refreshSessions()
        if (disposed) return
        const replacement = sessions.value.find((item) =>
          activeStatuses.includes(item.activeRun?.status || ''),
        )
        await loadConversation(replacement?.sessionId || options.name)
      }
      if (activeRun.value) await reconcile()
    } catch (e) {
      localError.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }
  async function send(prompt: string, retry = false) {
    const text = prompt.trim()
    if (!canSend.value || !text || text.length > 8000) return false
    preparing.value = true
    localError.value = ''
    try {
      if (retry && historicalAnnotations && !request?.annotations?.length)
        throw new Error('历史批注只保留说明，请重新选择目标后发送。')
      const prepared = await options.prepare()
      if (disposed) return false
      if (retry && request?.annotations?.length) {
        if (typeof prepared === 'number' || prepared.baseHash !== request.baseHash)
          throw new Error('原批注对应的内容已变化，请重新选择目标并发送。')
        prepared.annotations = request.annotations.map((item) => ({
          ...item,
          path: [...item.path],
        }))
      }
      request = {
        ...(typeof prepared === 'number' ? { version: prepared } : prepared),
        runId: crypto.randomUUID(),
        prompt: text,
        modelName: modelName.value,
        sessionId: sessionId.value,
      }
      checkpointPreview.value = undefined
      recovery.value = undefined
      run.begin(request.runId)
      lastPrompt.value = text
      chat.clearError()
      const sending = chat.sendMessage({ text })
      void awaitAdmission(request.runId)
      await sending
      await reconcile()
      return !error.value
    } catch (e) {
      streamPreview.discard()
      localError.value = errorMessage(e)
      if (activeRun.value) await reconcile()
      return false
    } finally {
      preparing.value = false
    }
  }
  const resumeBlocked = computed(() =>
    options.dirty?.()
      ? '当前有未保存的编辑，请先保存或撤销这些编辑，再检查恢复进度。'
      : recovery.value?.blockedReason || '',
  )
  const canResume = computed(
    () =>
      available.value &&
      recovered.value &&
      !sessionBusy.value &&
      !previewLoading.value &&
      !!recovery.value?.canResume &&
      !resumeBlocked.value,
  )
  async function loadCheckpoint() {
    if (!sessionId.value) throw new Error('会话尚未加载。')
    const id = sessionId.value
    const currentRunId = activeRun.value?.runId
    const checkpoint = (
      await aiPageBuilderChatApi.getPageChatCheckpoint({
        name: options.name,
        sessionId: id,
      })
    ).data
    if (disposed || id !== sessionId.value || currentRunId !== activeRun.value?.runId)
      throw new Error('会话已变化，请重新载入。')
    recovery.value = checkpoint.recovery
    return checkpoint
  }
  async function viewCheckpoint() {
    if (checkpointPreview.value) {
      checkpointPreview.value = undefined
      return
    }
    if (sessionBusy.value || previewLoading.value) return
    previewLoading.value = true
    try {
      const saved = await loadCheckpoint()
      checkpointPreview.value = {
        runId: saved.recovery.sourceRunId,
        toolCallId: 'checkpoint',
        content: saved.document,
        status: 'checkpoint',
      }
    } catch (e) {
      localError.value = errorMessage(e)
    } finally {
      previewLoading.value = false
    }
  }
  async function resume() {
    if (!canResume.value) return false
    preparing.value = true
    localError.value = ''
    try {
      const saved = await loadCheckpoint()
      if (resumeBlocked.value || !saved.recovery.canResume || options.dirty?.())
        throw new Error(resumeBlocked.value || '恢复进度已变化，请重新载入。')
      if (options.version && options.version() !== saved.request.version)
        throw new Error('页面已变化，请重新载入后检查恢复进度。')
      // Resuming must not run prepare(), which can save local edits and change the base revision.
      request = {
        ...saved.request,
        sessionId: sessionId.value,
        runId: crypto.randomUUID(),
        resumeFromRunId: saved.recovery.sourceRunId,
        checkpointVersion: saved.recovery.checkpointVersion,
      }
      checkpointPreview.value = undefined
      recovery.value = undefined
      run.begin(request.runId)
      streamPreview.seedBaseline(saved.document)
      lastPrompt.value = request.prompt
      chat.clearError()
      const sending = chat.sendMessage({ text: '继续生成' })
      void awaitAdmission(request.runId)
      await sending
      await reconcile()
      return !error.value
    } catch (e) {
      localError.value = errorMessage(e)
      if (activeRun.value) await reconcile()
      return false
    } finally {
      preparing.value = false
    }
  }
  const steering = shallowRef(false)
  // Mid-run instructions are queued server-side and injected into the next model step.
  const canSteer = computed(
    () =>
      !steering.value &&
      !!activeRun.value &&
      (activeRun.value.status === 'running' || activeRun.value.status === 'waiting_for_user'),
  )
  function appendSteerMessage(runId: string, text: string, index: number) {
    const current = [...(chat.messages.value || [])] as UIMessage[]
    const prefix = `${runId}-steer-`
    const id = `${prefix}${index}`
    if (current.some((message) => message.id === id)) return
    const message: UIMessage = {
      id,
      role: 'user',
      parts: [{ type: 'text', id: `${id}-text`, text }],
    }
    const next = current.findIndex(
      (item) =>
        item.id === `${runId}-assistant` ||
        (item.id.startsWith(prefix) && Number(item.id.slice(prefix.length)) > index),
    )
    if (next >= 0) current.splice(next, 0, message)
    // A live assistant message has a server-generated id, so only its position
    // identifies it. Inserting after it would make the SDK append every later
    // delta as a second bubble instead of extending the existing one.
    else if (current[current.length - 1]?.role === 'assistant')
      current.splice(current.length - 1, 0, message)
    else current.push(message)
    chat.setMessages(current)
  }
  async function steer(text: string) {
    const run = activeRun.value
    const value = text.trim()
    if (!canSteer.value || !run || !value || value.length > 2000) return false
    const id = sessionId.value
    const isCurrent = () =>
      !disposed && sessionId.value === id && activeRun.value?.runId === run.runId
    steering.value = true
    try {
      await aiPageBuilderChatApi.steerPageChatRun({
        name: options.name,
        runId: run.runId,
        pageChatSteerRequest: { text: value },
      })
      if (isCurrent()) {
        try {
          const { data } = await aiPageBuilderChatApi.getPageChat({
            name: options.name,
            sessionId: id,
            size: 20,
          })
          if (isCurrent()) {
            data.turns
              .find((turn) => turn.runId === run.runId)
              ?.steers?.forEach((text, index) => appendSteerMessage(run.runId, text, index))
          }
        } catch {
          // The write succeeded; SSE or the next history refresh can recover its display.
        }
      }
      return true
    } catch (e) {
      if (!isCurrent()) return true
      localError.value = errorMessage(e)
      return false
    } finally {
      steering.value = false
    }
  }
  onScopeDispose(() => {
    disposed = true
  })
  return {
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
    preview: computed(() => streamPreview.preview.value || checkpointPreview.value),
    recovery,
    resumeBlocked,
    canResume,
    resume,
    viewCheckpoint,
    previewLoading,
    viewingCheckpoint: computed(() => !!checkpointPreview.value),
    status: chat.status,
    loading,
    available,
    statusMessage,
    modelName,
    error,
    notice,
    running,
    admitted,
    confirmed,
    preparing,
    activeRun,
    waitingForUser,
    pendingQuestion,
    questions,
    answering,
    answerError,
    answerQuestion,
    refreshError,
    refreshing,
    retryRefresh,
    canSend,
    canSteer,
    steering,
    steer,
    lastPrompt,
    recover,
    checkStatus,
    send,
    stop,
    reconcile,
  }
}
