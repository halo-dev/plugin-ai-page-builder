<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation } from '@tanstack/vue-query'
import { useLocalStorage } from '@vueuse/core'
import { Toast } from '@halo-dev/components'
import {
  aiPageBuilderAiApi,
  aiPageBuilderApi,
  aiPageBuilderChatApi,
  type SilentRequestConfig,
} from '../../api/clients'
import { errorMessage } from '../../utils/error'
import { pageKeys, queryClient } from '../../lib/query-client'
import { markRunStarted } from '../../lib/pending-runs'
import { promptPresetGroups } from '../../data/promptPresets'
import ChatComposer from '../chat/ChatComposer.vue'
import ManualCreateModal from './ManualCreateModal.vue'
import ImportPageModal from './ImportPageModal.vue'

defineProps<{ autofocus?: boolean }>()

const route = useRoute()
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
  mutationFn: async () => {
    const detail = (
      await aiPageBuilderApi.createPage({
        createPageRequest: { title: '未命名页面', themeLayout: themeLayout.value },
      })
    ).data
    const text = prompt.value.trim()
    // Start on the server so the run survives leaving the editor immediately.
    if (!text || !model.value.trim() || !available.value) return { detail, started: false }
    const runId = crypto.randomUUID()
    try {
      await aiPageBuilderChatApi.startPageChatRun({
        name: detail.page.name,
        pageChatRequest: {
          runId,
          version: detail.page.version,
          modelName: model.value,
          prompt: text,
        },
      })
      markRunStarted(detail.page.name)
      return { detail, started: true }
    } catch (e) {
      // The run may have been admitted even though its response was lost. Query this run
      // id before letting the editor start a second generation for the same prompt.
      let status: number | undefined
      let found = false
      try {
        await aiPageBuilderChatApi.getPageChatRun({ name: detail.page.name, runId }, {
          mute: true,
          timeout: 5000,
        } as SilentRequestConfig)
        found = true
      } catch (cause) {
        status = (cause as { response?: { status?: number } }).response?.status
      }
      if (found) {
        markRunStarted(detail.page.name)
        return { detail, started: true }
      }
      // Only a definite 404 proves the start never landed; anything else stays uncertain
      // and must not authorize another generation.
      return status === 404
        ? { detail, started: false, startError: e }
        : { detail, started: false, unknown: true, startError: e }
    }
  },
  onSuccess: ({ detail, started, startError, unknown }) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    if (started) {
      Toast.success('已开始生成，可在页面列表查看进度')
      if (route.name !== 'AiPageBuilderPages') {
        void router.push({ name: 'AiPageBuilderPages' })
      }
      return
    }
    if (unknown) {
      // Submitting the prompt again could generate twice, so let the user inspect first.
      Toast.info('无法确认生成是否已开始，请在编辑器中检查会话。')
      void router.push({ name: 'AiPageBuilderEditor', params: { name: detail.page.name } })
      return
    }
    if (startError) Toast.error(errorMessage(startError))
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
