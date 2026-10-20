import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import type { PageChatQuestion, PageChatRun, PageDetail } from '../../api/generated'
import { useConversationRun } from '../useConversationRun'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => (resolve = done))
  return { promise, resolve }
}
const question: PageChatQuestion = {
  id: 'q1',
  createdAt: '2026-01-01T00:00:00Z',
  questions: [{ id: 'style', title: '风格？', options: [], allowCustomAnswer: true }],
}
const waiting: PageChatRun = {
  runId: 'run',
  status: 'waiting_for_user',
  pendingQuestion: question,
}
const page = {
  page: { name: 'page', version: 1, draftRevision: 'revision' },
  document: { html: '<p>saved</p>' },
} as PageDetail
const completed: PageChatRun = {
  runId: 'run',
  status: 'completed',
  page,
  revision: 'revision',
}
let scope = effectScope()
function setup(streaming = true) {
  const options = {
    transport: {
      get: vi.fn<(id: string) => Promise<PageChatRun>>().mockResolvedValue(waiting),
      answer: vi
        .fn<() => Promise<PageChatRun>>()
        .mockResolvedValue({ runId: 'run', status: 'running' }),
      cancel: vi
        .fn<() => Promise<PageChatRun>>()
        .mockResolvedValue({ runId: 'run', status: 'cancelled' }),
    },
    streaming: () => streaming,
    stopStream: vi.fn(),
    discardPreview: vi.fn(),
    refresh: vi.fn<(_: () => boolean) => Promise<void>>().mockResolvedValue(undefined),
    committed: vi.fn(),
    completed: vi.fn(),
  }
  const run = scope.run(() => useConversationRun(options))!
  run.restore(waiting, [question])
  return { run, ...options }
}
beforeEach(() => {
  vi.useFakeTimers()
  scope = effectScope()
})
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})

