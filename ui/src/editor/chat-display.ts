import type { DeepReadonly } from 'vue'
import type { UIMessage, ToolPart } from '@halo-dev/ai-foundation-sdk'
import type { ChatTurn } from '../api/clients'
import type { PageChatQuestion } from '../api/generated'

export interface DisplayPart {
  type: 'text' | 'reasoning' | 'tool'
  id: string
  text: string
  active: boolean
  tool?: {
    label: string
    state: 'error' | 'success' | 'default'
    active: boolean
    status: string
    summary: string
    hint: string
    openable: boolean
    question?: PageChatQuestion
  }
}

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

export function restoreConversation(turns: ChatTurn[]) {
  const questionsByRunId = new Map(turns.map((turn) => [turn.runId, turnQuestions(turn)]))
  const messages: UIMessage[] = turns.flatMap((turn) => [
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
        questions: questionsByRunId.get(turn.runId),
        recovery: turn.recovery,
      },
    },
  ])
  return { messages, questionsByRunId }
}

export function buildConversationDisplay(options: {
  messages: DeepReadonly<UIMessage[]> | UIMessage[]
  activeRunId?: string
  questions: PageChatQuestion[]
  running: boolean
  waitingForUser: boolean
  error: string
}) {
  const { messages } = options
  return messages.map((message) => {
    const last = message === messages[messages.length - 1]
    const questions =
      message.role === 'assistant' && options.activeRunId && last
        ? options.questions
        : (message.metadata as { questions?: PageChatQuestion[] } | undefined)?.questions || []
    const result = turnResult(message.role, message.metadata)
    const active = options.running && !options.waitingForUser && !options.error && !result && last
    const waiting = options.waitingForUser && last
    return {
      id: message.id,
      role: message.role,
      message,
      result,
      ...displayParts(message, active, waiting, questions),
      annotations:
        (
          message.metadata as
            { annotations?: { id: string; summary: string; comment: string }[] } | undefined
        )?.annotations || [],
    }
  })
}

export type ConversationDisplayItem = ReturnType<typeof buildConversationDisplay>[number]

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
function partFailed(part: { state?: string; output?: unknown }): boolean {
  const output =
    part.output && typeof part.output === 'object' ? (part.output as Record<string, unknown>) : {}
  return (
    part.state === 'output-error' ||
    part.state === 'output-denied' ||
    output.valid === false ||
    !!output.error
  )
}

// Only ask_user calls that reached the user occupy a question slot; failed calls
// (invalid input, unavailable handler) never produced a recorded question.
function displayParts(
  message: DeepReadonly<UIMessage> | UIMessage,
  active: boolean,
  waiting: boolean,
  questions: PageChatQuestion[],
) {
  let askUserIndex = 0
  const parts = message.parts.flatMap<DisplayPart>((part, index) => {
    if (part.type === 'text' || part.type === 'reasoning')
      return [
        {
          type: part.type,
          id: part.id,
          text: part.text,
          active: active && index === message.parts.length - 1,
        },
      ]
    if (!('toolName' in part)) return []
    const output =
      part.output && typeof part.output === 'object' ? (part.output as Record<string, unknown>) : {}
    const failed = partFailed(part)
    const done = part.state === 'output-available'
    const changing = ['update_current_page', 'patch_current_page'].includes(part.toolName)
    const labels: Record<string, string> = {
      read_current_page: '读取当前页面',
      get_theme_context: '读取主题样式',
      update_current_page: '更新页面内容',
      update_page_title: '设置页面标题',
      update_page_description: '设置页面描述',
      set_page_slug: '设置访问路径',
      rename_session: '命名会话',
      patch_current_page: '局部修改页面',
      list_theme_files: '查找主题文件',
      read_theme_file: '读取主题文件',
      list_posts: '查找文章',
      list_single_pages: '查找独立页面',
      list_ai_pages: '查找 AI 页面',
      read_ai_page: '读取 AI 页面',
      get_site_profile: '读取站点资料',
      get_navigation: '读取导航菜单',
      read_content: '读取正文',
      search_attachments: '搜索附件',
      fetch_webpage: '读取网页信息',
      search_public_apis: '搜索公开接口',
      get_public_api: '读取接口文档',
      ask_user: '确认页面需求',
      update_site_memory: '记忆业务资料',
    }
    const summary =
      typeof output.summary === 'string'
        ? output.summary
        : typeof output.error === 'string'
          ? output.error
          : (part as ToolPart).errorText || ''
    // Failures show their reason inline; "读取完成" is the old history fallback and
    // repeats the row status, so neither is worth an expander.
    const openable = !failed && summary.trim() !== '' && summary !== '读取完成'
    // The first segment of a read summary is its subject (path, keyword, title),
    // which tells repeated calls apart without expanding the row.
    const hint = openable && summary.includes(' · ') ? summary.slice(0, summary.indexOf(' · ')) : ''
    return [
      {
        type: 'tool' as const,
        id: part.toolCallId,
        text: '',
        active: false,
        tool: {
          label: labels[part.toolName] || '处理页面',
          state: failed ? ('error' as const) : done ? ('success' as const) : ('default' as const),
          active: active && !done && !failed,
          status: failed
            ? part.toolName === 'update_site_memory'
              ? '未确认保存'
              : part.toolName === 'get_public_api'
                ? '读取失败'
                : '校验未通过'
            : done
              ? changing && output.staged === false
                ? '内容未变化'
                : changing && output.staged === true
                  ? '已暂存'
                  : ''
              : waiting && part.toolName === 'ask_user'
                ? '等待回答'
                : active
                  ? '进行中'
                  : '已结束',
          summary,
          hint,
          openable,
          question: part.toolName === 'ask_user' && !failed ? questions[askUserIndex++] : undefined,
        },
      },
    ]
  })
  // The same traversal owns pairing and the unmatched remainder.
  return { parts, fallbackQuestions: questions.slice(askUserIndex) }
}
