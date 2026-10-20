<script setup lang="ts">
import { VDropdown, VDropdownItem } from '@halo-dev/components'
import IconSparkles from '~icons/mingcute/sparkles-line'
import IconAdd from '~icons/mingcute/add-line'
import IconMore from '~icons/mingcute/more-2-line'
import IconArrowDown from '~icons/mingcute/down-line'
import IconCheck from '~icons/mingcute/check-line'
import type { PageChatSessionSummary } from '../../api/generated'

defineProps<{
  sessions: PageChatSessionSummary[]
  currentSession?: PageChatSessionSummary
  sessionId?: string
  defaultSession: boolean
  busy: boolean
  disabled: boolean
}>()
const emit = defineEmits<{
  select: [sessionId: string]
  create: []
  delete: []
}>()
</script>

<template>
  <header class="flex h-12 shrink-0 items-center gap-1 border-b border-gray-100 px-3">
    <IconSparkles class="text-base text-gray-700" />
    <VDropdown>
      <button
        type="button"
        class="apb-btn-ghost h-7 max-w-44 gap-1 px-2 text-[13px]"
        :disabled="busy || disabled"
        :aria-label="`当前会话：${currentSession?.title || '新会话'}，点击切换`"
      >
        <span class="truncate">{{ currentSession?.title || '新会话' }}</span>
        <IconArrowDown class="shrink-0 text-xs text-gray-400" />
      </button>
      <template #popper>
        <VDropdownItem
          v-for="session in sessions"
          :key="session.sessionId"
          v-close-popper
          @click="emit('select', session.sessionId)"
        >
          <template #prefix-icon>
            <IconCheck v-if="session.sessionId === sessionId" class="text-xs text-emerald-600" />
            <span v-else class="w-3.5 shrink-0" aria-hidden="true" />
          </template>
          {{ session.title || '新会话' }}
        </VDropdownItem>
      </template>
    </VDropdown>
    <div class="ml-auto flex items-center">
      <button
        type="button"
        class="apb-icon-btn h-7 w-7"
        v-tooltip="'新建会话'"
        aria-label="新建会话"
        :disabled="busy || disabled"
        @click="emit('create')"
      >
        <IconAdd class="text-base" />
      </button>
      <VDropdown class="flex">
        <button
          type="button"
          class="apb-icon-btn h-7 w-7"
          v-tooltip="'会话操作'"
          aria-label="会话操作"
          :disabled="busy || disabled || !sessionId"
        >
          <IconMore class="text-base" />
        </button>
        <template #popper>
          <VDropdownItem v-close-popper type="danger" @click="emit('delete')">
            {{ defaultSession ? '清空会话' : '删除会话' }}
          </VDropdownItem>
        </template>
      </VDropdown>
    </div>
  </header>
</template>
