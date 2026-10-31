<script setup lang="ts">
import type { Component } from 'vue'
import IconRefresh from '~icons/mingcute/refresh-2-line'
import IconEdit from '~icons/mingcute/edit-2-line'
import IconCode from '~icons/mingcute/code-line'
import IconPlay from '~icons/mingcute/play-line'
import IconDesktop from '~icons/mingcute/computer-line'
import IconMobile from '~icons/mingcute/cellphone-line'
import IconTablet from '~icons/mingcute/pad-line'
import type { PreviewDevice } from '../../editor/preview-canvas'
import ApbSegmented from '../ui/ApbSegmented.vue'

defineProps<{
  locked: boolean
  saving: boolean
  dirty: boolean
  scripted: boolean
  hasStreamPreview: boolean
  rootStateCount: number
  previewModeIcon: { icon: Component; title: string }
  themeName?: string
  themeLayout: boolean
}>()
const emit = defineEmits<{ refresh: []; 'cycle-preview-mode': []; settings: [] }>()
const view = defineModel<'visual' | 'source' | 'runtime'>('view', { required: true })
const device = defineModel<PreviewDevice>('device', { required: true })

const viewOptions = [
  { value: 'visual', label: '编辑', icon: IconEdit },
  { value: 'source', label: '源码', icon: IconCode },
  { value: 'runtime', label: '运行', icon: IconPlay, title: '运行已保存的草稿，脚本在沙箱中执行' },
] as const
const deviceOptions = [
  {
    value: 'desktop',
    label: '桌面',
    icon: IconDesktop,
    title: '桌面 1280px',
    labelClass: 'md:hidden',
  },
  {
    value: 'tablet',
    label: '平板',
    icon: IconTablet,
    title: '平板 768px',
    labelClass: 'md:hidden',
  },
  {
    value: 'mobile',
    label: '手机',
    icon: IconMobile,
    title: '手机 390px',
    labelClass: 'md:hidden',
  },
] as const
</script>

<template>
  <div
    class="flex shrink-0 flex-wrap items-center gap-2 border-b border-gray-200 bg-white px-3 py-1.5 xl:h-11 xl:flex-nowrap xl:py-0"
  >
    <ApbSegmented
      v-model="view"
      :options="viewOptions"
      :disabled="locked"
      touch
      aria-label="编辑视图"
    />
    <span
      class="apb-chip"
      :class="
        saving
          ? 'bg-blue-50 text-blue-700'
          : dirty
            ? 'bg-amber-50 text-amber-700'
            : 'bg-emerald-50 text-emerald-700'
      "
    >
      <span
        class="h-1.5 w-1.5 rounded-full"
        :class="saving ? 'bg-blue-500' : dirty ? 'bg-amber-500' : 'bg-emerald-500'"
      />
      草稿：{{ saving ? '保存中…' : dirty ? '未保存' : '已保存' }}
    </span>
    <button
      type="button"
      class="apb-chip bg-gray-50 text-gray-600 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
      :disabled="locked"
      :title="`${themeName ? `当前主题：${themeName}` : '未识别当前主题'}；${
        themeLayout ? '复用主题布局' : '独立渲染，不套用主题的页头页尾'
      }。点击打开页面设置。`"
      @click="emit('settings')"
    >
      <span class="max-w-32 truncate">主题：{{ themeName || '未识别' }}</span>
      <span aria-hidden="true">·</span>
      <span>{{ themeLayout ? '复用布局' : '独立渲染' }}</span>
    </button>
    <span
      v-if="scripted"
      class="apb-chip bg-purple-50 text-purple-700"
      title="页面包含 JavaScript，编辑画布不执行脚本；交互效果请在「运行」中查看，发布后会在访客浏览器中执行"
      >含脚本</span
    >
    <span
      v-if="view === 'visual' && !hasStreamPreview && scripted"
      class="hidden text-xs text-gray-500 lg:inline"
      title="编辑画布不执行页面脚本，交互效果请在「运行」中查看"
      >脚本仅在「运行」中执行</span
    >
    <span
      v-if="view === 'runtime' && dirty"
      class="apb-chip bg-amber-50 text-amber-700"
      title="运行预览只读取已保存的草稿，请先保存再查看最新修改"
      >运行的是已保存草稿</span
    >
    <div class="ml-auto flex items-center gap-2">
      <button
        v-if="view === 'runtime'"
        type="button"
        class="apb-icon-btn"
        v-tooltip="'重新运行'"
        aria-label="重新运行"
        @click="emit('refresh')"
      >
        <IconRefresh class="text-base" />
      </button>
      <button
        v-if="rootStateCount && view !== 'runtime'"
        type="button"
        class="apb-icon-btn"
        v-tooltip="previewModeIcon.title"
        :aria-label="previewModeIcon.title"
        @click="emit('cycle-preview-mode')"
      >
        <component :is="previewModeIcon.icon" class="text-base" />
      </button>
      <ApbSegmented
        v-if="view !== 'source' || hasStreamPreview"
        v-model="device"
        :options="deviceOptions"
        touch
        aria-label="预览设备"
      />
    </div>
  </div>
</template>
