<script setup lang="ts">
import { shallowRef, useTemplateRef } from 'vue'
import IconFileZip from '~icons/mingcute/file-zip-line'
import IconUpload from '~icons/mingcute/upload-2-line'
import { formatSize } from '../../editor/import-review'

const props = withDefaults(defineProps<{ file?: File; disabled?: boolean }>(), {
  disabled: false,
})
const emit = defineEmits<{ select: [file: File] }>()
const fileInput = useTemplateRef<HTMLInputElement>('fileInput')
const dragging = shallowRef(false)

function chooseFile() {
  if (props.disabled) return
  fileInput.value?.click()
}

function selectFile(event: Event) {
  if (props.disabled) return
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0]
  if (selected) emit('select', selected)
  input.value = ''
}

function dropFile(event: DragEvent) {
  dragging.value = false
  if (props.disabled) return
  const selected = event.dataTransfer?.files?.[0]
  if (selected) emit('select', selected)
}
</script>

<template>
  <div>
    <div class="mb-2 flex items-baseline justify-between gap-2">
      <label for="page-archive" class="text-sm font-medium text-gray-700">页面包 ZIP</label>
      <span class="text-xs text-gray-400">最大 4 MiB</span>
    </div>
    <input
      id="page-archive"
      ref="fileInput"
      type="file"
      accept=".zip"
      class="hidden"
      :disabled="disabled"
      @change="selectFile"
    />
    <button
      type="button"
      class="flex w-full items-center gap-3 rounded-lg border border-dashed px-4 py-4 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-60"
      :class="
        dragging
          ? 'border-blue-400 bg-blue-50'
          : 'border-gray-300 bg-gray-50 hover:border-gray-400 hover:bg-gray-100'
      "
      :disabled="disabled"
      @click="chooseFile"
      @dragenter.prevent="dragging = true"
      @dragover.prevent
      @dragleave.prevent="dragging = false"
      @drop.prevent="dropFile"
    >
      <span
        class="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-white text-gray-500 ring-1 ring-gray-200"
      >
        <IconFileZip class="text-xl" />
      </span>
      <span class="min-w-0 flex-1">
        <span class="block truncate text-sm font-medium text-gray-900">
          {{ file?.name ?? '选择或拖拽 ZIP 文件' }}
        </span>
        <span class="mt-0.5 block text-xs text-gray-500">
          {{ file ? `${formatSize(file.size)} · 点击重新选择` : '仅支持 .zip 格式' }}
        </span>
      </span>
      <IconUpload class="shrink-0 text-lg text-gray-400" />
    </button>
  </div>
</template>
