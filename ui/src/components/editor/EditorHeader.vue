<script setup lang="ts">
import { computed } from 'vue'
import IconArrowLeft from '~icons/mingcute/arrow-left-line'
import IconUndo from '~icons/mingcute/back-line'
import IconRedo from '~icons/mingcute/forward-line'
import IconSettings from '~icons/mingcute/settings-3-line'
import IconHistory from '~icons/mingcute/history-line'
import IconExternal from '~icons/mingcute/external-link-line'
import IconSave from '~icons/mingcute/save-2-line'
import IconRocket from '~icons/mingcute/rocket-line'
import IconSpinner from '~icons/mingcute/loading-line'
import type { PageSummary } from '../../api/generated'
import type { PageWrites } from '../../composables/usePageWrites'
import PublishPagePopper from '../PublishPagePopper.vue'

const props = defineProps<{
  page?: PageSummary
  title: string
  busy: boolean
  locked: boolean
  dirty: boolean
  valid: boolean
  canUndo: boolean
  canRedo: boolean
  ready: boolean
  publicUrl: string
  scripted: boolean
  writes: PageWrites
}>()
const emit = defineEmits<{
  back: []
  undo: []
  redo: []
  settings: []
  history: []
  preview: []
  save: []
  publish: []
  'open-public': []
}>()

const status = computed(() => {
  const page = props.page
  if (page?.publishedRevision) {
    return page.hasUnpublishedChanges
      ? { chip: 'bg-amber-50 text-amber-700', text: '有未发布修改' }
      : { chip: 'bg-emerald-50 text-emerald-700', text: '已发布' }
  }
  return { chip: 'bg-gray-100 text-gray-500', text: '未发布' }
})
</script>

<template>
  <header
    class="flex shrink-0 flex-wrap items-center gap-1.5 border-b border-gray-200 bg-white px-3 py-1.5 md:h-12 md:flex-nowrap md:py-0"
  >
    <button
      type="button"
      class="apb-icon-btn"
      aria-label="返回页面列表"
      v-tooltip="'返回页面列表'"
      @click="emit('back')"
    >
      <IconArrowLeft class="text-base" />
    </button>
    <div class="min-w-0">
      <h1 class="max-w-56 truncate text-[13px] font-semibold leading-4">{{ title }}</h1>
    </div>
    <span v-if="page" class="apb-chip ml-1 shrink-0" :class="status.chip">
      {{ status.text }}
    </span>
    <button
      v-if="publicUrl"
      type="button"
      class="apb-chip max-w-44 shrink-0 bg-blue-50 text-blue-700 hover:bg-blue-100"
      v-tooltip="`打开公开页面：${publicUrl}`"
      @click="emit('open-public')"
    >
      <IconExternal class="shrink-0 text-xs" />
      <span class="truncate">/{{ page?.slug }}</span>
    </button>
    <div
      v-if="page && ready"
      class="ml-auto flex max-w-full items-center gap-1 overflow-x-auto max-md:w-full"
    >
      <button
        type="button"
        class="apb-icon-btn"
        :disabled="locked || !canUndo"
        v-tooltip="'撤销（⌘/Ctrl Z）'"
        aria-label="撤销"
        @click="emit('undo')"
      >
        <IconUndo class="text-base" />
      </button>
      <button
        type="button"
        class="apb-icon-btn"
        :disabled="locked || !canRedo"
        v-tooltip="'重做（⌘/Ctrl Shift Z）'"
        aria-label="重做"
        @click="emit('redo')"
      >
        <IconRedo class="text-base" />
      </button>
      <span class="mx-1 h-4 w-px bg-gray-200" />
      <button
        type="button"
        class="apb-icon-btn"
        :disabled="locked"
        v-tooltip="'页面设置'"
        aria-label="页面设置"
        @click="emit('settings')"
      >
        <IconSettings class="text-base" />
      </button>
      <button
        type="button"
        class="apb-icon-btn"
        :disabled="locked"
        v-tooltip="'历史版本'"
        aria-label="历史版本"
        @click="emit('history')"
      >
        <IconHistory class="text-base" />
      </button>
      <button
        type="button"
        class="apb-icon-btn"
        :disabled="locked"
        v-tooltip="'预览（新窗口）'"
        aria-label="预览"
        @click="emit('preview')"
      >
        <IconExternal class="text-base" />
      </button>
      <span class="mx-1 h-4 w-px bg-gray-200" />
      <button
        type="button"
        class="apb-btn-outline"
        :disabled="locked || !dirty || !valid"
        @click="emit('save')"
      >
        <IconSpinner v-if="busy" class="animate-spin text-sm" />
        <IconSave v-else class="text-sm" />
        保存
      </button>
      <PublishPagePopper
        v-if="!page.publishedRevision"
        :name="page.name"
        :title="page.title"
        :slug="page.slug"
        :version="page.version"
        :disabled="locked || dirty || !valid"
        :scripted="scripted"
        :writes="writes"
      >
        <button type="button" class="apb-btn-primary" :disabled="locked || dirty || !valid">
          <IconRocket class="text-sm" />
          发布
        </button>
      </PublishPagePopper>
      <button
        v-else
        type="button"
        class="apb-btn-primary"
        :disabled="locked || dirty || !valid || busy"
        @click="emit('publish')"
      >
        <IconRocket class="text-sm" />
        更新发布
      </button>
    </div>
  </header>
</template>
