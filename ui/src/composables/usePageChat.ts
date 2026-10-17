import { computed, onScopeDispose, shallowRef, type DeepReadonly } from 'vue'
import { useLocalStorage } from '@vueuse/core'
import {
  DefaultChatTransport,
  useChat,
  type UIMessage,
  type ToolPart,
} from '@halo-dev/ai-foundation-sdk'
import {
  chatUrl,
  prepareChatRequest,
  aiPageBuilderAiApi,
  aiPageBuilderChatApi,
  type ChatRun,
  type ChatTurn,
} from '../api/clients'
import { errorMessage } from '../utils/error'
import type {
  PageDetail,
  PageChatRequest,
  PageChatSessionSummary,
  PageChatQuestion,
  PageChatAnswerItem,
  PageChatRecovery,
} from '../api/generated'

import { authenticatedChatFetch } from '../api/stream'
import type { PreparedPage } from '../editor/annotations'
import { useStreamingPreview, type StreamingPreview } from './useStreamingPreview'

const activeStatuses = ['running', 'committing', 'waiting_for_user']

// A resumed run inherits the checkpoint's answered questions for task context
// (PageChatService.reserve); they belong to the source turn and would otherwise
// mispair with this turn's ask_user tool parts, which start empty on resume.
function turnQuestions(turn: ChatTurn): PageChatQuestion[] {
  const started = Date.parse(turn.createdAt)
  return (turn.questions || []).filter((question) => {
    const asked = Date.parse(question.createdAt)
    return Number.isNaN(started) || Number.isNaN(asked) || asked >= started
  })
}

