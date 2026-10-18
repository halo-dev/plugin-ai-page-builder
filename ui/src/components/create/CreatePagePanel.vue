<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMutation } from '@tanstack/vue-query'
import { useLocalStorage } from '@vueuse/core'
import { Toast } from '@halo-dev/components'
import { aiPageBuilderAiApi, aiPageBuilderApi } from '../../api/clients'
import { errorMessage } from '../../utils/error'
import { pageKeys, queryClient } from '../../lib/query-client'
import { promptPresetGroups } from '../../data/promptPresets'
import ChatComposer from '../chat/ChatComposer.vue'
import ManualCreateModal from './ManualCreateModal.vue'
import ImportPageModal from './ImportPageModal.vue'

defineProps<{ autofocus?: boolean }>()

const router = useRouter()
const prompt = ref('')
// Same storage key as the editor chat, so the choice carries over.
const model = useLocalStorage('ai-page-builder:model-name', '')
const themeLayout = ref(true)
const available = ref(true)
const statusMessage = ref('')
const manualOpen = ref(false)
const importOpen = ref(false)

onMounted(async () => {
  try {
    const status = (await aiPageBuilderAiApi.getAiPageBuilderAiStatus()).data
    available.value = status.available
    statusMessage.value = status.message
  } catch (e) {
    available.value = false
    statusMessage.value = errorMessage(e)
  }
})

const createMutation = useMutation({
  mutationFn: async () =>
    (
      await aiPageBuilderApi.createPage({
        createPageRequest: { title: '未命名页面', themeLayout: themeLayout.value },
      })
    ).data,
  onSuccess: (detail) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    void router.push({
      name: 'AiPageBuilderEditor',
      params: { name: detail.page.name },
      // history.state handoff: the editor starts the assistant once, without URL noise.
      state: { aiPrompt: prompt.value.trim(), aiModel: model.value },
    })
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
function submit() {
  if (!createMutation.isPending.value) createMutation.mutate()
}
</script>

<template>
  <div class="mx-auto flex w-full max-w-2xl flex-col px-4">
    <h1 class="text-center text-2xl font-semibold text-gray-900">想做一个什么页面？</h1>
    <p class="mt-2 text-center text-sm text-gray-500">描述你的想法，AI 会立即开始生成页面。</p>
    <div class="mt-6">
      <ChatComposer
        v-model:prompt="prompt"
        v-model:model="model"
        :autofocus="autofocus"
        :disabled="createMutation.isPending.value"
        :can-send="!!prompt.trim()"
        :running="false"
        :can-stop="false"
        :available="available"
        placeholder="描述想要的页面，AI 将立即开始生成…（Enter 发送，Shift Enter 换行）"
        @send="submit"
      >
        <template #footer>
          <button
            type="button"
            :aria-pressed="themeLayout"
            class="shrink-0 rounded-full border px-2.5 py-1 text-xs transition-colors"
            :class="
              themeLayout
                ? 'border-accent bg-accent/10 text-accent'
                : 'border-gray-200 text-gray-500 hover:bg-gray-50'
            "
            v-tooltip="'开启后页面套用主题的页头页尾与基础样式'"
            @click="themeLayout = !themeLayout"
          >
            主题布局
          </button>
        </template>
      </ChatComposer>
      <p v-if="!available" class="mt-2 text-xs text-amber-600">{{ statusMessage }}</p>
    </div>
    <div class="mt-6 max-h-52 space-y-3 overflow-y-auto pr-1">
      <div v-for="group in promptPresetGroups" :key="group.label">
        <p class="mb-1.5 text-xs text-gray-400">{{ group.label }}</p>
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="preset in group.presets"
            :key="preset.label"
            type="button"
            class="rounded-full border border-gray-200 px-2.5 py-1 text-xs text-gray-600 transition-colors hover:border-gray-300 hover:bg-gray-50 hover:text-gray-900"
            @click="prompt = preset.prompt"
          >
            {{ preset.label }}
          </button>
        </div>
      </div>
    </div>
    <div class="mt-6 flex justify-center gap-4">
      <button
        type="button"
        class="text-xs text-gray-400 underline hover:text-gray-600"
        @click="manualOpen = true"
      >
        手动创建页面
      </button>
      <button
        type="button"
        class="text-xs text-gray-400 underline hover:text-gray-600"
        @click="importOpen = true"
      >
        导入页面
      </button>
    </div>
    <ManualCreateModal v-if="manualOpen" @close="manualOpen = false" />
    <ImportPageModal
      v-if="importOpen"
      :ai-available="available"
      :ai-status-message="statusMessage"
      @close="importOpen = false"
    />
  </div>
</template>
