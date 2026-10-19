import { describe, expect, it } from 'vitest'
import type { UIMessage, ToolPart } from '@halo-dev/ai-foundation-sdk'
import type { ChatTurn } from '../../api/clients'
import type { PageChatQuestion } from '../../api/generated'
import { buildConversationDisplay, restoreConversation } from '../chat-display'

const started = '2026-01-01T00:00:00Z'
function question(id: string, createdAt = started): PageChatQuestion {
  return { id, createdAt, questions: [], answers: [] }
}
function turn(runId: string, overrides: Partial<ChatTurn> = {}): ChatTurn {
  return {
    runId,
    actor: 'admin',
    createdAt: started,
    prompt: 'Build a page',
    text: '',
    tools: [],
    status: 'completed',
    ...overrides,
  }
}
const idle = { questions: [], running: false, waitingForUser: false, error: '' }

describe('conversation display', () => {
  it('restores ordered text, reasoning and tool display parts', () => {
    const turns = [
      {
        runId: 'a',
        actor: 'user',
        createdAt: '2026-01-01T00:00:00Z',
        prompt: 'Hi',
        text: 'Legacy',
        tools: [],
        status: 'completed',
        parts: [
          { type: 'text', id: 'first', text: 'First' },
          { type: 'reasoning', id: 'why', text: 'Actual reasoning' },
          {
            type: 'tool',
            id: 'tool-a',
            toolName: 'patch_current_page',
            status: 'completed',
            summary: 'Saved',
          },
          { type: 'text', id: 'last', text: 'Last' },
        ],
      },
    ] as ChatTurn[]
    const parts = restoreConversation(turns).messages[1]!.parts
    expect(parts.map((part) => part.type)).toEqual([
      'text',
      'reasoning',
      'tool-patch_current_page',
      'text',
    ])
    expect(parts[1]).toMatchObject({ text: 'Actual reasoning' })
    expect(parts[2]).not.toHaveProperty('input')
  })
  it('restores legacy text/tool summaries without tool arguments', () => {
    const turns = [
      {
        runId: 'a',
        prompt: 'Hello',
        text: 'Done',
        tools: [{ name: 'update_current_page', status: 'completed', summary: 'ok' }],
        status: 'completed',
      },
    ] as ChatTurn[]
    const messages = restoreConversation(turns).messages
    expect(messages).toHaveLength(2)
    expect(messages[1]?.parts[0]).not.toHaveProperty('input')
    expect(messages[1]?.parts[0]).toHaveProperty('output', { summary: 'ok' })
  })
  it('renders mid-run steers as user messages between the prompt and the reply', () => {
    const turns = [
      {
        runId: 'a',
        actor: 'user',
        createdAt: '2026-01-01T00:00:00Z',
        prompt: 'Hello',
        text: 'Done',
        tools: [],
        status: 'completed',
        steers: ['标题再短一点', '换个配色'],
      },
    ] as ChatTurn[]
    const messages = restoreConversation(turns).messages
    expect(messages.map((message) => [message.id, message.role])).toEqual([
      ['a-user', 'user'],
      ['a-steer-0', 'user'],
      ['a-steer-1', 'user'],
      ['a-assistant', 'assistant'],
    ])
    expect(messages[1]?.parts[0]).toMatchObject({ text: '标题再短一点' })
    expect(messages[2]?.parts[0]).toMatchObject({ text: '换个配色' })
  })
  it('restores question ownership, skips failed tools and retains unmatched questions together', () => {
    const inherited = question('inherited', '2025-12-31T00:00:00Z')
    const fresh = question('fresh')
    const unmatched = question('unmatched')
    const restored = restoreConversation([
      turn('old', {
        questions: [inherited],
        createdAt: inherited.createdAt,
        parts: [{ type: 'tool', id: 'old-ask', toolName: 'ask_user', status: 'completed' }],
      }),
      turn('resumed', {
        status: 'waiting_for_user',
        questions: [inherited, fresh, unmatched],
        parts: [
          { type: 'text', id: 'before', text: 'Before' },
          { type: 'tool', id: 'bad', toolName: 'ask_user', status: 'error' },
          { type: 'tool', id: 'ask', toolName: 'ask_user', status: 'running' },
          { type: 'text', id: 'after', text: 'After' },
        ],
      }),
    ])
    const display = buildConversationDisplay({
      ...idle,
      messages: restored.messages,
      activeRunId: 'resumed',
      running: true,
      waitingForUser: true,
      questions: restored.questionsByRunId.get('resumed')!,
    })
    expect(display[1]!.parts[0]!.tool?.question).toBe(inherited)
    const current = display[3]!
    expect(current.parts.map((part) => part.id)).toEqual(['before', 'bad', 'ask', 'after'])
    expect(current.parts[1]!.tool).toMatchObject({ state: 'error', question: undefined })
    expect(current.parts[2]!.tool).toMatchObject({
      question: fresh,
      status: '等待回答',
      active: false,
    })
    expect(current.fallbackQuestions).toEqual([unmatched])
    expect(current.parts.every((part) => !part.active && !part.tool?.active)).toBe(true)
    expect(current.message).toBe(restored.messages[3])
    expect(restored.messages[3]!.parts[2]).not.toHaveProperty('question')
  })

  it.each([
    { state: 'output-error', errorText: 'invalid' },
    { state: 'output-denied' },
    { state: 'output-available', output: { valid: false } },
    { state: 'output-available', output: { error: 'invalid' } },
  ] as Partial<ToolPart>[])(
    'uses the same failure rule for pairing and fallback: %j',
    (failure) => {
      const first = question('first')
      const second = question('second')
      const message: UIMessage = {
        id: 'live',
        role: 'assistant',
        parts: [
          {
            type: 'tool-ask_user',
            toolName: 'ask_user',
            toolCallId: 'bad',
            state: 'input-available',
            ...failure,
          } as ToolPart,
          {
            type: 'tool-ask_user',
            toolName: 'ask_user',
            toolCallId: 'good',
            state: 'input-available',
          },
        ],
      }
      const [display] = buildConversationDisplay({
        ...idle,
        messages: [message],
        activeRunId: 'live',
        questions: [first, second],
        running: true,
        waitingForUser: true,
      })
      expect(display!.parts[0]!.tool).toMatchObject({ state: 'error', question: undefined })
      expect(display!.parts[1]!.tool?.question).toBe(first)
      expect(display!.fallbackQuestions).toEqual([second])
    },
  )

  it('keeps the active empty turn separate from archived questions', () => {
    const previous = question('previous')
    const pending = question('pending')
    const { messages } = restoreConversation([turn('old', { questions: [previous] })])
    const placeholder: UIMessage = {
      id: 'new-assistant',
      role: 'assistant',
      parts: [],
      metadata: { runId: 'new' },
    }
    const display = buildConversationDisplay({
      ...idle,
      messages: [...messages, placeholder],
      activeRunId: 'new',
      questions: [pending],
      running: true,
      waitingForUser: true,
    })
    expect(display[1]!.fallbackQuestions).toEqual([previous])
    expect(display[2]!.fallbackQuestions).toEqual([pending])
    expect(display[2]!.message).toBe(placeholder)
  })

  it.each([
    ['not-a-date', started],
    [started, 'not-a-date'],
    [started, started],
  ])('preserves timestamp compatibility for turn %s and question %s', (createdAt, askedAt) => {
    const kept = question('kept', askedAt)
    const restored = restoreConversation([turn('a', { createdAt, questions: [kept] })])
    const [_, display] = buildConversationDisplay({ ...idle, messages: restored.messages })
    expect(restored.questionsByRunId.get('a')).toEqual([kept])
    expect(display!.fallbackQuestions).toEqual([kept])
  })

  it.each([
    ['completed', '本轮草稿已保存', false],
    ['failed', '运行失败 · 草稿未更新', true],
    ['cancelled', '已停止 · 草稿未更新', false],
    ['incomplete', '本轮未完成 · 草稿未更新', false],
    ['interrupted', '运行中断 · 草稿未更新', true],
  ])('stops animations and preserves the %s result', (status, text, failed) => {
    const { messages } = restoreConversation([
      turn('a', {
        status,
        revision: status === 'completed' ? 'revision' : undefined,
        error: 'detail',
        parts: [{ type: 'text', id: 'last', text: 'Done' }],
      }),
    ])
    const display = buildConversationDisplay({ ...idle, messages, running: true })[1]!
    expect(display.result).toMatchObject({ text, failed })
    expect(display.parts[0]!.active).toBe(false)
  })

  it('shows retained progress without repeating the terminal error', () => {
    const { messages } = restoreConversation([
      turn('a', {
        status: 'interrupted',
        error: 'truncated',
        recovery: { sourceRunId: 'a', checkpointVersion: 1, canResume: true, updatedAt: started },
      }),
    ])
    const display = buildConversationDisplay({ ...idle, messages })[1]!
    expect(display.result).toEqual({
      failed: true,
      text: '运行中断 · 进度已保留，草稿未更新',
      error: '',
    })
  })

  it('marks only the final live segment active and stops it on a stream error', () => {
    const message: UIMessage = {
      id: 'live',
      role: 'assistant',
      parts: [
        { type: 'text', id: 'first', text: 'Before' },
        { type: 'reasoning', id: 'last', text: 'Thinking' },
      ],
    }
    const live = buildConversationDisplay({ ...idle, messages: [message], running: true })[0]!
    expect(live.parts.map((part) => part.active)).toEqual([false, true])
    const failed = buildConversationDisplay({
      ...idle,
      messages: [message],
      running: true,
      error: 'connection lost',
    })[0]!
    expect(failed.parts.map((part) => part.active)).toEqual([false, false])
  })
})
