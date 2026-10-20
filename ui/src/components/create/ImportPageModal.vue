<script setup lang="ts">
import { useTemplateRef } from 'vue'
import { useRouter } from 'vue-router'
import { VAlert, VButton, VModal, VSpace } from '@halo-dev/components'
import ImportReviewReport from './ImportReviewReport.vue'
import PageArchiveDropzone from './PageArchiveDropzone.vue'
import { usePageArchiveImport } from '../../composables/usePageArchiveImport'

const props = withDefaults(defineProps<{ aiAvailable?: boolean; aiStatusMessage?: string }>(), {
  aiAvailable: true,
  aiStatusMessage: '',
})

const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const modal = useTemplateRef<InstanceType<typeof VModal>>('modal')

const {
  file,
  model,
  review,
  confirmed,
  pending,
  phase,
  malicious,
  canImport,
  actionLabel,
  applyFile,
  cancelReview,
  reviewAgain,
  submit,
} = usePageArchiveImport({
  aiAvailable: () => props.aiAvailable,
  onImported: (detail) => {
    void router.push({ name: 'AiPageBuilderEditor', params: { name: detail.page.name } })
  },
})

function close() {
  if (!pending.value) emit('close')
}

function guardEscape(event: KeyboardEvent) {
  if (pending.value) event.stopImmediatePropagation()
}

function guardClose(event: MouseEvent) {
  if (!pending.value) return
  // The default VModal header close button cannot be disabled, so stop the
  // click before it reaches VModal and hides the dialog mid-review.
  if ((event.target as HTMLElement | null)?.closest('.modal-header-actions'))
    event.stopPropagation()
}
</script>

<template>
  <VModal
    title="导入页面"
    :width="640"
    :layer-closable="false"
    @keyup.esc.capture="guardEscape"
    @click.capture="guardClose"
    mount-to-body
    @close="close"
    ref="modal"
  >
    <div>
      <PageArchiveDropzone :file="file" :disabled="pending" @select="applyFile" />

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
            :disabled="pending || malicious"
          />
        </div>
        <p class="mt-1.5 text-xs text-gray-500">不选择时使用 AI Foundation 配置的默认模型。</p>
      </div>
      <div v-else class="mt-4">
        <VAlert
          type="warning"
          title="AI 审查不可用"
          :closable="false"
          :description="aiStatusMessage || 'AI Foundation 暂不可用，请先安装、启用并配置语言模型。'"
        />
      </div>

      <div class="mt-3">
        <VAlert
          type="warning"
          title="安全提示"
          :closable="false"
          description="导入前会先由 AI 审查页面包；发布后脚本仍会在访客浏览器中执行。"
        />
      </div>

      <div v-if="pending && phase === 'select'" class="mt-3">
        <VAlert
          type="info"
          title="正在审查"
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

      <ImportReviewReport
        v-if="phase === 'report' && review"
        v-model:confirmed="confirmed"
        :review="review"
        :pending="pending"
        @review-again="reviewAgain"
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
          {{ actionLabel }}
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
