import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, shallowRef } from 'vue'
import { usePageChat } from '../usePageChat'
import type { UIMessage } from '@halo-dev/ai-foundation-sdk'
import type { PageDetail, PageChatQuestion } from '../../api/generated'
const api = vi.hoisted(() => ({
  ai: {
    getAiPageBuilderAiStatus: vi.fn<(...args: unknown[]) => unknown>(),
  },
  chat: {
    listPageChatSessions: vi.fn<(...args: unknown[]) => unknown>(),
    createPageChatSession: vi.fn<(...args: unknown[]) => unknown>(),
    deletePageChatSession: vi.fn<(...args: unknown[]) => unknown>(),
    getPageChatCheckpoint: vi.fn<(...args: unknown[]) => unknown>(),
    validatePageContent: vi.fn<(...args: unknown[]) => unknown>(),
    getPageChat: vi.fn<(...args: unknown[]) => unknown>(),
    getPageChatRun: vi.fn<(...args: unknown[]) => unknown>(),
    cancelPageChatRun: vi.fn<(...args: unknown[]) => unknown>(),
    answerPageChatQuestion: vi.fn<(...args: unknown[]) => unknown>(),
    steerPageChatRun: vi.fn<(...args: unknown[]) => unknown>(),
  },
}))
interface MockChatOptions {
  onError: (error: Error) => void
  onData: (part: { type: string; data: unknown }) => void
  sendAutomaticallyWhen: () => boolean
}
interface MockTransportOptions {
  prepareSendMessagesRequest: () => { body: Record<string, unknown> }
}
const sdk = vi.hoisted(() => ({
  options: {} as MockChatOptions,
  transport: {} as MockTransportOptions,
  send: vi.fn<(...args: unknown[]) => unknown>(),
  stop: vi.fn<(...args: unknown[]) => unknown>(),
  setMessages: vi.fn<(...args: unknown[]) => unknown>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderAiApi: api.ai,
  aiPageBuilderChatApi: api.chat,
  chatUrl: (name: string) => `/pages/${name}/chat`,
  prepareChatRequest: (_name: string, request: Record<string, unknown>) => ({ body: request }),
}))
vi.mock('../../utils/error', () => ({
  errorMessage: (e: Error) => e.message,
}))
vi.mock('../../api/stream', () => ({
  authenticatedChatFetch: vi.fn<(...args: unknown[]) => unknown>(),
}))
vi.mock('@halo-dev/ai-foundation-sdk', () => ({
  DefaultChatTransport: class {
    constructor(options: MockTransportOptions) {
      sdk.transport = options
    }
  },
  useChat: (options: MockChatOptions) => {
    sdk.options = options
    const messages = shallowRef<UIMessage[]>([])
    sdk.setMessages.mockImplementation((value) => {
      messages.value = value as UIMessage[]
    })
    return {
      messages,
      status: shallowRef('ready'),
      isLoading: shallowRef(false),
      sendMessage: sdk.send,
      stop: sdk.stop,
      setMessages: sdk.setMessages,
      clearError: vi.fn<(...args: unknown[]) => unknown>(),
    }
  },
}))
let scope = effectScope()
const committed = vi.fn<(...args: unknown[]) => unknown>()
const prepare = vi.fn<() => Promise<number | import('../../editor/annotations').PreparedPage>>()
const page = {
  page: { name: 'one', draftRevision: 'r2' },
  document: { html: '<p>AI</p>' },
} as PageDetail
beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  localStorage.clear()
  scope = effectScope()
  api.ai.getAiPageBuilderAiStatus.mockResolvedValue({ data: { available: true, message: 'Ready' } })
  api.chat.listPageChatSessions.mockResolvedValue({
    data: { items: [{ sessionId: 'one', title: 'Default', version: 3 }] },
  })
  api.chat.getPageChat.mockResolvedValue({
    data: { sessionId: 'one', turns: [], activeRun: null, hasMore: false },
  })
  api.chat.getPageChatRun.mockResolvedValue({
    data: { runId: 'run', status: 'completed', page, revision: 'r2' },
  })
  prepare.mockResolvedValue(8)
  sdk.send.mockResolvedValue(undefined)
})
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})
function setup(extra: Partial<Parameters<typeof usePageChat>[0]> = {}) {
  const state = scope.run(() => usePageChat({ name: 'one', prepare, committed, ...extra }))!
  state.modelName.value = 'tool-model'
  return state
}
const question: PageChatQuestion = {
  id: 'question-batch',
  createdAt: '2026-01-01T00:00:00Z',
  questions: [{ id: 'style', title: '风格？', options: [], allowCustomAnswer: true }],
}
const waitingRun = { runId: 'waiting', status: 'waiting_for_user', pendingQuestion: question }
function waitingConversation() {
  api.chat.listPageChatSessions.mockResolvedValue({
    data: { items: [{ sessionId: 'busy', activeRun: waitingRun }] },
  })
  api.chat.getPageChat.mockResolvedValue({
    data: {
      sessionId: 'busy',
      activeRun: waitingRun,
      turns: [
        {
          runId: 'waiting',
          prompt: '设计页面',
          text: '',
          tools: [],
          status: 'waiting_for_user',
          questions: [question],
        },
      ],
    },
  })
  api.chat.getPageChatRun.mockResolvedValue({ data: waitingRun })
}
const renamedPart = (toolCallId: string) => ({
  type: 'tool-rename_session',
  toolCallId,
  toolName: 'rename_session',
  state: 'output-available',
  input: {},
  output: { valid: true, renamed: true },
})

