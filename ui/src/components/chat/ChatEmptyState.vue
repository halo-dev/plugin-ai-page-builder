<script setup lang="ts">
import { computed } from 'vue'
import IconSparkles from '~icons/mingcute/sparkles-line'
import { pageAdjustmentPresets, promptPresetGroups } from '../../data/promptPresets'

const props = defineProps<{
  blankPage: boolean
  disabled: boolean
  available: boolean
}>()
const emit = defineEmits<{ pick: [prompt: string] }>()

const suggestionGroups = computed(() =>
  props.blankPage ? promptPresetGroups : [{ label: '常见调整', presets: pageAdjustmentPresets }],
)
const activePresetGroup = defineModel<number>('activePresetGroup', { default: 0 })
const activePresets = computed(
  () =>
    (suggestionGroups.value[activePresetGroup.value] || suggestionGroups.value[0])?.presets || [],
)
</script>

<template>
  <div class="flex min-h-full flex-col items-center justify-center gap-3 px-1 py-6 text-center">
    <span class="grid h-11 w-11 place-items-center rounded-2xl bg-gray-900 text-white">
      <IconSparkles class="text-xl" />
    </span>
    <h2 class="text-base font-semibold">{{ blankPage ? '从一个想法开始' : '想调整哪里？' }}</h2>
    <p class="max-w-64 text-xs leading-5 text-gray-500">
      {{
        blankPage
          ? '描述想要的页面，或从下面的页面类型开始。生成内容会先保存为草稿，也可以直接在右侧画布中编辑。'
          : '告诉助手需要修改的地方，或从下面的常见调整开始。每次修改都会先保存为草稿。'
      }}
    </p>
    <div class="mt-1 w-full">
      <div v-if="suggestionGroups.length > 1" class="mb-2 flex flex-wrap justify-center gap-1">
        <button
          v-for="(group, index) in suggestionGroups"
          :key="group.label"
          type="button"
          class="rounded-full px-2.5 py-1 text-[11px] transition-colors"
          :class="
            index === activePresetGroup
              ? 'bg-gray-900 text-white'
              : 'text-gray-500 hover:bg-gray-100 hover:text-gray-800'
          "
          :aria-pressed="index === activePresetGroup"
          @click="activePresetGroup = index"
        >
          {{ group.label }}
        </button>
      </div>
      <p v-else class="mb-1.5 text-[11px] text-gray-400">{{ suggestionGroups[0]?.label }}</p>
      <div class="flex flex-wrap justify-center gap-1.5">
        <button
          v-for="preset in activePresets"
          :key="preset.label"
          type="button"
          class="rounded-full border border-gray-200 px-2.5 py-1 text-xs text-gray-600 transition-colors hover:border-gray-300 hover:bg-gray-50 hover:text-gray-900 disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="disabled || !available"
          @click="emit('pick', preset.prompt)"
        >
          {{ preset.label }}
        </button>
      </div>
    </div>
  </div>
</template>
