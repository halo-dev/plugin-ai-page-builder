<script setup lang="ts">
import type { Component } from 'vue'
import IconBookmark from '~icons/mingcute/bookmark-line'
import IconBrain from '~icons/mingcute/brain-line'
import IconChat from '~icons/mingcute/chat-1-line'
import IconCode from '~icons/mingcute/code-line'
import IconEdit from '~icons/mingcute/edit-line'
import IconEye from '~icons/mingcute/eye-line'
import IconFile from '~icons/mingcute/file-line'
import IconFolder from '~icons/mingcute/folder-line'
import IconGlobe from '~icons/mingcute/globe-line'
import IconInformation from '~icons/mingcute/information-line'
import IconLink from '~icons/mingcute/link-line'
import IconListCheck from '~icons/mingcute/list-check-line'
import IconPalette from '~icons/mingcute/palette-line'
import IconQuestion from '~icons/mingcute/question-line'
import IconSearch from '~icons/mingcute/search-line'
import IconSparkles from '~icons/mingcute/sparkles-line'
import IconText from '~icons/mingcute/text-line'
import type { PageChatAnswerItem } from '../../api/generated'
import { reasoningSummary, type DisplayPart } from '../../editor/chat-display'
import ChatDisclosureRow from './ChatDisclosureRow.vue'
import ChatMarkdown from './ChatMarkdown.vue'
import ChatQuestions from './ChatQuestions.vue'

defineProps<{
  parts: DisplayPart[]
  pendingQuestionId?: string
  submitting?: boolean
  answerError?: string
}>()
const emit = defineEmits<{ answer: [questionId: string, answers: PageChatAnswerItem[]] }>()

// Each step carries a glyph for what it touches, the way the harness rows do.
const TOOL_ICONS: Record<string, Component> = {
  read_current_page: IconEye,
  get_theme_context: IconPalette,
  update_current_page: IconEdit,
  update_page_title: IconEdit,
  update_page_description: IconEdit,
  patch_current_page: IconEdit,
  set_page_slug: IconLink,
  rename_session: IconChat,
  list_theme_files: IconFolder,
  read_theme_file: IconFile,
  search_theme_file: IconSearch,
  list_posts: IconFile,
  list_single_pages: IconFile,
  list_ai_pages: IconFile,
  read_ai_page: IconFile,
  read_content: IconText,
  get_site_profile: IconInformation,
  get_navigation: IconListCheck,
  search_attachments: IconSearch,
  fetch_webpage: IconGlobe,
  search_public_apis: IconSearch,
  get_public_api: IconCode,
  ask_user: IconQuestion,
  update_site_memory: IconBookmark,
}

function toolIcon(name: string): Component {
  return TOOL_ICONS[name] || IconSparkles
}
</script>

<template>
  <template v-for="part in parts" :key="part.id">
    <ChatMarkdown v-if="part.type === 'text'" :content="part.text" :final="!part.active" />
    <ChatDisclosureRow
      v-else-if="part.type === 'reasoning' && part.text"
      class="my-0.5"
      :icon="IconBrain"
      title="思考"
      :summary="reasoningSummary(part.text, part.active)"
      :running="part.active"
      expandable
    >
      <div class="apb-reasoning-body">
        <ChatMarkdown :content="part.text" :final="!part.active" />
      </div>
    </ChatDisclosureRow>
    <template v-else-if="'tool' in part && part.tool">
      <ChatDisclosureRow
        class="my-0.5"
        :icon="toolIcon(part.tool.name)"
        :title="part.tool.label"
        :summary="part.tool.tone === 'error' ? '' : part.tool.hint || part.tool.summary"
        :status="part.tool.tone === 'error' ? '失败' : part.tool.status"
        :tone="part.tool.tone"
        :running="part.tool.active"
        :expandable="part.tool.openable && part.tool.tone !== 'error'"
      >
        <p
          class="break-all text-xs leading-5"
          :class="part.tool.tone === 'error' ? 'text-red-500' : 'text-gray-500'"
        >
          {{ part.tool.summary }}
        </p>
      </ChatDisclosureRow>
      <p
        v-if="part.tool.tone === 'error'"
        class="mb-1 break-all pl-[22px] text-xs leading-5 text-red-500"
        data-tool-error
      >
        {{ part.tool.summary }}
      </p>
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

<style scoped>
/* Thinking reads one step below the answer: compact, muted, and never louder
   than the row that opened it. */
.apb-reasoning-body :deep(.apb-md) {
  color: #6b7280;
  font-size: 13px;
  line-height: 21px;
}
.apb-reasoning-body :deep(.apb-md p) {
  margin: 0.4em 0;
}
</style>
