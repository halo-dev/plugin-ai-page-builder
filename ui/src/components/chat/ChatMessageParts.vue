<script lang="ts">
import type { DeepReadonly } from 'vue'
import type { UIMessage } from '@halo-dev/ai-foundation-sdk'

type MessagePart = UIMessage['parts'][number]

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
export function questionSlotCount(parts: DeepReadonly<MessagePart[]> | MessagePart[]): number {
  return parts.filter(
    (part) => 'toolName' in part && part.toolName === 'ask_user' && !partFailed(part),
  ).length
}
</script>

<script setup lang="ts">
import { computed, defineAsyncComponent, defineComponent, h } from 'vue'
import IconCheck from '~icons/mingcute/check-line'
import IconError from '~icons/mingcute/warning-line'
import IconSpinner from '~icons/mingcute/loading-line'
import IconChevron from '~icons/mingcute/right-line'
import type { ToolPart } from '@halo-dev/ai-foundation-sdk'
import type { PageChatAnswerItem, PageChatQuestion } from '../../api/generated'
import ChatQuestions from './ChatQuestions.vue'
// The Markdown renderer brings its own parser (~660 kB); keep it out of the
// editor chunk until a message actually needs it.
// Keep messages readable if the async renderer cannot be fetched (for example a
// stale chunk hash after a deploy) instead of rendering nothing.
const MarkdownFallback = defineComponent({
  // Mirrors ChatMarkdown's props so the async component keeps its inferred type.
  props: { content: { type: String, required: true }, final: { type: Boolean, required: true } },
  setup: (props) => () =>
    h('div', { class: 'break-anywhere whitespace-pre-wrap leading-7' }, props.content),
})
const ChatMarkdown = defineAsyncComponent({
  // Resolve to the fallback instead of rejecting so the original props are still
  // forwarded (Vue only passes `error` to `errorComponent`).
  loader: () => import('./ChatMarkdown.vue').catch(() => MarkdownFallback),
})

const props = defineProps<{
  message: DeepReadonly<UIMessage> | UIMessage
  active: boolean
  waiting?: boolean
  // The turn's recorded questions, in ask_user call order; each renders right after
  // the tool call that asked it, so later activity streams below the answered panel.
  questions?: PageChatQuestion[]
  pendingQuestionId?: string
  submitting?: boolean
  answerError?: string
}>()
const emit = defineEmits<{ answer: [questionId: string, answers: PageChatAnswerItem[]] }>()
interface DisplayPart {
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
const parts = computed(() => {
  let askUserIndex = 0
  return props.message.parts.flatMap<DisplayPart>((part, index) => {
    if (part.type === 'text' || part.type === 'reasoning')
      return [
        {
          type: part.type,
          id: part.id,
          text: part.text,
          active: props.active && index === props.message.parts.length - 1,
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
          active: props.active && !done && !failed,
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
              : props.waiting && part.toolName === 'ask_user'
                ? '等待回答'
                : props.active
                  ? '进行中'
                  : '已结束',
          summary,
          hint,
          openable,
          question:
            part.toolName === 'ask_user' && !failed
              ? props.questions?.[askUserIndex++]
              : undefined,
        },
      },
    ]
  })
})
</script>

<template>
  <template v-for="part in parts" :key="part.id">
    <ChatMarkdown v-if="part.type === 'text'" :content="part.text" :final="!part.active" />
    <details
      v-else-if="part.type === 'reasoning' && part.text"
      class="my-2 border-l-2 border-gray-200 pl-3 text-xs text-gray-500"
    >
      <summary class="cursor-pointer select-none">
        思考过程<span v-if="part.active"> · 正在思考…</span>
      </summary>
      <ChatMarkdown :content="part.text" :final="!part.active" />
    </details>
    <template v-else-if="'tool' in part && part.tool">
      <details v-if="part.tool.openable" class="group my-0.5">
        <summary
          class="flex cursor-pointer select-none items-center gap-1.5 py-1 text-xs text-gray-500 marker:hidden"
        >
          <IconSpinner v-if="part.tool.active" class="animate-spin text-gray-400" />
          <IconError v-else-if="part.tool.state === 'error'" class="text-red-500" />
          <IconCheck v-else-if="part.tool.state === 'success'" class="text-emerald-600" />
          <IconCheck v-else class="text-gray-300" />
          <span class="text-gray-600">{{ part.tool.label }}</span>
          <span v-if="part.tool.hint" class="min-w-0 truncate text-gray-400">{{ part.tool.hint }}</span>
          <span v-if="part.tool.status" class="text-gray-400">{{ part.tool.status }}</span>
          <IconChevron class="ml-auto shrink-0 text-gray-300 transition-transform group-open:rotate-90" />
        </summary>
        <p class="mb-1 break-all pl-[22px] text-xs leading-5 text-gray-400">
          {{ part.tool.summary }}
        </p>
      </details>
      <div v-else class="my-0.5">
        <div class="flex items-center gap-1.5 py-1 text-xs text-gray-500">
          <IconSpinner v-if="part.tool.active" class="animate-spin text-gray-400" />
          <IconError v-else-if="part.tool.state === 'error'" class="text-red-500" />
          <IconCheck v-else-if="part.tool.state === 'success'" class="text-emerald-600" />
          <IconCheck v-else class="text-gray-300" />
          <span class="text-gray-600">{{ part.tool.label }}</span>
          <span v-if="part.tool.status" class="text-gray-400">{{ part.tool.status }}</span>
        </div>
        <p
          v-if="part.tool.state === 'error' && part.tool.summary"
          class="mb-1 break-all pl-[22px] text-xs leading-5 text-red-500"
        >
          {{ part.tool.summary }}
        </p>
      </div>
      <ChatQuestions
        v-if="part.tool.question"
        :question="part.tool.question"
        :pending="pendingQuestionId === part.tool.question.id"
        :submitting="submitting"
        :error="pendingQuestionId === part.tool.question.id ? answerError : ''"
        @answer="emit('answer', part.tool.question.id, $event)"
      />
    </template>
  </template>
</template>
