<script setup lang="ts">
import { computed, ref, useTemplateRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMutation } from '@tanstack/vue-query'
import { VAlert, VButton, VModal, VSpace } from '@halo-dev/components'
import IconFileZip from '~icons/mingcute/file-zip-line'
import IconUpload from '~icons/mingcute/upload-2-line'
import { aiPageBuilderApi, type ImportReview } from '../../api/clients'
import { pageKeys, queryClient } from '../../lib/query-client'
import { errorMessage } from '../../utils/error'

const props = withDefaults(defineProps<{ aiAvailable?: boolean; aiStatusMessage?: string }>(), {
  aiAvailable: true,
  aiStatusMessage: '',
})

const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')
const file = ref<File>()
const fileInput = useTemplateRef<HTMLInputElement>('fileInput')
const dragging = ref(false)
const model = ref('')
const error = ref('')
const review = ref<ImportReview>()
const confirmed = ref(false)
const pending = ref(false)
const phase = ref<'select' | 'report'>('select')
const REVIEW_CLIENT_TIMEOUT = 11 * 60 * 1000
let reviewAttempt = 0
let reviewAbort: AbortController | undefined

const severityLabels: Record<string, string> = {
  critical: '严重',
  high: '高',
  medium: '中',
  low: '低',
}
const categoryLabels: Record<string, string> = {
  malicious_script: '恶意脚本',
  data_exfiltration: '数据外传',
  phishing: '钓鱼内容',
  obfuscation: '代码混淆',
  external_resource: '外部资源',
  prompt_injection: '提示注入',
  content_policy: '内容违规',
  other: '其他风险',
}

const isSuspicious = computed(() => review.value?.verdict === 'suspicious')
const isMalicious = computed(() => review.value?.verdict === 'malicious')
const canImport = computed(
  () => review.value?.verdict === 'safe' || (isSuspicious.value && confirmed.value),
)
const reviewFooter = computed(() =>
  isMalicious.value ? '该页面包 30 分钟内不能重新审查或导入' : '审查结果 30 分钟内有效',
)
const verdictMeta = computed<{ label: string; alertType: 'success' | 'warning' | 'error' }>(() => {
  if (review.value?.verdict === 'safe') {
    return { label: 'AI 审查通过', alertType: 'success' }
  }
  if (review.value?.verdict === 'suspicious') {
    return { label: 'AI 审查发现风险', alertType: 'warning' }
  }
  return { label: 'AI 审查判定为恶意页面，禁止导入', alertType: 'error' }
})
const primaryLabel = computed(() => {
  if (phase.value === 'select') {
    return props.aiAvailable ? '开始 AI 审查' : 'AI 审查不可用'
  }
  if (review.value?.verdict === 'safe') return '导入页面'
  if (review.value?.verdict === 'suspicious') return '仍然导入'
  if (review.value?.verdict === 'malicious') return '发现恶意内容，无法导入'
  return '无法导入'
})

const reviewMutation = useMutation({
  mutationFn: async (input: { file: File; model?: string; attempt: number; signal: AbortSignal }) =>
    (
      await aiPageBuilderApi.reviewPageImport(
        { body: input.file, model: input.model },
        { signal: input.signal, timeout: REVIEW_CLIENT_TIMEOUT },
      )
    ).data,
  onSuccess: (data, input) => {
    if (input.attempt !== reviewAttempt) return
    review.value = data
    confirmed.value = false
    error.value = ''
    phase.value = 'report'
  },
  onError: (e, input) => {
    if (input.attempt !== reviewAttempt) return
    review.value = undefined
    phase.value = 'select'
    error.value = errorMessage(e)
  },
  onSettled: (_data, _error, input) => {
    if (input.attempt === reviewAttempt) pending.value = false
  },
  queryClient,
})

