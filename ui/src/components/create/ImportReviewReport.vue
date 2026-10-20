<script setup lang="ts">
import { computed } from 'vue'
import { VAlert } from '@halo-dev/components'
import type { ImportReview } from '../../api/generated'
import {
  categoryLabel,
  isMalicious,
  isSuspicious,
  reviewFooter,
  severityClass,
  severityLabel,
  verdictMeta,
} from '../../editor/import-review'

const props = withDefaults(defineProps<{ review: ImportReview; pending?: boolean }>(), {
  pending: false,
})
const emit = defineEmits<{ 'review-again': [] }>()
const confirmed = defineModel<boolean>('confirmed', { default: false })

const suspicious = computed(() => isSuspicious(props.review.verdict))
const malicious = computed(() => isMalicious(props.review.verdict))
const meta = computed(() => verdictMeta(props.review.verdict))
const footer = computed(() => reviewFooter(props.review.verdict))
</script>

<template>
  <div class="mt-4 rounded-md border border-gray-200 p-3">
    <VAlert
      :type="meta.alertType"
      :title="meta.label"
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
    <label v-if="suspicious" class="mt-3 flex items-start gap-2 text-sm text-gray-700">
      <input v-model="confirmed" type="checkbox" class="mt-1" :disabled="pending" />
      <span>我已了解上述风险，仍要导入此页面包。</span>
    </label>
    <div class="mt-3 flex items-center justify-between text-xs text-gray-500">
      <button
        v-if="!malicious"
        type="button"
        class="text-blue-600 hover:underline disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="pending"
        @click="emit('review-again')"
      >
        重新审查
      </button>
      <span class="ml-auto">{{ footer }}</span>
    </div>
  </div>
</template>
