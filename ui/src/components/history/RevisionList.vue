<script setup lang="ts">
import IconSparkles from '~icons/mingcute/sparkles-line'
import IconEdit from '~icons/mingcute/edit-2-line'
import IconHistory from '~icons/mingcute/history-line'
import type { RevisionSummary } from '../../api/generated'
import { fullTime, sourceLabel, timeAgo } from '../../editor/revision-display'

defineProps<{
  revisions: RevisionSummary[]
  selectedName?: string
  draftRevision?: string
  publishedRevision?: string | null
}>()
const emit = defineEmits<{ select: [revision: RevisionSummary] }>()

const sourceIcon = (source: string) =>
  source === 'ai' ? IconSparkles : source === 'restore' ? IconHistory : IconEdit
</script>

<template>
  <nav
    class="max-h-44 shrink-0 overflow-auto border-b border-gray-100 p-1.5 md:max-h-none md:w-60 md:border-b-0 md:border-r"
    aria-label="历史版本列表"
  >
    <div class="flex flex-col gap-0.5">
      <button
        v-for="revision in revisions"
        :key="revision.name"
        type="button"
        class="relative flex w-full flex-col gap-1 rounded-md px-2.5 py-2 text-left transition-colors"
        :class="selectedName === revision.name ? 'bg-gray-100' : 'hover:bg-gray-50'"
        :aria-pressed="selectedName === revision.name"
        @click="emit('select', revision)"
      >
        <span
          v-if="selectedName === revision.name"
          class="absolute inset-y-2 left-0 w-0.5 rounded-full bg-gray-900"
        />
        <span class="flex items-center gap-1.5">
          <strong class="min-w-0 flex-1 truncate text-[13px] font-medium text-gray-900">{{
            revision.title
          }}</strong>
          <span
            v-if="revision.name === draftRevision"
            class="shrink-0 rounded bg-blue-50 px-1 text-[10px] leading-4 text-blue-600"
            >当前草稿</span
          >
          <span
            v-if="revision.name === publishedRevision"
            class="shrink-0 rounded bg-green-50 px-1 text-[10px] leading-4 text-green-600"
            >已发布</span
          >
        </span>
        <span
          class="flex items-center gap-1 text-[11px] text-gray-400"
          :title="fullTime(revision.createdAt)"
        >
          <component :is="sourceIcon(revision.source)" class="shrink-0" />
          <span class="truncate"
            >{{ sourceLabel(revision.source) }} · {{ timeAgo(revision.createdAt) }} ·
            {{ revision.createdBy }}</span
          >
        </span>
      </button>
    </div>
  </nav>
</template>