describe('Conversation Turn observations', () => {
  it('adopts property-only commits with the same content revision and deduplicates each version', () => {
    const { run, committed } = setup()
    run.committed(page)
    const renamed = { ...page, page: { ...page.page, title: 'Renamed', version: 2 } }
    run.committed(renamed)
    run.committed(renamed)
    expect(committed).toHaveBeenCalledTimes(2)
    expect(committed).toHaveBeenLastCalledWith(renamed)
  })

  it('ignores a waiting poll captured before an answer and polls the advanced run', async () => {
    const { run, transport } = setup()
    const poll = deferred<PageChatRun>()
    transport.get.mockReturnValueOnce(poll.promise)
    const pending = run.reconcile()
    await run.answerQuestion('q1', [{ questionId: 'style', text: '简洁' }])
    poll.resolve(waiting)
    await pending
    expect(run.waitingForUser.value).toBe(false)
    transport.get.mockResolvedValue({ runId: 'run', status: 'running' })
    await vi.advanceTimersByTimeAsync(1500)
    expect(transport.get).toHaveBeenCalledTimes(2)
  })
  it('keeps the next streamed question when an earlier answer response arrives late', async () => {
    const { run, transport } = setup()
    const answer = deferred<PageChatRun>()
    transport.answer.mockReturnValueOnce(answer.promise)
    const pending = run.answerQuestion('q1', [{ questionId: 'style', text: '简洁' }])
    const next = { ...question, id: 'q2' }
    run.questionEvent('run', next, false)
    answer.resolve({ runId: 'run', status: 'running' })
    expect(await pending).toBe(true)
    expect(run.pendingQuestion.value?.id).toBe('q2')
    expect(run.waitingForUser.value).toBe(true)
    expect(run.questions.value.map((q) => q.id)).toEqual(['q1', 'q2'])
    expect(run.questions.value[0]?.answers).toHaveLength(1)
  })
  it('updates answers in place without dismissing a newer question or reverting to unanswered history', () => {
    const { run } = setup()
    const next = { ...question, id: 'q2' }
    run.questionEvent('run', next, false)
    const answered = { ...question, answers: [{ questionId: 'style', text: '简洁' }] }
    run.questionEvent('run', answered, true)
    run.restore(waiting, [question, next])
    run.questionEvent('run', question, false)
    expect(run.pendingQuestion.value?.id).toBe('q2')
    expect(run.questions.value.map((q) => q.id)).toEqual(['q1', 'q2'])
    expect(run.questions.value[0]?.answers).toEqual(answered.answers)
  })
  it('ignores an old cancellation response after a new run has started', async () => {
    const { run, transport, stopStream, committed } = setup()
    const cancel = deferred<PageChatRun>()
    transport.cancel.mockReturnValueOnce(cancel.promise)
    const pending = run.stop()
    transport.get.mockResolvedValueOnce(completed)
    await run.reconcile()
    run.begin('next-run')
    cancel.resolve({ runId: 'run', status: 'cancelled' })
    await pending
    expect(run.activeRun.value?.runId).toBe('next-run')
    expect(stopStream).not.toHaveBeenCalled()
    expect(committed).toHaveBeenCalledTimes(1)
  })
  it('keeps tracking a cancellation result that is already committing', async () => {
    const { run, transport, stopStream, committed } = setup()
    transport.cancel.mockResolvedValue({ runId: 'run', status: 'committing' })
    await run.stop()
    expect(run.activeRun.value?.status).toBe('committing')
    expect(stopStream).not.toHaveBeenCalled()
    transport.get.mockResolvedValue(completed)
    await vi.advanceTimersByTimeAsync(1500)
    expect(committed).toHaveBeenCalledOnce()
    expect(run.activeRun.value).toBeUndefined()
  })
  it('adopts a revision and notifies completion once across stream, poll and answer results', async () => {
    const { run, transport, committed, completed: onCompleted, refresh } = setup()
    const answer = deferred<PageChatRun>()
    transport.answer.mockReturnValueOnce(answer.promise)
    const pending = run.answerQuestion('q1', [{ questionId: 'style', text: '简洁' }])
    run.committed(page)
    run.committed(page)
    transport.get.mockResolvedValueOnce(completed)
    await run.reconcile()
    answer.resolve(completed)
    await pending
    expect(committed).toHaveBeenCalledExactlyOnceWith(page)
    expect(onCompleted).toHaveBeenCalledOnce()
    expect(refresh).toHaveBeenCalledOnce()
  })
  it('does not recreate polling after a history refresh finishes behind the terminal result', async () => {
    const { run, transport, refresh } = setup(false)
    const history = deferred<void>()
    refresh.mockReturnValueOnce(history.promise)
    // Recovered runs have no live stream, so each poll refreshes their conversation.
    const pending = run.reconcile()
    await vi.advanceTimersByTimeAsync(0)
    transport.get.mockResolvedValue(completed)
    await run.reconcile()
    history.resolve()
    await pending
    await vi.advanceTimersByTimeAsync(3000)
    expect(transport.get).toHaveBeenCalledTimes(2)
    expect(run.activeRun.value).toBeUndefined()
  })
  it('invalidates terminal refresh work after switching conversations or starting another run', async () => {
    const { run, transport, refresh } = setup()
    const history = deferred<void>()
    refresh.mockReturnValueOnce(history.promise)
    transport.get.mockResolvedValueOnce(completed)
    const pending = run.reconcile()
    await vi.advanceTimersByTimeAsync(0)
    const valid = refresh.mock.calls[0]![0]
    expect(valid()).toBe(true)
    run.reset()
    run.begin('next')
    expect(valid()).toBe(false)
    history.resolve()
    await pending
    expect(run.activeRun.value?.runId).toBe('next')
  })
  it('keeps the editor locked when the commit outcome cannot be queried', async () => {
    const { run, transport, committed } = setup()
    transport.get.mockRejectedValue(new Error('offline'))
    await run.reconcile()
    expect(run.activeRun.value?.runId).toBe('run')
    expect(run.error.value).toContain('检查运行结果')
    expect(committed).not.toHaveBeenCalled()
  })
  it('discards pending command results and timers on disposal', async () => {
    const { run, transport, committed } = setup()
    const poll = deferred<PageChatRun>()
    transport.get.mockReturnValueOnce(poll.promise)
    const pending = run.reconcile()
    scope.stop()
    poll.resolve(completed)
    await pending
    await vi.advanceTimersByTimeAsync(3000)
    expect(committed).not.toHaveBeenCalled()
    expect(transport.get).toHaveBeenCalledOnce()
  })
})
