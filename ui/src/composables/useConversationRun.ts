import { computed, onScopeDispose, shallowRef } from 'vue'
import type {
  PageChatAnswerItem,
  PageChatQuestion,
  PageChatRun,
  PageDetail,
} from '../api/generated'
import { errorMessage } from '../utils/error'

export const activeRunStatuses = ['running', 'committing', 'waiting_for_user']

interface RunTransport {
  get: (runId: string) => Promise<PageChatRun>
  answer: (runId: string, questionId: string, answers: PageChatAnswerItem[]) => Promise<PageChatRun>
  cancel: (runId: string) => Promise<PageChatRun>
}

/** Owns observations of one conversation's run, not the server's transition rules. */
export function useConversationRun(options: {
  transport: RunTransport
  streaming: () => boolean
  stopStream: () => void
  discardPreview: () => void
  refresh: (current: () => boolean) => Promise<void>
  committed: (page: PageDetail) => void
  completed?: () => void
}) {
  const activeRun = shallowRef<PageChatRun>()
  // Proof that the server admitted the run; a local begin() alone can still be aborted.
  const admittedRunId = shallowRef<string>()
  const questions = shallowRef<PageChatQuestion[]>([])
  const error = shallowRef('')
  const notice = shallowRef('')
  const answering = shallowRef(false)
  const answerError = shallowRef('')
  const refreshError = shallowRef('')
  const refreshing = shallowRef(false)
  const applied = new Set<string>()
  const finished = new Set<string>()
  let generation = 0
  let observation = 0
  let refreshSequence = 0
  let disposed = false
  let polling: ReturnType<typeof setTimeout> | undefined

  function current() {
    const epoch = generation
    const version = observation
    return () => !disposed && epoch === generation && version === observation
  }
  function sameRun(runId: string, epoch = generation) {
    return !disposed && epoch === generation && activeRun.value?.runId === runId
  }
  function remember(question: PageChatQuestion) {
    const known = questions.value.find((item) => item.id === question.id)
    // A history snapshot can lag behind an answer received from the stream.
    if (known?.answers?.length && !question.answers?.length) return
    questions.value = known
      ? questions.value.map((item) => (item.id === question.id ? question : item))
      : [...questions.value, question]
  }
  function schedule() {
    clearTimeout(polling)
    if (!disposed && activeRun.value) polling = setTimeout(() => void reconcile(), 1500)
  }
  function reset() {
    generation++
    observation++
    refreshSequence++
    clearTimeout(polling)
    activeRun.value = undefined
    admittedRunId.value = undefined
    questions.value = []
    error.value = ''
    notice.value = ''
    answering.value = false
    answerError.value = ''
    refreshError.value = ''
    refreshing.value = false
  }
  function begin(runId: string) {
    reset()
    activeRun.value = { runId, status: 'running' }
  }
  function restore(run: PageChatRun | undefined, restoredQuestions: PageChatQuestion[]) {
    if (disposed) return
    if (
      !activeRun.value &&
      run &&
      activeRunStatuses.includes(run.status) &&
      !finished.has(run.runId)
    ) {
      begin(run.runId)
      activeRun.value = run
      // A server snapshot means the run was admitted even if this tab never saw it start.
      admittedRunId.value = run.runId
    }
    for (const question of restoredQuestions) remember(question)
    if (activeRun.value?.pendingQuestion) remember(activeRun.value.pendingQuestion)
  }
  function committed(page: PageDetail) {
    if (!activeRun.value || disposed || applied.has(String(page.page.version))) return
    applied.add(String(page.page.version))
    options.committed(page)
  }
  function questionEvent(runId: string, question: PageChatQuestion, answered: boolean) {
    if (!sameRun(runId)) return
    const known = questions.value.find((item) => item.id === question.id)
    if (!answered && known?.answers?.length) return
    remember(question)
    // A late answer to Q1 must not dismiss an already displayed Q2.
    if (!answered || activeRun.value?.pendingQuestion?.id === question.id) {
      activeRun.value = {
        ...activeRun.value!,
        status: answered ? 'running' : 'waiting_for_user',
        pendingQuestion: answered ? undefined : question,
      }
    }
    observation++
    answerError.value = ''
    schedule()
  }
  function streamFailed(cause: unknown) {
    if (disposed || !activeRun.value) return
    options.discardPreview()
    error.value = errorMessage(cause)
  }
  function recovering(message: string) {
    if (!disposed && activeRun.value) notice.value = message
  }
  async function retryRefresh() {
    if (disposed) return
    const valid = current()
    const sequence = ++refreshSequence
    refreshing.value = true
    try {
      await options.refresh(() => valid() && sequence === refreshSequence)
      if (valid() && sequence === refreshSequence) refreshError.value = ''
    } catch (cause) {
      if (valid() && sequence === refreshSequence)
        refreshError.value = `运行结果已确认，但会话刷新失败：${errorMessage(cause)}`
    } finally {
      if (sequence === refreshSequence) refreshing.value = false
    }
  }
  async function settle(result: PageChatRun) {
    observation++
    if (activeRunStatuses.includes(result.status)) {
      activeRun.value = result
      admittedRunId.value = result.runId
      error.value = ''
      if (result.pendingQuestion) remember(result.pendingQuestion)
      notice.value =
        result.status === 'waiting_for_user'
          ? '等待你的回答，提交后助手将继续。'
          : result.status === 'committing'
            ? '正在保存本轮修改…'
            : '助手正在处理页面…'
      // Arm before refreshing: a late history response cannot revive a terminal timer.
      schedule()
      if (!options.streaming()) {
        const valid = current()
        try {
          await options.refresh(valid)
          if (valid()) refreshError.value = ''
        } catch (cause) {
          if (valid()) refreshError.value = `会话刷新失败：${errorMessage(cause)}`
        }
      }
      return
    }
    clearTimeout(polling)
    answerError.value = ''
    options.discardPreview()
    if (result.page) committed(result.page)
    const runId = activeRun.value!.runId
    const first = !finished.has(runId)
    finished.add(runId)
    activeRun.value = undefined
    notice.value =
      result.status === 'cancelled'
        ? '本轮已停止，未提交页面修改。'
        : result.status === 'completed'
          ? result.revision
            ? '页面已更新并保存为草稿。'
            : '本轮对话已完成。'
          : ''
    error.value = result.error || (result.status === 'interrupted' ? '运行已中断，请重试。' : '')
    if (first && result.status === 'completed') options.completed?.()
    await retryRefresh()
  }
  /**
   * Wait until the server exposes the run. The request that starts it may not have been
   * received yet, and aborting before admission discards the run.
   */
  async function awaitAdmission(runId: string) {
    const deadline = Date.now() + 20_000
    while (
      !disposed &&
      activeRun.value?.runId === runId &&
      admittedRunId.value !== runId &&
      Date.now() < deadline
    ) {
      try {
        const result = await options.transport.get(runId)
        if (activeRun.value?.runId === runId && result.runId === runId) admittedRunId.value = runId
        return
      } catch (cause) {
        if ((cause as { response?: { status?: number } }).response?.status !== 404) return
      }
      await new Promise((resolve) => setTimeout(resolve, 300))
    }
  }

  async function reconcile() {
    const run = activeRun.value
    if (!run || disposed) return
    const valid = current()
    try {
      const result = await options.transport.get(run.runId)
      if (!valid()) return
      await settle(result)
    } catch (cause) {
      if (!valid()) return
      if ((cause as { response?: { status?: number } }).response?.status === 404) {
        observation++
        activeRun.value = undefined
        clearTimeout(polling)
        await retryRefresh()
        return
      }
      // An unknown outcome cannot unlock editing or authorize another generation.
      error.value = `${errorMessage(cause)} 请重新检查运行结果。`
    }
  }
  async function answerQuestion(questionId: string, answers: PageChatAnswerItem[]) {
    const run = activeRun.value
    const question = run?.pendingQuestion
    if (answering.value || run?.status !== 'waiting_for_user' || question?.id !== questionId)
      return false
    const epoch = generation
    const valid = current()
    answering.value = true
    answerError.value = ''
    clearTimeout(polling)
    try {
      const result = await options.transport.answer(run.runId, questionId, answers)
      if (!sameRun(run.runId, epoch)) return false
      remember({ ...question, answers })
      if (valid()) await settle(result)
      else schedule()
      return true
    } catch (cause) {
      if (!sameRun(run.runId, epoch)) return false
      answerError.value = errorMessage(cause)
      // A lost response may already have resumed generation; query before retrying.
      await reconcile()
      if (epoch === generation && activeRun.value?.pendingQuestion?.id !== questionId)
        answerError.value = ''
      return false
    } finally {
      if (epoch === generation) answering.value = false
    }
  }
  async function stop() {
    const run = activeRun.value
    if (!run || disposed) return
    options.discardPreview()
    const valid = current()
    const epoch = generation
    try {
      const result = await options.transport.cancel(run.runId)
      if (!sameRun(run.runId, epoch)) return
      if (!valid()) {
        schedule()
        return
      }
      if (result.status !== 'running' && result.status !== 'committing') options.stopStream()
      await settle(result)
    } catch (cause) {
      if (valid()) error.value = errorMessage(cause)
    }
  }
  onScopeDispose(() => {
    reset()
    disposed = true
    options.stopStream()
  })
  return {
    activeRun: computed(() => activeRun.value),
    admitted: computed(() => !!activeRun.value && admittedRunId.value === activeRun.value.runId),
    awaitAdmission,
    questions: computed(() => questions.value),
    waitingForUser: computed(() => activeRun.value?.status === 'waiting_for_user'),
    pendingQuestion: computed(() => activeRun.value?.pendingQuestion),
    error: computed(() => error.value),
    notice: computed(() => notice.value),
    answering: computed(() => answering.value),
    answerError: computed(() => answerError.value),
    refreshError: computed(() => refreshError.value),
    refreshing: computed(() => refreshing.value),
    begin,
    reset,
    restore,
    committed,
    questionEvent,
    streamFailed,
    recovering,
    reconcile,
    answerQuestion,
    stop,
    retryRefresh,
  }
}
