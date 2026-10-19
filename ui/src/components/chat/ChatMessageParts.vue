<script setup lang="ts">
import IconCheck from '~icons/mingcute/check-line'
import IconError from '~icons/mingcute/warning-line'
import IconSpinner from '~icons/mingcute/loading-line'
import IconChevron from '~icons/mingcute/right-line'
import type { PageChatAnswerItem } from '../../api/generated'
import type { DisplayPart } from '../../editor/chat-display'
import ChatMarkdown from './ChatMarkdown.vue'
import ChatQuestions from './ChatQuestions.vue'

defineProps<{
  parts: DisplayPart[]
  pendingQuestionId?: string
  submitting?: boolean
  answerError?: string
}>()
const emit = defineEmits<{ answer: [questionId: string, answers: PageChatAnswerItem[]] }>()
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
          <span v-if="part.tool.hint" class="min-w-0 truncate text-gray-400">{{
            part.tool.hint
          }}</span>
          <span v-if="part.tool.status" class="text-gray-400">{{ part.tool.status }}</span>
          <IconChevron
            class="ml-auto shrink-0 text-gray-300 transition-transform group-open:rotate-90"
          />
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