describe('persistent page chat', () => {
  it.each(['SSE first', 'HTTP first'])(
    'shows each accepted steer once with %s, preserving intentionally repeated text',
    async (order) => {
      waitingConversation()
      const state = setup()
      await state.recover()
      for (let index = 0; index < 2; index++) {
        let accept!: (value: unknown) => void
        api.chat.steerPageChatRun.mockImplementationOnce(
          () => new Promise((resolve) => (accept = resolve)),
        )
        api.chat.getPageChat.mockResolvedValue({
          data: {
            sessionId: 'busy',
            turns: [{ runId: 'waiting', steers: Array(index + 1).fill('保留留白') }],
          },
        })
        const event = {
          type: 'data-steer',
          data: { runId: 'waiting', index, text: '保留留白' },
        }
        const pending = state.steer('保留留白')
        if (order === 'SSE first') sdk.options.onData(event)
        accept({ data: waitingRun })
        expect(await pending).toBe(true)
        // HTTP success must also work when the stream is disconnected.
        expect(
          state.messages.value.filter((message) => message.id.startsWith('waiting-steer-')),
        ).toHaveLength(index + 1)
        if (order === 'HTTP first') sdk.options.onData(event)
        sdk.options.onData(event)
        expect(
          state.messages.value
            .filter((message) => message.id.startsWith('waiting-steer-'))
            .map((message) => message.id),
        ).toEqual(Array.from({ length: index + 1 }, (_, i) => `waiting-steer-${i}`))
      }
    },
  )
  it('merges server-indexed steers from another tab without replacing live assistant content', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    sdk.setMessages([
      {
        id: 'waiting-assistant',
        role: 'assistant',
        parts: [{ type: 'text', text: 'Latest streamed text' }],
        metadata: { runId: 'waiting' },
      },
    ])
    api.chat.steerPageChatRun.mockResolvedValue({ data: waitingRun })
    sdk.options.onData({
      type: 'data-steer',
      data: { runId: 'waiting', index: 1, text: '当前指令' },
    })
    api.chat.getPageChat.mockResolvedValue({
      data: {
        sessionId: 'busy',
        turns: [
          { runId: 'waiting', text: 'Stale history', steers: ['另一标签的指令', '当前指令'] },
        ],
      },
    })
    expect(await state.steer('当前指令')).toBe(true)
    expect(state.messages.value.map((message) => message.id)).toEqual([
      'waiting-steer-0',
      'waiting-steer-1',
      'waiting-assistant',
    ])
    expect(state.messages.value[2]?.parts).toEqual([{ type: 'text', text: 'Latest streamed text' }])
  })
  it('inserts a live steer before the streaming assistant message', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    // The live assistant id comes from the server stream, not `${runId}-assistant`.
    sdk.setMessages([
      { id: 'prompt', role: 'user', parts: [{ type: 'text', id: 'p', text: '设计页面' }] },
      {
        id: '9f3c-live',
        role: 'assistant',
        parts: [{ type: 'text', id: 't', text: '第一段' }],
        metadata: { runId: 'waiting' },
      },
    ])
    sdk.setMessages.mockClear()
    sdk.options.onData({
      type: 'data-steer',
      data: { runId: 'waiting', index: 0, text: '补充要求' },
    })
    const raw = sdk.setMessages.mock.lastCall?.[0] as UIMessage[]
    expect(raw.map((message) => message.id)).toEqual(['prompt', 'waiting-steer-0', '9f3c-live'])
  })
  it('does not report an accepted steer as failed when history refresh fails', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    api.chat.steerPageChatRun.mockResolvedValue({ data: waitingRun })
    api.chat.getPageChat.mockRejectedValueOnce(new Error('history unavailable'))
    expect(await state.steer('已接受的指令')).toBe(true)
    expect(state.error.value).toBe('')
    sdk.options.onData({
      type: 'data-steer',
      data: { runId: 'waiting', index: 0, text: '已接受的指令' },
    })
    expect(state.messages.value.filter((message) => message.id === 'waiting-steer-0')).toHaveLength(
      1,
    )
    expect(api.chat.steerPageChatRun).toHaveBeenCalledTimes(1)
  })
  it('reports a rejected steer in the current run without adding a message', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    const before = state.messages.value
    api.chat.steerPageChatRun.mockRejectedValueOnce(new Error('Steer rejected'))
    expect(await state.steer('未接受的指令')).toBe(false)
    expect(state.error.value).toBe('Steer rejected')
    expect(state.messages.value).toEqual(before)
    expect(state.canSteer.value).toBe(true)
  })
  it.each(['success', 'failure', 'history'])(
    'ignores a late steer %s after switching conversations',
    async (response) => {
      waitingConversation()
      const state = setup()
      await state.recover()
      let resolve!: (value: unknown) => void
      let reject!: (error: Error) => void
      const deferred = new Promise((accept, fail) => {
        resolve = accept
        reject = fail
      })
      if (response === 'history') {
        api.chat.steerPageChatRun.mockResolvedValue({ data: waitingRun })
        api.chat.getPageChat.mockReturnValueOnce(deferred)
      } else {
        api.chat.steerPageChatRun.mockReturnValueOnce(deferred)
      }
      const pending = state.steer('Old instruction')
      await vi.advanceTimersByTimeAsync(0)
      api.chat.getPageChatRun.mockResolvedValueOnce({
        data: { runId: 'waiting', status: 'completed' },
      })
      api.chat.getPageChat.mockResolvedValue({
        data: { sessionId: 'busy', turns: [], activeRun: null },
      })
      await state.reconcile()
      api.chat.getPageChat.mockResolvedValue({
        data: { sessionId: 'other', turns: [], activeRun: null },
      })
      await state.selectSession('other')
      if (response === 'failure') reject(new Error('Old failure'))
      else
        resolve({
          data: {
            ...waitingRun,
            sessionId: 'busy',
            turns: [{ runId: 'waiting', steers: ['Old instruction'] }],
          },
        })
      // A stale failure must not restore the old prompt into the new conversation.
      expect(await pending).toBe(true)
      expect(state.messages.value).toEqual([])
      expect(state.error.value).toBe('')
      expect(state.sessionId.value).toBe('other')
    },
  )
  it('restores first-run questions when the server has no archived turns', async () => {
    waitingConversation()
    api.chat.getPageChat.mockResolvedValue({
      data: { sessionId: 'busy', activeRun: waitingRun, turns: [] },
    })
    const state = setup()
    await state.recover()
    expect(state.messages.value).toHaveLength(1)
    expect(state.messages.value[0]).toMatchObject({
      role: 'assistant',
      metadata: { runId: 'waiting' },
    })
    expect(state.questions.value).toEqual([question])
    await state.reconcile()
    expect(state.messages.value).toHaveLength(1)
    expect(state.waitingForUser.value).toBe(true)
  })
  it('keeps the current question separate from a previous completed assistant message', async () => {
    waitingConversation()
    api.chat.getPageChat.mockResolvedValue({
      data: {
        sessionId: 'busy',
        activeRun: waitingRun,
        turns: [
          {
            runId: 'old',
            prompt: '旧请求',
            text: '旧回答',
            tools: [],
            status: 'completed',
            questions: [{ ...question, id: 'old-batch' }],
          },
        ],
      },
    })
    const state = setup()
    await state.recover()
    expect(state.messages.value).toHaveLength(3)
    expect(state.messages.value[1]?.metadata).toMatchObject({ runId: 'old' })
    expect(state.messages.value[2]?.metadata).toMatchObject({ runId: 'waiting' })
    expect(state.questions.value).toEqual([question])
  })
  it('drops checkpoint-inherited questions when a resumed run asks again', async () => {
    waitingConversation()
    const inherited: PageChatQuestion = {
      ...question,
      id: 'inherited',
      createdAt: '2025-12-31T00:00:00Z',
      answers: [{ questionId: 'style', optionId: 'minimal' }],
      answeredAt: '2025-12-31T00:00:01Z',
    }
    const fresh: PageChatQuestion = { ...question, id: 'fresh', createdAt: '2026-01-01T00:01:00Z' }
    const resumedRun = { ...waitingRun, pendingQuestion: fresh }
    api.chat.listPageChatSessions.mockResolvedValue({
      data: { items: [{ sessionId: 'busy', activeRun: resumedRun }] },
    })
    api.chat.getPageChatRun.mockResolvedValue({ data: resumedRun })
    api.chat.getPageChat.mockResolvedValue({
      data: {
        sessionId: 'busy',
        activeRun: resumedRun,
        turns: [
          {
            runId: 'waiting',
            prompt: '设计页面',
            text: '',
            tools: [],
            status: 'waiting_for_user',
            createdAt: '2026-01-01T00:00:00Z',
            questions: [inherited, fresh],
          },
        ],
      },
    })
    const state = setup()
    await state.recover()
    expect(state.questions.value.map((item) => item.id)).toEqual(['fresh'])
    expect(state.messages.value?.[1]?.metadata).toMatchObject({ questions: [fresh] })
  })
  it('recovers a waiting question and keeps editing and session actions locked', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    expect(state.waitingForUser.value).toBe(true)
    expect(state.running.value).toBe(true)
    expect(state.sessionBusy.value).toBe(true)
    expect(state.canSend.value).toBe(false)
    expect(state.pendingQuestion.value).toEqual(question)
    expect(state.questions.value).toEqual([question])
    expect(state.notice.value).toContain('等待你的回答')
    await state.selectSession('other')
    await state.createSession()
    expect(state.sessionId.value).toBe('busy')
    expect(api.chat.createPageChatSession).not.toHaveBeenCalled()
    expect(state.messages.value?.[1]?.metadata).toMatchObject({ questions: [question] })
  })

  it('refreshes the session list after every completed rename, not only the first', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    api.chat.listPageChatSessions.mockClear()
    sdk.setMessages([{ id: 'm1', role: 'assistant', parts: [renamedPart('r1')] }])
    await nextTick()
    expect(api.chat.listPageChatSessions).toHaveBeenCalledTimes(1)
    sdk.setMessages([
      { id: 'm1', role: 'assistant', parts: [renamedPart('r1')] },
      { id: 'm2', role: 'assistant', parts: [renamedPart('r2')] },
    ])
    await nextTick()
    expect(api.chat.listPageChatSessions).toHaveBeenCalledTimes(2)
  })
  it('keeps an older successful refresh when a newer refresh fails', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    let resolveFirst!: (value: unknown) => void
    const first = new Promise((resolve) => {
      resolveFirst = resolve
    })
    api.chat.listPageChatSessions
      .mockReturnValueOnce(first)
      .mockRejectedValueOnce(new Error('Offline'))
    sdk.setMessages([{ id: 'm1', role: 'assistant', parts: [renamedPart('r1')] }])
    await nextTick()
    sdk.setMessages([
      { id: 'm1', role: 'assistant', parts: [renamedPart('r1')] },
      { id: 'm2', role: 'assistant', parts: [renamedPart('r2')] },
    ])
    await nextTick()
    resolveFirst({ data: { items: [{ sessionId: 'renamed', version: 4 }] } })
    await first
    await nextTick()
    expect(state.sessions.value.map((session) => session.sessionId)).toEqual(['renamed'])
  })
  it('submits once through the generated endpoint and polls the resumed run to commit', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    let finish!: (value: unknown) => void
    api.chat.answerPageChatQuestion.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const answers = [{ questionId: 'style', text: '简洁' }]
    const submitted = state.answerQuestion(question.id, answers)
    expect(state.answering.value).toBe(true)
    expect(await state.answerQuestion(question.id, answers)).toBe(false)
    expect(api.chat.answerPageChatQuestion).toHaveBeenCalledTimes(1)
    expect(api.chat.answerPageChatQuestion).toHaveBeenCalledWith({
      name: 'one',
      runId: 'waiting',
      questionId: question.id,
      pageChatAnswerRequest: { answers },
    })
    finish({ data: { runId: 'waiting', status: 'running' } })
    expect(await submitted).toBe(true)
    expect(state.answering.value).toBe(false)
    expect(state.waitingForUser.value).toBe(false)
    expect(state.running.value).toBe(true)
    expect(state.questions.value[0]?.answers).toEqual(answers)
    expect(sdk.send).not.toHaveBeenCalled()
    api.chat.getPageChatRun.mockResolvedValue({
      data: { runId: 'waiting', status: 'completed', page },
    })
    api.chat.getPageChat.mockResolvedValue({
      data: { sessionId: 'busy', turns: [], activeRun: null },
    })
    await vi.advanceTimersByTimeAsync(1500)
    expect(committed).toHaveBeenCalledWith(page)
    expect(state.running.value).toBe(false)
  })
  it('keeps a failed answer retryable and reconciles a lost successful response', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    api.chat.answerPageChatQuestion.mockRejectedValue(new Error('离线'))
    const answers = [{ questionId: 'style', text: '简洁' }]
    expect(await state.answerQuestion(question.id, answers)).toBe(false)
    expect(state.answerError.value).toBe('离线')
    expect(state.pendingQuestion.value).toEqual(question)
    expect(state.answering.value).toBe(false)
    api.chat.getPageChatRun.mockResolvedValue({ data: { runId: 'waiting', status: 'running' } })
    expect(await state.answerQuestion(question.id, answers)).toBe(false)
    expect(state.answerError.value).toBe('')
    expect(state.waitingForUser.value).toBe(false)
    expect(await state.answerQuestion(question.id, answers)).toBe(false)
    expect(api.chat.answerPageChatQuestion).toHaveBeenCalledTimes(2)
  })
  it('handles live question events and lets the user cancel a waiting run', async () => {
    api.chat.getPageChat.mockResolvedValue({
      data: { sessionId: 'one', turns: [], activeRun: { runId: 'waiting', status: 'running' } },
    })
    api.chat.getPageChatRun.mockResolvedValue({ data: { runId: 'waiting', status: 'running' } })
    const state = setup()
    await state.recover()
    sdk.options.onData({ type: 'data-user-question', data: { runId: 'waiting', question } })
    expect(state.waitingForUser.value).toBe(true)
    expect(state.pendingQuestion.value).toEqual(question)
    api.chat.cancelPageChatRun.mockResolvedValue({
      data: { runId: 'waiting', status: 'cancelled' },
    })
    api.chat.getPageChat.mockResolvedValue({
      data: { sessionId: 'one', turns: [], activeRun: null },
    })
    await state.stop()
    expect(state.running.value).toBe(false)
    expect(state.pendingQuestion.value).toBeUndefined()
    expect(sdk.stop).toHaveBeenCalledTimes(1)
  })
  it('keeps a newer streamed question when the previous answer response arrives late', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    let answer!: (value: unknown) => void
    api.chat.answerPageChatQuestion.mockImplementationOnce(
      () => new Promise((resolve) => (answer = resolve)),
    )
    const pending = state.answerQuestion(question.id, [{ questionId: 'style', text: '简洁' }])
    const next = { ...question, id: 'next-question' }
    sdk.options.onData({ type: 'data-user-question', data: { runId: 'waiting', question: next } })
    answer({ data: { runId: 'waiting', status: 'running' } })
    await pending
    expect(state.pendingQuestion.value?.id).toBe('next-question')
    expect(state.waitingForUser.value).toBe(true)
  })
  it('does not apply terminal history arriving after a new generation starts', async () => {
    waitingConversation()
    const state = setup()
    await state.recover()
    let history!: (value: unknown) => void
    api.chat.getPageChat.mockImplementationOnce(() => new Promise((resolve) => (history = resolve)))
    api.chat.getPageChatRun.mockResolvedValueOnce({
      data: { runId: 'waiting', status: 'completed', page },
    })
    const settling = state.reconcile()
    await vi.advanceTimersByTimeAsync(0)
    let finish!: () => void
    sdk.send.mockImplementationOnce(() => new Promise<void>((resolve) => (finish = resolve)))
    const sending = state.send('Next generation')
    await vi.advanceTimersByTimeAsync(0)
    const nextRunId = state.activeRun.value?.runId
    expect(nextRunId).toBeTruthy()
    expect(nextRunId).not.toBe('waiting')
    history({
      data: {
        sessionId: 'busy',
        turns: [{ runId: 'stale', prompt: 'Old', text: 'Old', tools: [], status: 'completed' }],
      },
    })
    await settling
    expect(state.activeRun.value?.runId).toBe(nextRunId)
    expect(state.messages.value.some((message) => message.id === 'stale-assistant')).toBe(false)
    api.chat.getPageChatRun.mockResolvedValue({ data: { runId: nextRunId, status: 'running' } })
    finish()
    await sending
  })
  it('loads server history and sends only the bound prompt/version without automatic tool loops', async () => {
    const state = setup()
    await state.recover()
    expect(api.chat.getPageChat).toHaveBeenCalledWith({
      name: 'one',
      sessionId: 'one',
      size: 20,
    })
    expect(await state.send('Make it calm')).toBe(true)
    expect(prepare).toHaveBeenCalledTimes(1)
    expect(sdk.transport.prepareSendMessagesRequest().body).toMatchObject({
      version: 8,
      prompt: 'Make it calm',
    })
    expect(sdk.options.sendAutomaticallyWhen()).toBe(false)
    expect(committed).toHaveBeenCalledTimes(1)
  })
  it('switches conversation without restoring page revisions and binds subsequent prompts', async () => {
    const state = setup()
    await state.recover()
    api.chat.getPageChat.mockResolvedValueOnce({
      data: { sessionId: 'two', turns: [], hasMore: false },
    })
    await state.selectSession('two')
    expect(state.sessionId.value).toBe('two')
    expect(committed).not.toHaveBeenCalled()
    await state.send('Continue here')
    expect(sdk.transport.prepareSendMessagesRequest().body.sessionId).toBe('two')
  })
  it.each(['select', 'create'])(
    'restores the last session after %s and reopening',
    async (action) => {
      api.chat.listPageChatSessions.mockResolvedValue({
        data: { items: [{ sessionId: 'one' }, { sessionId: 'two' }] },
      })
      const state = setup()
      await state.recover()
      api.chat.getPageChat.mockResolvedValue({
        data: {
          sessionId: 'two',
          turns: [
            { runId: 'old', prompt: 'Continue', text: 'Done', tools: [], status: 'completed' },
          ],
          hasMore: false,
        },
      })
      if (action === 'create') {
        api.chat.createPageChatSession.mockResolvedValue({ data: { sessionId: 'two' } })
        await state.createSession()
      } else {
        await state.selectSession('two')
      }
      scope.stop()
      scope = effectScope()
      const reopened = setup()
      await reopened.recover()
      expect(api.chat.getPageChat).toHaveBeenLastCalledWith({
        name: 'one',
        sessionId: 'two',
        size: 20,
      })
      expect(reopened.sessionId.value).toBe('two')
      expect(reopened.messages.value.map((message) => message.id)).toEqual([
        'old-user',
        'old-assistant',
      ])
    },
  )
  it('keeps remembered sessions separate for each page', async () => {
    localStorage.setItem('ai-page-builder:last-session:one', 'two')
    api.chat.listPageChatSessions.mockResolvedValue({
      data: { items: [{ sessionId: 'other-page' }] },
    })
    api.chat.getPageChat.mockResolvedValue({
      data: { sessionId: 'other-page', turns: [], hasMore: false },
    })
    await setup({ name: 'other-page' }).recover()
    expect(api.chat.getPageChat).toHaveBeenLastCalledWith({
      name: 'other-page',
      sessionId: 'other-page',
      size: 20,
    })
    expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('two')
    expect(localStorage.getItem('ai-page-builder:last-session:other-page')).toBe('other-page')
  })
  it('falls back to the default session when the remembered session no longer exists', async () => {
    localStorage.setItem('ai-page-builder:last-session:one', 'deleted')
    const state = setup()
    await state.recover()
    expect(api.chat.getPageChat).toHaveBeenLastCalledWith({
      name: 'one',
      sessionId: 'one',
      size: 20,
    })
    expect(state.error.value).toBe('')
    expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('one')
  })
  it('falls back when the remembered session is deleted after listing sessions', async () => {
    localStorage.setItem('ai-page-builder:last-session:one', 'two')
    api.chat.listPageChatSessions.mockResolvedValueOnce({
      data: { items: [{ sessionId: 'one' }, { sessionId: 'two' }] },
    })
    api.chat.getPageChat.mockRejectedValueOnce({ response: { status: 404 } })
    const state = setup()
    await state.recover()
    expect(api.chat.listPageChatSessions).toHaveBeenCalledTimes(2)
    expect(api.chat.getPageChat.mock.calls).toEqual([
      [{ name: 'one', sessionId: 'two', size: 20 }],
      [{ name: 'one', sessionId: 'one', size: 20 }],
    ])
    expect(state.sessions.value.map((session) => session.sessionId)).toEqual(['one'])
    expect(state.sessionId.value).toBe('one')
    expect(state.canSend.value).toBe(true)
    expect(state.error.value).toBe('')
    expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('one')
  })
  it.each(['running', 'committing', 'waiting_for_user'])(
    'prioritizes a newly discovered %s session after the remembered session disappears',
    async (status) => {
      localStorage.setItem('ai-page-builder:last-session:one', 'two')
      const activeRun = { runId: 'r', status }
      api.chat.listPageChatSessions
        .mockResolvedValueOnce({ data: { items: [{ sessionId: 'two' }] } })
        .mockResolvedValue({
          data: { items: [{ sessionId: 'one' }, { sessionId: 'busy', activeRun }] },
        })
      api.chat.getPageChat.mockRejectedValueOnce({ response: { status: 404 } }).mockResolvedValue({
        data: { sessionId: 'busy', turns: [], hasMore: false, activeRun },
      })
      api.chat.getPageChatRun.mockResolvedValue({ data: activeRun })
      const state = setup()
      await state.recover()
      expect(api.chat.getPageChat).toHaveBeenLastCalledWith({
        name: 'one',
        sessionId: 'busy',
        size: 20,
      })
      expect(state.sessionId.value).toBe('busy')
      expect(state.running.value).toBe(true)
      expect(state.error.value).toBe('')
      expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('busy')
    },
  )
  it.each([undefined, 403, 500])(
    'does not retry a session recovery error with status %s',
    async (status) => {
      localStorage.setItem('ai-page-builder:last-session:one', 'two')
      api.chat.listPageChatSessions.mockResolvedValue({
        data: { items: [{ sessionId: 'one' }, { sessionId: 'two' }] },
      })
      api.chat.getPageChat.mockRejectedValueOnce({ message: 'Unavailable', response: { status } })
      const state = setup()
      await state.recover()
      expect(api.chat.listPageChatSessions).toHaveBeenCalledTimes(1)
      expect(api.chat.getPageChat).toHaveBeenCalledTimes(1)
      expect(state.error.value).toBe('Unavailable')
      expect(state.canSend.value).toBe(false)
      expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('two')
    },
  )
  it('does not retry a missing default session', async () => {
    api.chat.getPageChat.mockRejectedValue({ message: 'Page missing', response: { status: 404 } })
    const state = setup()
    await state.recover()
    expect(api.chat.listPageChatSessions).toHaveBeenCalledTimes(1)
    expect(api.chat.getPageChat).toHaveBeenCalledTimes(1)
    expect(state.error.value).toBe('Page missing')
  })
  it('stops after one recovery retry if the replacement session also disappears', async () => {
    localStorage.setItem('ai-page-builder:last-session:one', 'two')
    api.chat.listPageChatSessions.mockResolvedValueOnce({
      data: { items: [{ sessionId: 'one' }, { sessionId: 'two' }] },
    })
    api.chat.getPageChat.mockRejectedValue({ message: 'Not found', response: { status: 404 } })
    const state = setup()
    await state.recover()
    expect(api.chat.listPageChatSessions).toHaveBeenCalledTimes(2)
    expect(api.chat.getPageChat).toHaveBeenCalledTimes(2)
    expect(state.error.value).toBe('Not found')
    expect(state.canSend.value).toBe(false)
    expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('two')
  })
  it('keeps the last successful selection when switching fails', async () => {
    const state = setup()
    await state.recover()
    api.chat.getPageChat.mockRejectedValueOnce(new Error('Offline'))
    await state.selectSession('two')
    expect(state.sessionId.value).toBe('one')
    expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('one')
  })
  it('remembers the fallback session after deleting the selected session', async () => {
    api.chat.listPageChatSessions.mockResolvedValueOnce({
      data: {
        items: [
          { sessionId: 'two', version: 2 },
          { sessionId: 'one', version: 3 },
        ],
      },
    })
    const state = setup()
    await state.recover()
    api.chat.getPageChat.mockResolvedValueOnce({
      data: { sessionId: 'two', turns: [], hasMore: false },
    })
    await state.selectSession('two')
    await state.deleteSession()
    expect(state.sessionId.value).toBe('one')
    expect(localStorage.getItem('ai-page-builder:last-session:one')).toBe('one')
    scope.stop()
    scope = effectScope()
    await setup().recover()
    expect(api.chat.getPageChat).toHaveBeenLastCalledWith({
      name: 'one',
      sessionId: 'one',
      size: 20,
    })
  })
  it('uses session versions for deletion and loads a newly created session', async () => {
    const state = setup()
    await state.recover()
    await state.deleteSession()
    expect(api.chat.deletePageChatSession).toHaveBeenCalledWith({
      name: 'one',
      sessionId: 'one',
      version: 3,
    })
    api.chat.createPageChatSession.mockResolvedValue({ data: { sessionId: 'new' } })
    api.chat.getPageChat.mockResolvedValueOnce({
      data: { sessionId: 'new', turns: [], hasMore: false },
    })
    await state.createSession()
    expect(state.sessionId.value).toBe('new')
    expect(committed).not.toHaveBeenCalled()
  })
  it('recovers the active session and blocks switching/deletion while a run is pending', async () => {
    localStorage.setItem('ai-page-builder:last-session:one', 'one')
    api.chat.listPageChatSessions.mockResolvedValue({
      data: {
        items: [
          { sessionId: 'one', version: 3 },
          { sessionId: 'busy', version: 2, activeRun: { runId: 'r', status: 'running' } },
        ],
      },
    })
    api.chat.getPageChat.mockResolvedValue({
      data: { sessionId: 'busy', turns: [], activeRun: { runId: 'r', status: 'running' } },
    })
    api.chat.getPageChatRun.mockResolvedValue({ data: { runId: 'r', status: 'running' } })
    const state = setup()
    await state.recover()
    expect(api.chat.getPageChat).toHaveBeenCalledWith({ name: 'one', sessionId: 'busy', size: 20 })
    await state.selectSession('other')
    await state.deleteSession()
    await state.createSession()
    expect(state.sessionId.value).toBe('busy')
    expect(api.chat.deletePageChatSession).not.toHaveBeenCalled()
    expect(api.chat.createPageChatSession).not.toHaveBeenCalled()
  })
  it('prepends paginated history in order without duplicates or page adoption', async () => {
    const turn = (runId: string) => ({
      runId,
      prompt: runId,
      text: 'ok',
      tools: [],
      status: 'completed',
    })
    api.chat.getPageChat.mockResolvedValueOnce({
      data: { sessionId: 'one', turns: [turn('new')], hasMore: true, nextBefore: 'cursor' },
    })
    const state = setup()
    await state.recover()
    api.chat.getPageChat.mockResolvedValueOnce({
      data: { sessionId: 'one', turns: [turn('old'), turn('new')], hasMore: false },
    })
    await state.loadEarlier()
    expect(api.chat.getPageChat).toHaveBeenLastCalledWith({
      name: 'one',
      sessionId: 'one',
      before: 'cursor',
      size: 20,
    })
    expect(state.messages.value?.map((item) => item.id)).toEqual([
      'old-user',
      'old-assistant',
      'new-user',
      'new-assistant',
    ])
    expect(state.hasMore.value).toBe(false)
    expect(committed).not.toHaveBeenCalled()
  })
  it('requires a selected model and respects the server prompt limit', async () => {
    const state = setup()
    await state.recover()
    state.modelName.value = ''
    expect(await state.send('Change')).toBe(false)
    state.modelName.value = 'tool-model'
    expect(await state.send('x'.repeat(8001))).toBe(false)
    expect(prepare).not.toHaveBeenCalled()
  })
  it('unlocks a rejected start with no server run and preserves the retry prompt', async () => {
    const state = setup()
    await state.recover()
    sdk.send.mockImplementation(async () => {
      sdk.options.onError(new Error('Model unavailable'))
    })
    api.chat.getPageChatRun.mockRejectedValue({ response: { status: 404 } })
    expect(await state.send('Keep this prompt')).toBe(false)
    expect(state.running.value).toBe(false)
    expect(state.lastPrompt.value).toBe('Keep this prompt')
    expect(state.error.value).toBe('Model unavailable')
  })

  it.each(['history', 'sessions'])(
    'keeps a confirmed commit successful when refreshing %s fails and retries without generating',
    async (target) => {
      const completed = vi.fn()
      const state = setup({ completed })
      await state.recover()
      const refresh = target === 'history' ? api.chat.getPageChat : api.chat.listPageChatSessions
      refresh.mockRejectedValueOnce(new Error('Refresh offline'))

      expect(await state.send('Change')).toBe(true)
      expect(state.running.value).toBe(false)
      expect(state.canSend.value).toBe(true)
      expect(state.error.value).toBe('')
      expect(state.refreshError.value).toContain('Refresh offline')
      expect(state.notice.value).toContain('已更新并保存')
      expect(committed).toHaveBeenCalledTimes(1)
      expect(completed).toHaveBeenCalledTimes(1)

      await state.retryRefresh()
      expect(state.refreshError.value).toBe('')
      expect(sdk.send).toHaveBeenCalledTimes(1)
      expect(committed).toHaveBeenCalledTimes(1)
      expect(completed).toHaveBeenCalledTimes(1)
    },
  )

  it('preserves a frozen state on unknown commit outcome and does not resubmit', async () => {
    const state = setup()
    await state.recover()
    api.chat.getPageChatRun.mockRejectedValue(new Error('offline'))
    await state.send('Change')
    expect(state.running.value).toBe(true)
    expect(state.error.value).toContain('检查运行结果')
    await state.send('Retry')
    expect(sdk.send).toHaveBeenCalledTimes(1)
  })
  it('retains original attachments for retry and rejects a changed content digest', async () => {
    const state = setup()
    await state.recover()
    const annotation = {
      id: 'a',
      path: [0],
      tag: 'p',
      summary: 'Target',
      html: '<p>Target</p>',
      comment: 'Change',
    }
    prepare.mockResolvedValueOnce({
      version: 8,
      baseHash: 'a'.repeat(64),
      annotations: [annotation],
    })
    api.chat.getPageChatRun.mockResolvedValue({
      data: { runId: 'run', status: 'failed', error: 'Try again' },
    })
    await state.send('Change target')
    prepare.mockResolvedValueOnce({ version: 8, baseHash: 'a'.repeat(64), annotations: [] })
    await state.send('Change target', true)
    expect(sdk.transport.prepareSendMessagesRequest().body.annotations).toEqual([annotation])
    prepare.mockResolvedValueOnce({ version: 9, baseHash: 'b'.repeat(64), annotations: [] })
    await state.send('Change target', true)
    expect(sdk.send).toHaveBeenCalledTimes(2)
    expect(state.error.value).toContain('重新选择')
  })
})

