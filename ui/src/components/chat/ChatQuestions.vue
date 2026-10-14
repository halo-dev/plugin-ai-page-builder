<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import IconSpinner from '~icons/mingcute/loading-line'
import type { PageChatAnswerItem, PageChatQuestion } from '../../api/generated'

const props = defineProps<{
  question: PageChatQuestion
  pending: boolean
  submitting?: boolean
  error?: string
}>()
const emit = defineEmits<{ answer: [answers: PageChatAnswerItem[]] }>()
const answers = ref<Record<string, { optionId: string; text: string }>>({})
const attempted = shallowRef(false)
watch(
  () => props.question.id,
  () => {
    answers.value = Object.fromEntries(
      props.question.questions.map((item) => [item.id, { optionId: '', text: '' }]),
    )
    attempted.value = false
  },
  { immediate: true },
)
const invalid = computed(() =>
  props.question.questions.filter((item) => {
    const answer = answers.value[item.id]
    return !answer || (!answer.optionId && !answer.text.trim()) || answer.text.trim().length > 2000
  }),
)
const answered = computed(() => !!props.question.answers?.length)
function answerText(id: string) {
  const answer = props.question.answers?.find((item) => item.questionId === id)
  const question = props.question.questions.find((item) => item.id === id)
  const label = question?.options?.find((item) => item.id === answer?.optionId)?.label
  return [label, answer?.text].filter(Boolean).join(' · ')
}
function submit() {
  if (!props.pending || props.submitting) return
  attempted.value = true
  if (invalid.value.length) return
  emit(
    'answer',
    props.question.questions.map((item) => {
      const answer = answers.value[item.id]!
      return {
        questionId: item.id,
        ...(answer.optionId ? { optionId: answer.optionId } : {}),
        ...(answer.text.trim() ? { text: answer.text.trim() } : {}),
      }
    }),
  )
}
</script>

<template>
  <form
    class="my-3 space-y-3 rounded-lg border border-gray-200 px-3 py-3 text-xs"
    aria-label="助手的问题"
    :aria-busy="submitting || undefined"
    @submit.prevent="submit"
  >
    <p class="font-medium text-gray-600">
      {{ answered ? '已回答' : pending ? '请确认以下问题' : '本轮未回答' }}
    </p>
    <fieldset
      v-for="(item, index) in question.questions"
      :key="item.id"
      class="min-w-0 space-y-2"
      :disabled="!pending || submitting"
    >
      <legend class="mb-2 break-words leading-5 text-gray-700">
        {{ index + 1 }}. {{ item.title }}
      </legend>
      <p v-if="answered" class="whitespace-pre-wrap break-words leading-5 text-gray-500">
        {{ answerText(item.id) }}
      </p>
      <template v-else-if="pending">
        <label
          v-for="option in item.options"
          :key="option.id"
          class="flex cursor-pointer items-start gap-2 rounded-lg border border-gray-200 px-2.5 py-2 leading-5 text-gray-600 has-[:checked]:border-gray-400 has-[:checked]:bg-gray-50"
        >
          <input
            v-model="answers[item.id]!.optionId"
            type="radio"
            :name="`${question.id}-${item.id}`"
            :value="option.id"
            class="mt-1 shrink-0 accent-gray-900"
          />
          <span class="min-w-0 break-words">{{ option.label }}</span>
        </label>
        <label
          v-if="item.allowCustomAnswer && item.options.length"
          class="flex items-center gap-2 text-gray-500"
        >
          <input
            v-model="answers[item.id]!.optionId"
            type="radio"
            :name="`${question.id}-${item.id}`"
            value=""
            class="accent-gray-900"
          />
          自己填写
        </label>
        <label
          v-if="item.allowCustomAnswer || !item.options.length"
          class="block space-y-1.5 text-gray-500"
        >
          <span>{{ answers[item.id]!.optionId ? '补充说明（选填）' : '你的回答' }}</span>
          <textarea
            v-model="answers[item.id]!.text"
            :aria-label="`${item.title}：${answers[item.id]!.optionId ? '补充说明' : '你的回答'}`"
            class="apb-input block min-h-16 w-full resize-y text-xs leading-5"
            :maxlength="2000"
            rows="2"
          />
        </label>
        <p
          v-if="attempted && invalid.some((question) => question.id === item.id)"
          class="text-red-600"
          role="alert"
        >
          请选择一项或填写回答，最多 2000 字。
        </p>
      </template>
    </fieldset>
    <template v-if="pending && !answered">
      <p v-if="error" class="break-words leading-5 text-red-600" role="alert">{{ error }}</p>
      <div class="flex flex-wrap items-center gap-2">
        <button
          type="submit"
          class="apb-btn-primary h-7 gap-1.5 px-2.5 text-xs"
          :disabled="submitting"
        >
          <IconSpinner v-if="submitting" class="animate-spin" />
          {{ submitting ? '正在提交…' : error ? '重新提交' : '提交回答' }}
        </button>
        <span class="text-gray-400">提交后继续生成</span>
      </div>
    </template>
  </form>
</template>
