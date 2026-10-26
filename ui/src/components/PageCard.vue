<script setup lang="ts">
import { computed } from 'vue'
import {
  VButton,
  VDropdown,
  VDropdownDivider,
  VDropdownItem,
  VStatusDot,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import IconMore from '~icons/mingcute/more-2-line'
import type { PageChatActiveRun, PageSummary, ThemeRef } from '../api/generated'
import PagePreviewCard from './PagePreviewCard.vue'
import PublishPagePopper from './PublishPagePopper.vue'

const props = defineProps<{
  item: PageSummary
  currentTheme?: ThemeRef | null
  run?: PageChatActiveRun
  disabled?: boolean
}>()
const emit = defineEmits<{
  open: [item: PageSummary]
  preview: [item: PageSummary]
  visit: [item: PageSummary]
  settings: [item: PageSummary]
  stop: [item: PageSummary]
  update: [item: PageSummary]
  unpublish: [item: PageSummary]
  clone: [item: PageSummary]
  export: [item: PageSummary]
  remove: [item: PageSummary]
}>()

const status = computed(() =>
  props.item.publishedRevision
    ? props.item.hasUnpublishedChanges
      ? { state: 'warning' as const, text: '有未发布修改' }
      : { state: 'success' as const, text: '已发布' }
    : { state: 'default' as const, text: '未发布' },
)
const generating = computed(() => !!props.run)
const runStatus = computed(() => {
  switch (props.run?.status) {
    case 'waiting_for_user':
      return { text: '等待回答', class: 'bg-amber-50 text-amber-700' }
    case 'committing':
      return { text: '保存中', class: 'bg-blue-50 text-blue-700' }
    default:
      return { text: 'AI 生成中', class: 'bg-blue-50 text-blue-700' }
  }
})
const runDetail = computed(() =>
  props.run?.status === 'waiting_for_user'
    ? props.run.pendingQuestion?.questions?.[0]?.title || '助手正在等待你的回答'
    : '助手正在后台生成，完成后会自动保存为草稿',
)
const updatedAt = computed(() => utils.date.timeAgo(props.item.updatedAt) ?? '')
const updatedAtFull = computed(() => utils.date.format(props.item.updatedAt))
const themeWarning = computed(() => {
  const saved = props.item.publishedTheme
  const current = props.currentTheme
  if (
    !props.item.themeLayout ||
    !props.item.publishedRevision ||
    !saved ||
    !current ||
    saved.name === current.name
  ) {
    return undefined
  }
  const savedLabel = saved.displayName || saved.name
  const currentLabel = current.displayName || current.name
  return {
    text: '主题已更换',
    detail: `该页面发布于主题「${savedLabel}」，当前站点主题为「${currentLabel}」。页面样式可能已不适配，可以让 AI 适配当前主题。`,
  }
})
</script>

<template>
  <article
    class="min-w-0 overflow-hidden rounded-lg border border-gray-200 bg-white transition-shadow hover:border-gray-300 hover:shadow-md"
  >
    <PagePreviewCard
      :name="item.name"
      :revision="item.draftRevision || ''"
      :title="item.title"
      :theme-layout="item.themeLayout"
      @open="emit('open', item)"
    />
    <div class="p-4">
      <RouterLink
        :to="{ name: 'AiPageBuilderEditor', params: { name: item.name } }"
        class="block truncate text-sm font-semibold text-gray-800 hover:underline"
        >{{ item.title }}</RouterLink
      >
      <p class="mt-1 break-all text-xs text-gray-400">
        {{ item.slug ? '/' + item.slug : '未设置访问路径' }}
      </p>
      <div class="mt-3.5 flex items-center justify-between gap-2">
        <div class="flex min-w-0 flex-wrap items-center gap-2">
          <VStatusDot :state="status.state" :text="status.text" />
          <span
            v-if="run"
            v-tooltip="runDetail"
            class="shrink-0 whitespace-nowrap rounded px-1.5 py-0.5 text-[11px] leading-4"
            :class="runStatus.class"
          >
            <span
              class="mr-1 inline-block h-1.5 w-1.5 rounded-full bg-current align-middle"
              :class="{ 'animate-pulse': run.status !== 'waiting_for_user' }"
            />{{ runStatus.text }}
          </span>
          <span
            v-if="themeWarning"
            v-tooltip="themeWarning.detail"
            class="shrink-0 whitespace-nowrap rounded bg-amber-50 px-1.5 py-0.5 text-[11px] leading-4 text-amber-700"
          >
            {{ themeWarning.text }}
          </span>
        </div>
        <span v-tooltip="updatedAtFull" class="text-[11px] text-gray-400">{{ updatedAt }}</span>
      </div>
      <div class="mt-4 flex items-center gap-2 border-t border-gray-100 pt-3.5">
        <VButton
          v-tooltip="'在受保护模式下访问页面，内容是最新的版本，可能包含未发布修改'"
          size="sm"
          class="flex-1"
          @click="emit('preview', item)"
        >
          预览 ↗
        </VButton>
        <VButton
          v-if="item.publishedRevision"
          v-tooltip="'公开访问链接，内容为已发布版本'"
          size="sm"
          class="flex-1"
          @click="emit('visit', item)"
        >
          访问 ↗
        </VButton>
        <VDropdown>
          <VButton ghost size="sm" :disabled="disabled" :aria-label="`${item.title}的操作`">
            <IconMore />
          </VButton>
          <template #popper>
            <VDropdownItem
              v-if="run"
              v-close-popper
              :disabled="disabled"
              @click="emit('stop', item)"
            >
              停止生成
            </VDropdownItem>
            <VDropdownItem
              v-close-popper
              :disabled="disabled || generating"
              @click="emit('settings', item)"
            >
              设置
            </VDropdownItem>
            <PublishPagePopper
              v-if="!item.publishedRevision"
              :name="item.name"
              :title="item.title"
              :slug="item.slug"
              :version="item.version"
              :disabled="disabled || generating"
              placement="left-start"
            >
              <VDropdownItem :disabled="disabled">发布</VDropdownItem>
            </PublishPagePopper>
            <VDropdownItem
              v-else
              v-close-popper
              :disabled="disabled || generating"
              @click="emit('update', item)"
            >
              更新发布
            </VDropdownItem>
            <VDropdownItem v-close-popper :disabled="disabled" @click="emit('clone', item)">
              克隆页面
            </VDropdownItem>
            <VDropdownItem v-close-popper :disabled="disabled" @click="emit('export', item)">
              导出
            </VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem
              v-if="item.publishedRevision"
              v-close-popper
              :disabled="disabled || generating"
              type="danger"
              @click="emit('unpublish', item)"
            >
              取消发布
            </VDropdownItem>
            <VDropdownItem
              v-close-popper
              type="danger"
              :disabled="disabled || generating"
              @click="emit('remove', item)"
            >
              删除页面
            </VDropdownItem>
          </template>
        </VDropdown>
      </div>
    </div>
  </article>
</template>
