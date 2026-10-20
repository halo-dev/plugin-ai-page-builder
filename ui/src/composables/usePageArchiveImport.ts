import { computed, onScopeDispose, shallowRef, watch } from 'vue'
import { useMutation } from '@tanstack/vue-query'
import { Toast } from '@halo-dev/components'
import { aiPageBuilderApi } from '../api/clients'
import type { ImportReview, PageDetail } from '../api/generated'
import {
  canImportReview,
  isMalicious,
  isSuspicious,
  primaryLabel,
  reviewFooter,
  validateArchive,
  verdictMeta,
} from '../editor/import-review'
import type { ImportPhase } from '../editor/import-review'
import { pageKeys, queryClient } from '../lib/query-client'
import { errorMessage } from '../utils/error'

const REVIEW_CLIENT_TIMEOUT = 11 * 60 * 1000

export function usePageArchiveImport(options: {
  aiAvailable: () => boolean
  onImported: (detail: PageDetail) => void
}) {
  const file = shallowRef<File>()
  const model = shallowRef('')
  const review = shallowRef<ImportReview>()
  const confirmed = shallowRef(false)
  const pending = shallowRef(false)
  const phase = shallowRef<ImportPhase>('select')
  let reviewAttempt = 0
  let reviewAbort: AbortController | undefined

  const suspicious = computed(() => isSuspicious(review.value?.verdict))
  const malicious = computed(() => isMalicious(review.value?.verdict))
  const canImport = computed(() => canImportReview(review.value?.verdict, confirmed.value))
  const footer = computed(() => reviewFooter(review.value?.verdict))
  const verdict = computed(() => verdictMeta(review.value?.verdict))
  const actionLabel = computed(() =>
    primaryLabel(phase.value, options.aiAvailable(), review.value?.verdict),
  )

  const reviewMutation = useMutation({
    mutationFn: async (input: {
      file: File
      model?: string
      attempt: number
      signal: AbortSignal
    }) =>
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
      phase.value = 'report'
    },
    onError: (e, input) => {
      if (input.attempt !== reviewAttempt) return
      review.value = undefined
      phase.value = 'select'
      Toast.error(errorMessage(e))
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
      options.onImported(detail)
    },
    onError: (e) => Toast.error(errorMessage(e)),
    onSettled: () => (pending.value = false),
    queryClient,
  })

  function applyFile(selected: File | undefined) {
    if (!selected) return
    file.value = selected
    review.value = undefined
    confirmed.value = false
    phase.value = 'select'
    const invalid = validateArchive(selected)
    if (invalid) Toast.error(invalid)
  }

  function startReview() {
    if (pending.value) return
    if (!options.aiAvailable()) {
      Toast.error('AI 审查不可用，请检查 AI Foundation。')
      return
    }
    if (!file.value) {
      Toast.error('请选择 ZIP 文件。')
      return
    }
    const invalid = validateArchive(file.value)
    if (invalid) {
      Toast.error(invalid)
      return
    }
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
    Toast.info('已取消审查。')
  }

  function startImport() {
    if (pending.value || !file.value || !review.value || !canImport.value) return
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

  // The report belongs to the model that produced it; changing the model invalidates it.
  watch(model, () => {
    if (!review.value || malicious.value) return
    review.value = undefined
    confirmed.value = false
    phase.value = 'select'
  })

  // Leaving the page invalidates an in-flight review so its callbacks stay silent.
  onScopeDispose(() => {
    reviewAttempt++
    reviewAbort?.abort()
  })

  return {
    file,
    model,
    review,
    confirmed,
    pending,
    phase,
    suspicious,
    malicious,
    canImport,
    footer,
    verdict,
    actionLabel,
    applyFile,
    startReview,
    cancelReview,
    startImport,
    reviewAgain,
    submit,
  }
}