const importMutation = useMutation({
  mutationFn: async (input: { file: File; reviewId: string; confirmed: boolean }) =>
    (
      await aiPageBuilderApi.importPage({
        body: input.file,
        reviewId: input.reviewId,
        ...(input.confirmed ? { confirmed: true } : {}),
      })
    ).data,
  onSuccess: (detail) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    void router.push({ name: 'AiPageBuilderEditor', params: { name: detail.page.name } })
  },
  onError: (e) => (error.value = errorMessage(e)),
  onSettled: () => (pending.value = false),
  queryClient,
})

function validateFile(selected: File) {
  if (!selected.name.toLowerCase().endsWith('.zip')) {
    return '请选择 .zip 格式的文件。'
  }
  if (selected.size > 4 * 1024 * 1024) {
    return 'ZIP 文件大小不能超过 4 MiB。'
  }
  return ''
}

function applyFile(selected: File | undefined) {
  if (!selected) return
  file.value = selected
  review.value = undefined
  confirmed.value = false
  phase.value = 'select'
  error.value = validateFile(selected)
}

function chooseFile() {
  if (pending.value) return
  fileInput.value?.click()
}

function selectFile(event: Event) {
  if (pending.value) return
  const input = event.target as HTMLInputElement
  applyFile(input.files?.[0])
  input.value = ''
}

function dropFile(event: DragEvent) {
  dragging.value = false
  if (pending.value) return
  applyFile(event.dataTransfer?.files?.[0])
}

function startReview() {
  if (pending.value) return
  if (!props.aiAvailable) {
    error.value = 'AI 审查不可用，请检查 AI Foundation。'
    return
  }
  if (!file.value) {
    error.value = '请选择 ZIP 文件。'
    return
  }
  const invalid = validateFile(file.value)
  if (invalid) {
    error.value = invalid
    return
  }
  error.value = ''
  review.value = undefined
  confirmed.value = false
  phase.value = 'select'
  reviewAbort?.abort()
  reviewAbort = new AbortController()
  const attempt = ++reviewAttempt
  pending.value = true
  reviewMutation.mutate({
    file: file.value,
    model: (model.value || '').trim() || undefined,
    attempt,
    signal: reviewAbort.signal,
  })
}

function cancelReview() {
  if (!pending.value || phase.value !== 'select') return
  reviewAttempt++
  reviewAbort?.abort()
  reviewAbort = undefined
  pending.value = false
  review.value = undefined
  confirmed.value = false
  phase.value = 'select'
  error.value = '已取消审查。'
}

function startImport() {
  if (pending.value || !file.value || !review.value || !canImport.value) return
  error.value = ''
  pending.value = true
  importMutation.mutate({
    file: file.value,
    reviewId: review.value.reviewId,
    confirmed: review.value.verdict === 'suspicious' && confirmed.value,
  })
}

function submit() {
  if (phase.value === 'select') {
    startReview()
  } else {
    startImport()
  }
}

function reviewAgain() {
  if (pending.value) return
  startReview()
}

function severityLabel(severity: string) {
  return severityLabels[severity] ?? severity
}

function severityClass(severity: string) {
  if (severity === 'critical' || severity === 'high') return 'bg-red-50 text-red-700'
  if (severity === 'medium') return 'bg-amber-50 text-amber-700'
  return 'bg-gray-100 text-gray-600'
}

