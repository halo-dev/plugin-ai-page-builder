import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import {
  applyUIMessageChunk,
  createUIMessageReducer,
  type UIMessageChunk,
  type UIMessage,
} from '@halo-dev/ai-foundation-sdk'
import ChatMarkdown from '../../components/chat/ChatMarkdown.vue'
import ChatMessageParts from '../../components/chat/ChatMessageParts.vue'
import type { PageChatQuestion } from '../../api/generated'
import { buildConversationDisplay, restoreConversation } from '../chat-display'

vi.mock('@halo-dev/components', () => ({
  VStatusDot: { props: ['animate'], template: '<span :data-animate="animate" />' },
}))

function displayProps(input: {
  message: UIMessage
  active?: boolean
  waiting?: boolean
  questions?: PageChatQuestion[]
}) {
  const [display] = buildConversationDisplay({
    messages: [input.message],
    activeRunId: 'run',
    questions: input.questions || [],
    running: !!input.active,
    waitingForUser: !!input.waiting,
    error: '',
  })
  return { parts: display!.parts }
}

describe('streaming conversation rendering', () => {
  it.each([
    ['search_public_apis', '搜索公开接口'],
    ['get_public_api', '读取接口文档'],
    ['set_page_slug', '设置访问路径'],
    ['update_page_title', '设置页面标题'],
    ['rename_session', '命名会话'],
  ])('renders %s while running, completed and restored from history', async (toolName, label) => {
    let state = createUIMessageReducer()
    state = applyUIMessageChunk(state, {
      type: 'tool-input-start',
      toolCallId: 'api',
      toolName,
    })
    const wrapper = mount(ChatMessageParts, {
      props: displayProps({ message: state.message, active: true }),
    })
    expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).toContain('进行中')
    expect(wrapper.text()).not.toContain('处理页面')
    expect(wrapper.find('.animate-spin').exists()).toBe(true)

    state = applyUIMessageChunk(state, {
      type: 'tool-input-available',
      toolCallId: 'api',
      toolName,
      input: {},
    })
    state = applyUIMessageChunk(state, {
      type: 'tool-output-available',
      toolCallId: 'api',
      toolName,
      output: { items: [] },
    })
    await wrapper.setProps(displayProps({ message: state.message, active: false }))
    expect(wrapper.text()).toContain(label)
    // A successful row ends with the check icon; the redundant "完成" label is gone.
    expect(wrapper.text()).not.toContain('完成')
    expect(wrapper.find('.text-emerald-600').exists()).toBe(true)
    expect(wrapper.find('.animate-spin').exists()).toBe(false)
    wrapper.unmount()

    const restored = mount(ChatMessageParts, {
      props: displayProps({
        active: false,
        message: {
          id: 'history',
          role: 'assistant',
          parts: [
            {
              type: `tool-${toolName}`,
              toolCallId: 'api',
              toolName,
              state: 'output-available',
              input: {},
              output: { summary: '读取完成' },
            },
          ],
        },
      }),
    })
    expect(restored.text()).toContain(label)
    // The generic history fallback is not detail, so the row stays flat.
    expect(restored.text()).not.toContain('读取完成')
    expect(restored.find('details').exists()).toBe(false)
    expect(restored.text()).not.toContain('处理页面')
    restored.unmount()
  })
  it.each([
    {
      status: 'error',
      summary: '已读取 0/3 项接口文档，3 项失败：未找到接口',
      items: [
        { id: 'GET /missing', status: 'not_found', error: '未找到接口' },
        { id: 'GET /unavailable', status: 'unavailable', error: '契约不可用' },
        { id: 'GET /ambiguous', status: 'ambiguous', error: '接口存在歧义' },
      ],
    },
    {
      status: 'completed',
      summary: '已读取 1/2 项接口文档，1 项失败：未找到接口',
      items: [
        { id: 'GET /posts', status: 'ok', responses: { '200': { description: 'Success' } } },
        { id: 'GET /missing', status: 'not_found', error: '未找到接口' },
      ],
    },
  ])(
    'renders $status API details live and through history restoration',
    ({ status, summary, items }) => {
      let state = createUIMessageReducer()
      state = applyUIMessageChunk(state, {
        type: 'tool-input-available',
        toolCallId: 'api',
        toolName: 'get_public_api',
        input: { ids: items.map((item) => item.id) },
      })
      state = applyUIMessageChunk(state, {
        type: 'tool-output-available',
        toolCallId: 'api',
        toolName: 'get_public_api',
        output: {
          items,
          partial: true,
          summary,
          ...(status === 'error' ? { error: summary } : {}),
        },
      })
      const restored = restoreConversation([
        {
          runId: 'history',
          actor: 'user',
          createdAt: '2026-01-01T00:00:00Z',
          prompt: '读取接口文档',
          text: '',
          tools: [],
          status: 'completed',
          parts: [{ type: 'tool', id: 'api', toolName: 'get_public_api', status, summary }],
        },
      ]).messages[1]!
      for (const message of [state.message, restored]) {
        const wrapper = mount(ChatMessageParts, { props: displayProps({ message, active: false }) })
        expect(wrapper.text()).toContain('读取接口文档')
        expect(wrapper.find('.text-red-500').exists()).toBe(status === 'error')
        expect(wrapper.find('.text-emerald-600').exists()).toBe(status === 'completed')
        if (status === 'error') {
          // Failures show their reason inline instead of behind an expander.
          expect(wrapper.find('details').exists()).toBe(false)
          expect(wrapper.text()).toContain(summary)
        } else {
          expect(wrapper.get('summary').text()).not.toContain('完成')
          expect(wrapper.get('details p').text()).toBe(summary)
          expect(wrapper.get('details').attributes('open')).toBeUndefined()
        }
        expect(wrapper.text()).not.toContain('GET /')
        wrapper.unmount()
      }
    },
  )
  it('shows a read subject inline and keeps the count in details', () => {
    const wrapper = mount(ChatMessageParts, {
      props: displayProps({
        active: false,
        message: {
          id: 'm',
          role: 'assistant',
          parts: [
            {
              type: 'tool-list_theme_files',
              toolCallId: 'files',
              toolName: 'list_theme_files',
              state: 'output-available',
              input: {},
              output: { summary: 'templates · 共 3 个文件' },
            },
          ],
        },
      }),
    })
    expect(wrapper.get('summary').text()).toContain('查找主题文件')
    expect(wrapper.get('summary').text()).toContain('templates')
    expect(wrapper.get('summary').text()).not.toContain('共 3 个文件')
    expect(wrapper.get('details p').text()).toBe('templates · 共 3 个文件')
    wrapper.unmount()
  })
  it('renders durable memory outcomes separately from page staging', async () => {
    const part = {
      type: 'tool-update_site_memory' as const,
      toolCallId: 'memory',
      toolName: 'update_site_memory',
      state: 'output-available' as const,
      input: {},
      output: { summary: '已记住公司电话' },
    }
    const wrapper = mount(ChatMessageParts, {
      props: displayProps({
        active: false,
        message: { id: 'm', role: 'assistant', parts: [part] },
      }),
    })
    expect(wrapper.text()).toContain('记忆业务资料')
    expect(wrapper.text()).toContain('已记住公司电话')
    expect(wrapper.text()).not.toContain('已暂存')
    await wrapper.setProps(
      displayProps({
        message: {
          id: 'm',
          role: 'assistant',
          parts: [{ ...part, output: { error: '保存结果待确认', summary: '保存结果待确认' } }],
        },
      }),
    )
    expect(wrapper.text()).toContain('未确认保存')
    expect(wrapper.text()).toContain('保存结果待确认')
    wrapper.unmount()
  })
  it('shows a static ask-user waiting state without thinking or tool animations', () => {
    const wrapper = mount(ChatMessageParts, {
      props: displayProps({
        active: false,
        waiting: true,
        message: {
          id: 'assistant',
          role: 'assistant',
          parts: [
            { type: 'reasoning', id: 'thinking', text: 'Need a preference' },
            {
              type: 'tool-ask_user',
              toolCallId: 'ask',
              toolName: 'ask_user',
              state: 'input-available',
              input: {},
            },
          ],
        },
      }),
    })
    expect(wrapper.text()).toContain('等待回答')
    expect(wrapper.text()).not.toContain('正在思考')
    expect(wrapper.text()).not.toContain('已结束')
    expect(wrapper.find('.animate-spin').exists()).toBe(false)
    wrapper.unmount()
  })
  it('renders the question panel where it was asked, with later activity streaming below', () => {
    const question = {
      id: 'q1',
      createdAt: '2026-01-01T00:00:00Z',
      questions: [
        {
          id: 'style',
          title: '选择页面风格',
          options: [{ id: 'minimal', label: '简洁' }],
          allowCustomAnswer: false,
        },
      ],
      answers: [{ questionId: 'style', optionId: 'minimal' }],
    } as PageChatQuestion
    const wrapper = mount(ChatMessageParts, {
      props: displayProps({
        active: true,
        questions: [question],
        message: {
          id: 'm',
          role: 'assistant',
          parts: [
            {
              type: 'tool-ask_user',
              toolCallId: 'ask',
              toolName: 'ask_user',
              state: 'output-available',
              input: {},
              output: { summary: '用户已回答问题' },
            },
            {
              type: 'tool-read_theme_file',
              toolCallId: 'read',
              toolName: 'read_theme_file',
              state: 'input-available',
              input: {},
            },
          ],
        },
      }),
    })
    const text = wrapper.text()
    expect(text).toContain('已回答')
    expect(text.indexOf('确认页面需求')).toBeLessThan(text.indexOf('选择页面风格'))
    expect(text.indexOf('选择页面风格')).toBeLessThan(text.indexOf('读取主题文件'))
    wrapper.unmount()
  })
  it('skips failed ask_user calls when pairing questions with tool parts', () => {
    const question = {
      id: 'q1',
      createdAt: '2026-01-01T00:00:00Z',
      questions: [
        {
          id: 'style',
          title: '选择页面风格',
          options: [{ id: 'minimal', label: '简洁' }],
          allowCustomAnswer: false,
        },
      ],
    } as PageChatQuestion
    const parts = [
      {
        type: 'tool-ask_user' as const,
        toolCallId: 'bad',
        toolName: 'ask_user',
        state: 'output-available' as const,
        input: {},
        output: { error: '问题格式不正确' },
      },
      {
        type: 'tool-ask_user' as const,
        toolCallId: 'ask',
        toolName: 'ask_user',
        state: 'input-available' as const,
        input: {},
      },
    ]
    const wrapper = mount(ChatMessageParts, {
      props: displayProps({
        active: true,
        waiting: true,
        questions: [question],
        message: { id: 'm', role: 'assistant', parts },
      }),
    })
    const text = wrapper.text()
    expect(wrapper.findAll('form[aria-label="助手的问题"]')).toHaveLength(1)
    expect(text.indexOf('问题格式不正确')).toBeLessThan(text.indexOf('选择页面风格'))
    wrapper.unmount()
  })
  it('escapes HTML, blocks dangerous links and renders unfinished Markdown and code safely', async () => {
    const wrapper = mount(ChatMarkdown, {
      props: {
        final: false,
        content:
          '**Hello 你好\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))\n\n```html\n<img src=x onerror=alert(1)>',
      },
    })
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('[onerror]').exists()).toBe(false)
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Hello 你好')
    await wrapper.setProps({ final: true })
    expect(wrapper.find('pre').exists()).toBe(true)
    wrapper.unmount()
  })
  it('preserves SDK text/reasoning/tool order and ends animation on cancellation', () => {
    let state = createUIMessageReducer()
    const chunks: UIMessageChunk[] = [
      { type: 'text-start', id: 'text1' },
      { type: 'text-delta', id: 'text1', delta: 'Before' },
      { type: 'reasoning-start', id: 'think' },
      { type: 'reasoning-delta', id: 'think', delta: 'Reason supplied by model' },
      { type: 'tool-input-available', toolCallId: 'a', toolName: 'patch_current_page', input: {} },
      {
        type: 'tool-output-available',
        toolCallId: 'a',
        toolName: 'patch_current_page',
        output: { valid: true, staged: true },
      },
      { type: 'text-start', id: 'text2' },
      { type: 'text-delta', id: 'text2', delta: 'Between' },
      { type: 'tool-input-start', toolCallId: 'b', toolName: 'patch_current_page' },
      { type: 'abort' },
    ]
    for (const chunk of chunks) state = applyUIMessageChunk(state, chunk)
    const wrapper = mount(ChatMessageParts, {
      props: displayProps({ message: state.message, active: false }),
    })
    const text = wrapper.text()
    expect(text.indexOf('Before')).toBeLessThan(text.indexOf('思考过程'))
    expect(text.indexOf('思考过程')).toBeLessThan(text.indexOf('已暂存'))
    expect(text.indexOf('已暂存')).toBeLessThan(text.indexOf('Between'))
    expect(text).toContain('已结束')
    expect(wrapper.find('[data-animate="true"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
