<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMutation } from '@tanstack/vue-query'
import { VButton, VModal, VSpace } from '@halo-dev/components'
import IconClose from '~icons/mingcute/close-line'
import { aiPageBuilderApi } from '../../api/clients'
import { pageKeys, queryClient } from '../../lib/query-client'
import { errorMessage } from '../../utils/error'

const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const file = ref<File>()
const error = ref('')
// Lock immediately, including repeated clicks before the mutation state updates.
const pending = ref(false)
const importMutation = useMutation({
  mutationFn: async (file: File) => (await aiPageBuilderApi.importPage({ body: file })).data,
  onSuccess: (detail) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    void router.push({ name: 'AiPageBuilderEditor', params: { name: detail.page.name } })
  },
  onError: (e) => (error.value = errorMessage(e)),
  onSettled: () => (pending.value = false),
  queryClient,
})

function selectFile(event: Event) {
  if (pending.value) return
  file.value = (event.target as HTMLInputElement).files?.[0]
  error.value = ''
}

function submit() {
  if (pending.value) return
  if (!file.value) {
    error.value = '请选择 ZIP 文件。'
    return
  }
  if (!file.value.name.toLowerCase().endsWith('.zip')) {
    error.value = '请选择 .zip 格式的文件。'
    return
  }
  if (file.value.size > 4 * 1024 * 1024) {
    error.value = 'ZIP 文件大小不能超过 4 MiB。'
    return
  }
  error.value = ''
  pending.value = true
  importMutation.mutate(file.value)
}

function close() {
  if (!pending.value) emit('close')
}

function guardEscape(event: KeyboardEvent) {
  if (pending.value) event.stopImmediatePropagation()
}
</script>

<template>
  <VModal
    title="导入页面"
    :width="500"
    :layer-closable="false"
    @keyup.esc.capture="guardEscape"
    @close="close"
  >
    <template #header>
      <div class="modal-header-title">导入页面</div>
      <button
        type="button"
        aria-label="关闭"
        class="rounded p-1 text-gray-500 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="pending"
        @click="close"
      >
        <IconClose />
      </button>
    </template>
    <div>
      <label for="page-archive" class="mb-2 block text-sm font-medium text-gray-700">
        ZIP 文件
      </label>
      <input
        id="page-archive"
        type="file"
        accept=".zip"
        class="block w-full text-sm text-gray-700"
        :disabled="pending"
        @change="selectFile"
      />
      <p class="mt-3 rounded-md bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-700">
        页面包可能包含 JavaScript
        脚本。导入不会自动发布；发布后脚本会在访客浏览器中执行，请确认来源可信。
      </p>
      <p v-if="error" role="alert" class="mt-3 text-sm text-red-600">{{ error }}</p>
    </div>
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="pending" :disabled="pending" @click="submit">
          导入
        </VButton>
        <VButton :disabled="pending" @click="close">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
