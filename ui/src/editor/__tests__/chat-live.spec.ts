import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AiPanel from '../../components/AiPanel.vue'
import type { PageDetail } from '../../api/generated'

const api = vi.hoisted(() => ({
  getPageChat: vi.fn(),
  getPageChatRun: vi.fn(),
  answerPageChatQuestion: vi.fn(),
  fetch: vi.fn(),
  prepare: vi.fn(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderAiApi: { getAiPageBuilderAiStatus: async () => ({ data: { available: true } }) },
  aiPageBuilderChatApi: {
    listPageChatSessions: async () => ({ data: { items: [{ sessionId: 'one' }] } }),
    getPageChat: api.getPageChat,
    getPageChatRun: api.getPageChatRun,
    answerPageChatQuestion: api.answerPageChatQuestion,
  },
  chatUrl: () => '/chat',
  prepareChatRequest: api.prepare,
}))
vi.mock('../../api/stream', () => ({ authenticatedChatFetch: api.fetch }))
vi.mock('@halo-dev/components', () => ({
  Dialog: {},
  VDropdown: { template: '<div><slot /></div>' },
  VDropdownItem: { template: '<button><slot /></button>' },
  VLoading: { template: '<span />' },
}))
afterEach(() => vi.useRealTimers())

describe('live questions with the real chat SDK', () => {
  it('renders first-run streamed questions and retains them through waiting polls', async () => {
    vi.useFakeTimers()
    localStorage.setItem('ai-page-builder:model-name', 'model')
    api.getPageChat.mockResolvedValue({ data: { sessionId: 'one', turns: [] } })
    let runId = ''
    api.prepare.mockImplementation((_name, request) => {
      runId = request.runId
      return { api: '/chat', body: request }
    })
    let controller!: ReadableStreamDefaultController<Uint8Array>
    api.fetch.mockResolvedValue(
      new Response(
        new ReadableStream({
          start(value) {
            controller = value
          },
        }),
        {
          headers: { 'content-type': 'text/event-stream', 'x-halo-ai-ui-message-stream': 'v1' },
        },
      ),
    )
    const wrapper = mount(AiPanel, {
      props: {
        pageName: 'one',
        disabled: false,
        prepare: async () => 1,
        document: { html: '', css: '', js: '' } as PageDetail['document'],
        annotations: [],
      },
      global: { stubs: { FormKit: true }, directives: { 'close-popper': {} } },
    })
    await flushPromises()
    await wrapper.find('textarea').setValue('帮我设计页面')
    await wrapper.find('button[aria-label="发送"]').trigger('click')
    await flushPromises()
    const question = {
      id: 'q',
      createdAt: '2026-01-01T00:00:00Z',
      questions: [
        {
          id: 'style',
          title: '选择页面风格',
          options: [{ id: 'minimal', label: '简洁' }],
          allowCustomAnswer: true,
        },
      ],
    }
    const waiting = { runId, status: 'waiting_for_user', pendingQuestion: question }
    api.getPageChatRun.mockResolvedValue({ data: waiting })
    api.getPageChat.mockResolvedValue({ data: { sessionId: 'one', turns: [], activeRun: waiting } })
    for (const chunk of [
      { type: 'start', messageId: 'reply' },
      { type: 'tool-input-available', toolName: 'ask_user', toolCallId: 'call', input: {} },
      {
        type: 'data-user-question',
        name: 'user-question',
        id: question.id,
        data: { runId, question },
      },
    ])
      controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(chunk)}\n\n`))
    await flushPromises()
    expect(wrapper.text()).toContain('帮我设计页面')
    expect(wrapper.text()).toContain('选择页面风格')
    expect(wrapper.text()).not.toContain('从一个想法开始')
    expect(wrapper.text()).not.toContain('助手正在处理页面')
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.text()).toContain('帮我设计页面')
    expect(wrapper.find('form[aria-label="助手的问题"]').exists()).toBe(true)
    expect(api.getPageChat).toHaveBeenCalledTimes(1)
    const answered = { ...question, answers: [{ questionId: 'style', optionId: 'minimal' }] }
    controller.enqueue(
      new TextEncoder().encode(
        `data: ${JSON.stringify({
          type: 'data-user-answer',
          name: 'user-answer',
          id: question.id,
          data: { runId, question: answered },
        })}\n\n`,
      ),
    )
    await flushPromises()
    expect(wrapper.text()).toContain('已回答')
    expect(wrapper.text()).toContain('助手正在处理页面')
    expect(wrapper.text()).toContain('帮我设计页面')
    controller.close()
    wrapper.unmount()
    await flushPromises()
  })
})