describe('checkpoint recovery', () => {
  const recovery = {
    sourceRunId: 'original-run',
    checkpointVersion: 7,
    canResume: true,
    updatedAt: '2026-10-16T10:00:00Z',
  }
  const original = {
    runId: 'original-run',
    version: 8,
    modelName: 'original-model',
    prompt: 'Build original page',
    sessionId: 'one',
    annotations: [],
    baseHash: 'hash',
  }
  function savedProgress() {
    api.chat.getPageChat.mockResolvedValue({ data: { sessionId: 'one', turns: [], recovery } })
    api.chat.getPageChatCheckpoint.mockResolvedValue({
      data: {
        request: original,
        recovery,
        document: { html: '<p>durable</p>', css: '', js: '' },
      },
    })
  }
  it('continues with original request/model and a fresh run id without saving the local editor', async () => {
    savedProgress()
    const state = setup({ version: () => 8, dirty: () => false })
    await state.recover()
    state.modelName.value = '' // The stored model is sufficient for resume.
    expect(state.canResume.value).toBe(true)
    await state.resume()
    expect(prepare).not.toHaveBeenCalled()
    expect(sdk.transport.prepareSendMessagesRequest().body).toMatchObject({
      ...original,
      runId: expect.any(String),
      resumeFromRunId: 'original-run',
      checkpointVersion: 7,
    })
    expect(sdk.transport.prepareSendMessagesRequest().body.runId).not.toBe('original-run')
    expect(sdk.send).toHaveBeenCalledWith({ text: '继续生成' })
  })
  it('uses checkpoint HTML for the first CSS-only update without changing the draft', async () => {
    savedProgress()
    const draft = { html: '<p>old draft</p>', css: '', js: '' }
    const state = setup({ baseline: () => draft, version: () => 8 })
    await state.recover()
    api.chat.validatePageContent.mockResolvedValue({})
    let finish!: () => void
    sdk.send.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const pending = state.resume()
    await vi.advanceTimersByTimeAsync(0)
    sdk.setMessages([
      {
        id: 'resumed-assistant',
        role: 'assistant',
        parts: [
          {
            type: 'tool-update_current_page',
            toolName: 'update_current_page',
            toolCallId: 'css',
            state: 'input-streaming',
            input: { css: 'p{color:blue}' },
          },
        ],
      },
    ])
    await vi.advanceTimersByTimeAsync(250)
    expect(api.chat.validatePageContent).toHaveBeenCalledWith(
      { validatePageContentRequest: { html: '<p>durable</p>', css: 'p{color:blue}' } },
      expect.anything(),
    )
    expect(state.preview.value?.content).toEqual({ html: '<p>durable</p>', css: 'p{color:blue}' })
    expect(draft).toEqual({ html: '<p>old draft</p>', css: '', js: '' })
    expect(committed).not.toHaveBeenCalled()
    expect(prepare).not.toHaveBeenCalled()
    finish()
    await pending
  })
  it('blocks resume while local edits are dirty and rechecks base version before sending', async () => {
    savedProgress()
    const dirty = shallowRef(true)
    const state = setup({ dirty: () => dirty.value, version: () => 9 })
    await state.recover()
    expect(state.canResume.value).toBe(false)
    expect(await state.resume()).toBe(false)
    expect(api.chat.getPageChatCheckpoint).not.toHaveBeenCalled()
    dirty.value = false
    expect(await state.resume()).toBe(false)
    expect(state.error.value).toContain('页面已变化')
    expect(prepare).not.toHaveBeenCalled()
    expect(sdk.send).not.toHaveBeenCalled()
  })
  it('loads checkpoint content only on demand and never adopts preview as a draft', async () => {
    savedProgress()
    const state = setup()
    await state.recover()
    expect(api.chat.getPageChatCheckpoint).not.toHaveBeenCalled()
    await state.viewCheckpoint()
    expect(state.preview.value).toMatchObject({
      status: 'checkpoint',
      content: { html: '<p>durable</p>' },
    })
    expect(committed).not.toHaveBeenCalled()
    expect(prepare).not.toHaveBeenCalled()
    await state.viewCheckpoint()
    expect(state.preview.value).toBeUndefined()
  })
  it('rejects a checkpoint response arriving after switching sessions', async () => {
    savedProgress()
    let respond!: (value: unknown) => void
    api.chat.getPageChatCheckpoint.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          respond = resolve
        }),
    )
    const state = setup()
    await state.recover()
    const pending = state.viewCheckpoint()
    api.chat.getPageChat.mockResolvedValue({ data: { sessionId: 'other', turns: [] } })
    await state.selectSession('other')
    respond({ data: { request: original, recovery, document: { html: '<p>wrong session</p>' } } })
    await pending
    expect(state.preview.value).toBeUndefined()
    expect(committed).not.toHaveBeenCalled()
  })
})