export function conversationMessages(turns: ChatTurn[]): UIMessage[] {
  return turns.flatMap((turn) => [
    {
      id: `${turn.runId}-user`,
      role: 'user' as const,
      parts: [{ type: 'text' as const, id: `${turn.runId}-prompt`, text: turn.prompt }],
      metadata: { annotations: turn.annotations },
    },
    ...(turn.steers || []).map((text, index) => ({
      id: `${turn.runId}-steer-${index}`,
      role: 'user' as const,
      parts: [{ type: 'text' as const, id: `${turn.runId}-steer-${index}-text`, text }],
    })),
    {
      id: `${turn.runId}-assistant`,
      role: 'assistant' as const,
      parts: turn.parts?.length
        ? turn.parts.flatMap((part): UIMessage['parts'] => {
            if (part.type === 'text' || part.type === 'reasoning')
              return [
                {
                  type: part.type,
                  id: part.id,
                  text: (part.text || '') + (part.truncated ? '\n（内容已截断）' : ''),
                },
              ]
            if (part.type !== 'tool' || !part.toolName) return []
            return [
              {
                type: `tool-${part.toolName}`,
                toolName: part.toolName,
                toolCallId: part.id,
                state:
                  part.status === 'error'
                    ? 'output-error'
                    : part.status === 'running'
                      ? 'input-available'
                      : 'output-available',
                output: { summary: part.summary || '' },
              },
            ]
          })
        : [
            ...turn.tools.map((tool, index): ToolPart => ({
              type: `tool-${tool.name}`,
              toolName: tool.name,
              toolCallId: `${turn.runId}-${index}`,
              state:
                tool.status === 'failed' || tool.status === 'error'
                  ? 'output-error'
                  : 'output-available',
              output: { summary: tool.summary },
            })),
            { type: 'text' as const, id: `${turn.runId}-text`, text: turn.text },
          ],
      metadata: {
        runId: turn.runId,
        revision: turn.revision,
        status: turn.status,
        error: turn.error,
        questions: turnQuestions(turn),
        recovery: turn.recovery,
      },
    },
  ])
}

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
  const hasMore = shallowRef(false)
  const loadingEarlier = shallowRef(false)
  let nextBefore: string | undefined
  const loading = shallowRef(true)
  const preparing = shallowRef(false)
  const available = shallowRef(false)
  const statusMessage = shallowRef('')
  const error = shallowRef('')
  const notice = shallowRef('')
  const recovery = shallowRef<PageChatRecovery>()
  const checkpointPreview = shallowRef<StreamingPreview>()
  const previewLoading = shallowRef(false)
  // Last used model survives reloads and is shared across pages.
  const modelName = useLocalStorage('ai-page-builder:model-name', '')
  const activeRun = shallowRef<ChatRun>()
  const answering = shallowRef(false)
  const answerError = shallowRef('')
  const waitingForUser = computed(() => activeRun.value?.status === 'waiting_for_user')
  const pendingQuestion = computed(() => activeRun.value?.pendingQuestion)
  const questions = shallowRef<PageChatQuestion[]>([])
  function rememberQuestion(question: PageChatQuestion) {
    questions.value = [...questions.value.filter((item) => item.id !== question.id), question]
  }
  const lastPrompt = shallowRef('')
  const recovered = shallowRef(false)
  let disposed = false
  let polling: ReturnType<typeof setTimeout> | undefined
  let request: PageChatRequest | undefined
  let historicalAnnotations = false
  const applied = new Set<string>()
  function apply(page: PageDetail) {
    const revision = page.page.draftRevision
    if (applied.has(revision)) return
    applied.add(revision)
    options.committed(page)
  }
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
      if (part.type === 'data-page-committed') apply(part.data as PageDetail)
      if (part.type === 'data-page-staged') streamPreview.accept(part.data)
      if (part.type === 'data-generation-recovery') {
        const data = part.data as { message: string }
        notice.value = data.message
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
        rememberQuestion(data.question)
        activeRun.value = {
          ...activeRun.value,
          status: part.type === 'data-user-question' ? 'waiting_for_user' : 'running',
          pendingQuestion: part.type === 'data-user-question' ? data.question : undefined,
        }
        answerError.value = ''
        clearTimeout(polling)
        polling = setTimeout(() => void reconcile(), 1500)
      }
    },
    onError(e) {
      streamPreview.discard()
      error.value = errorMessage(e)
    },
  })
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
  async function loadConversation(id = sessionId.value, refreshRun = true) {
    const conversation = (
      await aiPageBuilderChatApi.getPageChat({ name: options.name, sessionId: id, size: 20 })
    ).data
    if (disposed) return
    const switched = sessionId.value !== conversation.sessionId
    if (switched || recovery.value?.checkpointVersion !== conversation.recovery?.checkpointVersion)
      checkpointPreview.value = undefined
    recovery.value = conversation.recovery ?? undefined
    sessionId.value = conversation.sessionId
    hasMore.value = conversation.hasMore
    nextBefore = conversation.nextBefore ?? undefined
    chat.setMessages(conversationMessages(conversation.turns))
    recovered.value = true
    const active = conversation.activeRun
    if (refreshRun)
      activeRun.value = active && activeStatuses.includes(active.status) ? active : undefined
    const last = conversation.turns[conversation.turns.length - 1]
    const activeTurn = conversation.turns.find((turn) => turn.runId === activeRun.value?.runId)
    const activeTurnQuestions = activeTurn ? turnQuestions(activeTurn) : []
    if (refreshRun) questions.value = activeTurnQuestions
    else
      for (const question of activeTurnQuestions) {
        const known = questions.value.find((item) => item.id === question.id)
        if (!known?.answers?.length || question.answers?.length) rememberQuestion(question)
      }
    if (activeRun.value?.pendingQuestion) rememberQuestion(activeRun.value.pendingQuestion)
    if (last || switched) lastPrompt.value = last?.prompt || ''
    historicalAnnotations = !!last?.annotations?.length
    if (last && ['failed', 'interrupted', 'incomplete'].includes(last.status))
      error.value = last.error || '上次运行中断，可以继续生成。'
  }
  const sessionBusy = computed(
    () => running.value || preparing.value || loading.value || loadingEarlier.value,
  )
  async function refreshSessions() {
    sessions.value =
      (await aiPageBuilderChatApi.listPageChatSessions({ name: options.name })).data.items || []
  }
  async function selectSession(id: string) {
    if (sessionBusy.value || id === sessionId.value) return
    loading.value = true
    error.value = ''
    notice.value = ''
    request = undefined
    streamPreview.discard()
    try {
      await loadConversation(id)
      if (activeRun.value) await reconcile()
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }
  async function createSession() {
    if (sessionBusy.value) return
    loading.value = true
    error.value = ''
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
      notice.value = ''
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }
  async function renameSession(title: string) {
    const current = sessions.value.find((item) => item.sessionId === sessionId.value)
    if (sessionBusy.value || !current || !title.trim()) return false
    loading.value = true
    try {
      await aiPageBuilderChatApi.renamePageChatSession({
        name: options.name,
        sessionId: current.sessionId,
        renamePageChatSessionRequest: { title: title.trim(), version: current.version },
      })
      await refreshSessions()
      return true
    } catch (e) {
      error.value = errorMessage(e)
      return false
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
      notice.value = ''
    } catch (e) {
      error.value = errorMessage(e)
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
        ...conversationMessages(earlier.turns).filter((message) => !currentIds.has(message.id)),
        ...(chat.messages.value || []),
      ] as UIMessage[])
      hasMore.value = earlier.hasMore
      nextBefore = earlier.nextBefore ?? undefined
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loadingEarlier.value = false
    }
  }
  async function recover() {
    if (preparing.value || loadingEarlier.value) return
    recovered.value = false
    loading.value = true
    error.value = ''
    try {
      await Promise.all([checkStatus(), refreshSessions()])
      const active = sessions.value.find((item) =>
        activeStatuses.includes(item.activeRun?.status || ''),
      )
      await loadConversation(active?.sessionId || sessionId.value)
      if (activeRun.value) await reconcile()
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }
  async function settle(result: ChatRun) {
    if (disposed) return
    activeRun.value = result
    if (activeStatuses.includes(result.status)) {
      error.value = ''
      if (result.pendingQuestion) rememberQuestion(result.pendingQuestion)
      notice.value =
        result.status === 'waiting_for_user'
          ? '等待你的回答，提交后助手将继续。'
          : result.status === 'committing'
            ? '正在保存本轮草稿…'
            : '助手正在处理页面…'
      // A recovered run has no stream; refresh its text and answered questions while polling.
      if (!chat.isLoading.value) await loadConversation(sessionId.value, false)
      clearTimeout(polling)
      polling = setTimeout(() => void reconcile(), 1500)
      return
    }
    clearTimeout(polling)
    answerError.value = ''
    streamPreview.discard()
    if (result.page) apply(result.page)
    if (result.status === 'completed') options.completed?.()
    notice.value =
      result.status === 'cancelled'
        ? '本轮已停止，未提交页面修改。'
        : result.status === 'completed'
          ? result.revision
            ? '页面已更新并保存为草稿。'
            : '本轮对话已完成。'
          : ''
    error.value = result.error || (result.status === 'interrupted' ? '运行已中断，请重试。' : '')
    activeRun.value = undefined
    await loadConversation()
    await refreshSessions()
  }
  async function reconcile() {
    const run = activeRun.value
    if (!run || disposed) return
    let accepted = false
    try {
      const result = (
        await aiPageBuilderChatApi.getPageChatRun({ name: options.name, runId: run.runId })
      ).data
      // An answer or stream event may have advanced the run while this query was in flight.
      if (activeRun.value !== run) return
      accepted = true
      await settle(result)
    } catch (e) {
      if (!accepted && activeRun.value !== run) return
      if ((e as { response?: { status?: number } }).response?.status === 404) {
        activeRun.value = undefined
        await loadConversation()
        return
      }
      // Keep editing frozen until the authoritative commit outcome is known.
      error.value = `${errorMessage(e)} 请重新检查运行结果。`
    }
  }
  async function send(prompt: string, retry = false) {
    const text = prompt.trim()
    if (!canSend.value || !text || text.length > 8000) return false
    preparing.value = true
    error.value = ''
    notice.value = ''
    try {
      if (retry && historicalAnnotations && !request?.annotations?.length)
        throw new Error('历史批注只保留说明，请重新选择目标后发送。')
      const prepared = await options.prepare()
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
      activeRun.value = { runId: request.runId, status: 'running' }
      lastPrompt.value = text
      chat.clearError()
      await chat.sendMessage({ text })
      await reconcile()
      return !error.value
    } catch (e) {
      streamPreview.discard()
      error.value = errorMessage(e)
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
      error.value = errorMessage(e)
    } finally {
      previewLoading.value = false
    }
  }
  async function resume() {
    if (!canResume.value) return false
    preparing.value = true
    error.value = ''
    notice.value = ''
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
      activeRun.value = { runId: request.runId, status: 'running' }
      streamPreview.seedBaseline(saved.document)
      lastPrompt.value = request.prompt
      chat.clearError()
      await chat.sendMessage({ text: '继续生成' })
      await reconcile()
      return !error.value
    } catch (e) {
      error.value = errorMessage(e)
      if (activeRun.value) await reconcile()
      return false
    } finally {
      preparing.value = false
    }
  }
  async function answerQuestion(questionId: string, answers: PageChatAnswerItem[]) {
    const run = activeRun.value
    const question = pendingQuestion.value
    if (answering.value || !waitingForUser.value || !run || question?.id !== questionId)
      return false
    answering.value = true
    answerError.value = ''
    clearTimeout(polling)
    try {
      const result = (
        await aiPageBuilderChatApi.answerPageChatQuestion({
          name: options.name,
          runId: run.runId,
          questionId,
          pageChatAnswerRequest: { answers },
        })
      ).data
      if (disposed) return false
      rememberQuestion({ ...question, answers })
      await settle(result)
      return true
    } catch (e) {
      if (disposed) return false
      answerError.value = errorMessage(e)
      // A lost response may already have resumed the run. Query before permitting a retry.
      await reconcile()
      if (!waitingForUser.value || pendingQuestion.value?.id !== questionId) answerError.value = ''
      return false
    } finally {
      answering.value = false
    }
  }
  async function stop() {
    if (!activeRun.value) return
    streamPreview.discard()
    try {
      const result = (
        await aiPageBuilderChatApi.cancelPageChatRun({
          name: options.name,
          runId: activeRun.value.runId,
        })
      ).data
      if (result.status !== 'running' && result.status !== 'committing') chat.stop()
      await settle(result)
    } catch (e) {
      error.value = errorMessage(e)
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
  function appendSteerMessage(runId: string, text: string, index?: number) {
    const current = [...(chat.messages.value || [])] as UIMessage[]
    const position =
      index ?? current.filter((message) => message.id.startsWith(`${runId}-steer-`)).length
    const id = `${runId}-steer-${position}`
    if (current.some((message) => message.id === id)) return
    const message: UIMessage = {
      id,
      role: 'user',
      parts: [{ type: 'text', id: `${id}-text`, text }],
    }
    const assistant = current.findIndex((item) => item.id === `${runId}-assistant`)
    if (assistant >= 0) current.splice(assistant, 0, message)
    else current.push(message)
    chat.setMessages(current)
  }
  async function steer(text: string) {
    const run = activeRun.value
    const value = text.trim()
    if (!canSteer.value || !run || !value || value.length > 2000) return false
    steering.value = true
    try {
      await aiPageBuilderChatApi.steerPageChatRun({
        name: options.name,
        runId: run.runId,
        pageChatSteerRequest: { text: value },
      })
      if (!disposed) appendSteerMessage(run.runId, value)
      return true
    } catch (e) {
      if (!disposed) error.value = errorMessage(e)
      return false
    } finally {
      steering.value = false
    }
  }
  onScopeDispose(() => {
    disposed = true
    clearTimeout(polling)
    chat.stop()
  })
  return {
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
    preparing,
    activeRun,
    waitingForUser,
    pendingQuestion,
    questions,
    answering,
    answerError,
    answerQuestion,
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
