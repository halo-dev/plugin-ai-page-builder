<script setup lang="ts">
import { computed } from 'vue'
import IconCheck from '~icons/mingcute/check-line'
import IconError from '~icons/mingcute/warning-line'
import type { PageChatAnswerItem } from '../../api/generated'
import type { ConversationDisplayItem } from '../../editor/chat-display'
import ChatMessageParts from './ChatMessageParts.vue'
import ChatQuestions from './ChatQuestions.vue'

const props = defineProps<{
  message: ConversationDisplayItem
  waitingForUser: boolean
  pendingQuestionId?: string
  answering: boolean
  answerError: string
}>()
const emit = defineEmits<{
  answer: [questionId: string, answers: PageChatAnswerItem[]]
}>()

const userText = computed(
  () => props.message.message.parts.find((part) => part.type === 'text')?.text,
)
</script>

<template>
  <article class="mb-6 text-[13px]">
    <div v-if="message.role === 'user'" class="flex justify-end">
      <div class="max-w-[88%] rounded-2xl rounded-br-md bg-gray-100 px-3.5 py-2.5 leading-6">
        <p class="whitespace-pre-wrap">{{ userText }}</p>
        <div v-if="message.annotations.length" class="mt-2 space-y-1 border-t border-gray-200 pt-2">
          <div v-for="item in message.annotations" :key="item.id" class="text-xs text-gray-500">
            <span class="font-medium text-gray-600">{{ item.summary }}</span> ·
            {{ item.comment }}
          </div>
        </div>
      </div>
    </div>
    <template v-else>
      <ChatMessageParts
        :parts="message.parts"
        :pending-question-id="waitingForUser ? pendingQuestionId : undefined"
        :submitting="answering"
        :answer-error="answerError"
        @answer="(questionId, answers) => emit('answer', questionId, answers)"
      />
      <ChatQuestions
        v-for="question in message.fallbackQuestions"
        :key="question.id"
        :question="question"
        :pending="waitingForUser && pendingQuestionId === question.id"
        :submitting="answering"
        :error="pendingQuestionId === question.id ? answerError : ''"
        @answer="(answers) => emit('answer', question.id, answers)"
      />
      <div
        v-if="message.result"
        class="mt-3 flex items-start gap-1.5 text-xs"
        :class="message.result.failed ? 'text-red-600' : 'text-gray-400'"
      >
        <IconError v-if="message.result.failed" class="mt-0.5 shrink-0" />
        <IconCheck v-else class="mt-0.5 shrink-0 text-emerald-600" />
        <div>
          <p>{{ message.result.text }}</p>
          <p v-if="message.result.error" class="mt-1 break-all leading-5 text-gray-400">
            {{ message.result.error }}
          </p>
        </div>
      </div>
    </template>
  </article>
</template>
