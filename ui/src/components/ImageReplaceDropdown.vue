<script setup lang="ts">
import { consoleApiClient } from '@halo-dev/api-client'
import { Toast, VDropdown, VDropdownItem } from '@halo-dev/components'
import { utils, type AttachmentLike } from '@halo-dev/ui-shared'
import { useFileDialog } from '@vueuse/core'
import { computed, shallowRef } from 'vue'

// Two root nodes here: forward attrs (class) onto the dropdown explicitly.
defineOptions({ inheritAttrs: false })

// Same sources as the console's attachment FormKit input: upload through the
// file dialog, or pick from the global attachment selector modal.
const emit = defineEmits<{ select: [url: string] }>()

const ACCEPTS = ['image/*']

const canView = computed(() =>
  utils.permission.has(['system:attachments:view', 'uc:attachments:manage']),
)

const { open: openFileDialog, onChange } = useFileDialog({
  accept: ACCEPTS.join(','),
  multiple: false,
})
onChange(async (files) => {
  const file = files?.[0]
  if (!file) return
  try {
    const { data } = await consoleApiClient.storage.attachment.uploadAttachmentForConsole({ file })
    emitUrl(data)
  } catch (e) {
    console.error('Failed to upload image', e)
    Toast.error('图片上传失败')
  }
})

const selectorOpen = shallowRef(false)
function onSelectorSelect(attachments: AttachmentLike[]) {
  selectorOpen.value = false
  emitUrl(attachments[0])
}
function emitUrl(attachment: AttachmentLike | undefined) {
  if (!attachment) return
  const url = utils.attachment.getUrl(attachment)
  if (url) emit('select', url)
}
</script>

<template>
  <VDropdown v-bind="$attrs">
    <slot />
    <template #popper>
      <VDropdownItem @click="openFileDialog()">上传图片</VDropdownItem>
      <VDropdownItem v-if="canView" @click="selectorOpen = true">从附件库选择</VDropdownItem>
    </template>
  </VDropdown>
  <AttachmentSelectorModal
    v-if="selectorOpen"
    :max="1"
    :accepts="ACCEPTS"
    @select="onSelectorSelect"
    @close="selectorOpen = false"
  />
</template>
