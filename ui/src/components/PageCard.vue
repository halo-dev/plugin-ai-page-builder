<script setup lang="ts">
import { computed } from 'vue'
import { VButton, VDropdown, VDropdownItem, VStatusDot } from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import IconMore from '~icons/mingcute/more-2-line'
import type { PageSummary } from '../api/generated'
import PagePreviewCard from './PagePreviewCard.vue'
import PublishPagePopper from './PublishPagePopper.vue'

const props = defineProps<{ item: PageSummary; disabled?: boolean }>()
const emit = defineEmits<{
  open: [item: PageSummary]
  visit: [item: PageSummary]
  settings: [item: PageSummary]
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
const updatedAt = computed(() => utils.date.timeAgo(props.item.updatedAt) ?? '')
const updatedAtFull = computed(() => utils.date.format(props.item.updatedAt))
const visitLabel = computed(() => (props.item.publishedRevision ? '访问页面 ↗' : '预览 ↗'))
</script>

<template>
  <article
    class="min-w-0 overflow-hidden rounded-lg border border-gray-200 bg-white transition-shadow hover:border-gray-300 hover:shadow-md"
  >
    <PagePreviewCard
      :name="item.name"
      :revision="item.draftRevision || ''"
      :title="item.title"
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
        <VStatusDot :state="status.state" :text="status.text" />
        <span v-tooltip="updatedAtFull" class="text-[11px] text-gray-400">{{ updatedAt }}</span>
      </div>
      <div class="mt-4 flex items-center gap-2 border-t border-gray-100 pt-3.5">
        <VButton size="sm" class="flex-1" @click="emit('visit', item)">
          {{ visitLabel }}
        </VButton>
        <VDropdown>
          <VButton size="sm" :disabled="disabled" :aria-label="`${item.title}的操作`">
            <IconMore />
          </VButton>
          <template #popper>
            <VDropdownItem v-close-popper :disabled="disabled" @click="emit('settings', item)">
              设置
            </VDropdownItem>
            <PublishPagePopper
              v-if="!item.publishedRevision"
              :name="item.name"
              :title="item.title"
              :slug="item.slug"
              :version="item.version"
              :disabled="disabled"
              placement="left-start"
            >
              <VDropdownItem :disabled="disabled">发布</VDropdownItem>
            </PublishPagePopper>
            <VDropdownItem v-else v-close-popper :disabled="disabled" @click="emit('update', item)">
              更新发布
            </VDropdownItem>
            <VDropdownItem
              v-if="item.publishedRevision"
              v-close-popper
              :disabled="disabled"
              @click="emit('unpublish', item)"
            >
              取消发布
            </VDropdownItem>
            <VDropdownItem v-close-popper :disabled="disabled" @click="emit('clone', item)">
              克隆页面
            </VDropdownItem>
            <VDropdownItem v-close-popper :disabled="disabled" @click="emit('export', item)"
              >导出</VDropdownItem
            >
            <VDropdownItem
              v-close-popper
              type="danger"
              :disabled="disabled"
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