function categoryLabel(category: string) {
  return categoryLabels[category] ?? '其他风险'
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function close() {
  if (!pending.value) emit('close')
}

function guardEscape(event: KeyboardEvent) {
  if (pending.value) event.stopImmediatePropagation()
}

// The report belongs to the model that produced it; changing the model invalidates it.
watch(model, () => {
  if (!review.value || isMalicious.value) return
  review.value = undefined
  confirmed.value = false
  phase.value = 'select'
  error.value = ''
})
</script>

<template>
  <VModal
    title="导入页面"
    :width="640"
    :layer-closable="false"
    @keyup.esc.capture="guardEscape"
    mount-to-body
    @close="close"
    ref="modal"
  >
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
        :disabled="pending"
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
        :disabled="pending"
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

      <div v-if="aiAvailable" class="mt-4">
        <div class="mb-2 flex items-baseline justify-between gap-2">
          <label class="text-sm font-medium text-gray-700">审查模型</label>
          <span class="text-xs text-gray-400">可选</span>
        </div>
        <div class="apb-model">
          <FormKit
            v-model="model"
            type="aiModelSelector"
            model-type="language"
            required-features="structured-output"
            clearable
            full-width
            placeholder="默认模型"
            :disabled="pending || isMalicious"
          />
        </div>
        <p class="mt-1.5 text-xs text-gray-500">不选择时使用 AI Foundation 配置的默认模型。</p>
      </div>
      <div v-else class="mt-4">
        <VAlert
          type="warning"
          :closable="false"
          :description="aiStatusMessage || 'AI Foundation 暂不可用，请先安装、启用并配置语言模型。'"
        />
      </div>

      <div class="mt-3">
        <VAlert
          type="warning"
          :closable="false"
          description="导入前会先由 AI 审查页面包；发布后脚本仍会在访客浏览器中执行。"
        />
      </div>

      <div v-if="pending && phase === 'select'" class="mt-3">
        <VAlert
          type="info"
          :closable="false"
          description="正在审查页面包，可能需要几十秒到几分钟…"
        />
        <button
          type="button"
          class="mt-2 text-xs text-blue-600 hover:underline"
          @click="cancelReview"
        >
          取消审查
        </button>
      </div>

      <div v-if="phase === 'report' && review" class="mt-4 rounded-md border border-gray-200 p-3">
        <VAlert
          :type="verdictMeta.alertType"
          :title="verdictMeta.label"
          :description="review.summary"
          :closable="false"
        />
        <div v-if="review.findings.length" class="mt-3 max-h-64 space-y-3 overflow-y-auto pr-1">
          <div
            v-for="(finding, index) in review.findings"
            :key="index"
            class="rounded-md bg-gray-50 p-3"
          >
            <div class="flex flex-wrap items-center gap-2 text-xs">
              <span :class="['rounded px-1.5 py-0.5 font-medium', severityClass(finding.severity)]">
                {{ severityLabel(finding.severity) }}
              </span>
              <span class="text-gray-600">{{ categoryLabel(finding.category) }}</span>
              <span class="font-mono text-gray-500">{{ finding.file }}</span>
            </div>
            <pre
              v-if="finding.evidence"
              class="mt-2 max-h-24 overflow-auto whitespace-pre-wrap break-all rounded bg-white p-2 text-xs text-gray-700"
              >{{ finding.evidence }}</pre>
            <p class="mt-2 text-sm text-gray-700">{{ finding.explanation }}</p>
          </div>
        </div>
        <p v-else class="mt-2 text-sm text-gray-500">未发现具体风险项。</p>
        <label v-if="isSuspicious" class="mt-3 flex items-start gap-2 text-sm text-gray-700">
          <input v-model="confirmed" type="checkbox" class="mt-1" :disabled="pending" />
          <span>我已了解上述风险，仍要导入此页面包。</span>
        </label>
        <div class="mt-3 flex items-center justify-between text-xs text-gray-500">
          <button
            v-if="!isMalicious"
            type="button"
            class="text-blue-600 hover:underline disabled:cursor-not-allowed disabled:opacity-50"
            :disabled="pending"
            @click="reviewAgain"
          >
            重新审查
          </button>
          <span class="ml-auto">{{ reviewFooter }}</span>
        </div>
      </div>

      <VAlert
        v-if="error"
        type="error"
        role="alert"
        :closable="false"
        :description="error"
        class="mt-3"
      />
    </div>
    <template #footer>
      <VSpace>
        <VButton
          type="secondary"
          :loading="pending"
          :disabled="
            pending || (phase === 'select' && !aiAvailable) || (phase === 'report' && !canImport)
          "
          @click="submit"
        >
          {{ primaryLabel }}
        </VButton>
        <VButton :disabled="pending" @click="modal?.close()">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<!-- FormKit renders its own wrapper; only :deep() can reach it. -->
<style scoped>
.apb-model :deep(.formkit-outer) {
  padding: 0;
}
</style>
