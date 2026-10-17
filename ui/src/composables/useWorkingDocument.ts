import { computed, onScopeDispose, shallowRef } from 'vue'
import { aiPageBuilderApi, aiPageBuilderChatApi, type SilentRequestConfig } from '../api/clients'
import { errorMessage } from '../utils/error'
import { pageKeys, queryClient } from '../lib/query-client'
import type { PageDetail, PageDocument } from '../api/generated'
import {
  cloneDocument,
  createDocumentHistory,
  fingerprint,
  updateDocument,
} from '../editor/document'

export function useWorkingDocument() {
  const detail = shallowRef<PageDetail>()
  const document = shallowRef<PageDocument>()
  const busy = shallowRef(false)
  const agentRunning = shallowRef(false)
  const preparing = shallowRef(false)
  const error = shallowRef('')
  const validationError = shallowRef('')
  const validationState = shallowRef<'valid' | 'pending' | 'invalid'>('valid')
  const savedFingerprint = shallowRef('')
  const historyVersion = shallowRef(0)
  let history: ReturnType<typeof createDocumentHistory> | undefined
  let sequence = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  const dirty = computed(
    () => !!document.value && fingerprint(document.value) !== savedFingerprint.value,
  )
  const locked = computed(() => busy.value || agentRunning.value || preparing.value)
  const valid = computed(() => validationState.value === 'valid')
  const canUndo = computed(() => {
    void historyVersion.value
    return !!history?.canUndo
  })
  const canRedo = computed(() => {
    void historyVersion.value
    return !!history?.canRedo
  })

  function cancelValidation() {
    sequence++
    clearTimeout(timer)
    controller?.abort()
  }
  function validate() {
    cancelValidation()
    const snapshot = document.value
    if (!snapshot) return
    const request = sequence
    validationState.value = 'pending'
    validationError.value = ''
    timer = setTimeout(async () => {
      controller = new AbortController()
      // Failures surface in the inline validation banner; mute the global error toast.
      const options: SilentRequestConfig = { signal: controller.signal, mute: true }
      try {
        await aiPageBuilderChatApi.validatePageContent(
          {
            validatePageContentRequest: {
              html: snapshot.html,
              css: snapshot.css,
              js: snapshot.js ?? '',
            },
          },
          options,
        )
        if (request === sequence) validationState.value = 'valid'
      } catch (e) {
        if (request !== sequence) return
        validationState.value = 'invalid'
        validationError.value = errorMessage(e)
      }
    }, 400)
  }
  function adopt(result: PageDetail, reset = false) {
    cancelValidation()
    detail.value = result
    if (!history || reset) history = createDocumentHistory(result.document)
    else history.push(result.document)
    document.value = cloneDocument(result.document)
    savedFingerprint.value = fingerprint(result.document)
    validationState.value = 'valid'
    validationError.value = ''
    historyVersion.value++
  }
  function change(next: PageDocument) {
    if (locked.value || !document.value || fingerprint(next) === fingerprint(document.value)) return
    const contentChanged =
      next.html !== document.value.html ||
      next.css !== document.value.css ||
      (next.js ?? '') !== (document.value.js ?? '')
    history?.push(next)
    document.value = cloneDocument(next)
    historyVersion.value++
    if (contentChanged) validate()
  }
  function patch(
    value: Partial<Pick<PageDocument, 'html' | 'css' | 'js' | 'title' | 'description'>>,
  ) {
    if (document.value) change(updateDocument(document.value, value))
  }
  function move(direction: 'undo' | 'redo') {
    if (locked.value || !history || !(direction === 'undo' ? history.canUndo : history.canRedo))
      return
    document.value = history[direction]()
    historyVersion.value++
    validate()
  }
  async function save(internal = false) {
    if (
      busy.value ||
      agentRunning.value ||
      (preparing.value && !internal) ||
      !detail.value ||
      !document.value
    )
      return undefined
    if (!valid.value) throw new Error('请先修正源码并等待校验通过。')
    if (!dirty.value) return detail.value
    busy.value = true
    error.value = ''
    try {
      const result = (
        await aiPageBuilderApi.savePage({
          name: detail.value.page.name,
          savePageRequest: {
            version: detail.value.page.version,
            document: document.value,
            source: 'manual',
          },
        })
      ).data
      adopt(result)
      queryClient.setQueryData(pageKeys.detail(result.page.name), result)
      return result
    } finally {
      busy.value = false
    }
  }
  async function updateSlug(slug: string) {
    if (locked.value || !detail.value) return undefined
    if (slug === detail.value.page.slug) return detail.value
    busy.value = true
    try {
      const result = (
        await aiPageBuilderApi.updatePageSlug({
          name: detail.value.page.name,
          updatePageSlugRequest: { version: detail.value.page.version, slug },
        })
      ).data
      detail.value = { ...detail.value, page: result.page }
      queryClient.setQueryData(pageKeys.detail(result.page.name), result)
      void queryClient.invalidateQueries({ queryKey: [...pageKeys.all, 'list'] })
      return result
    } finally {
      busy.value = false
    }
  }
  async function updateLayout(themeLayout: boolean) {
    if (locked.value || !detail.value) return undefined
    if (themeLayout === detail.value.page.themeLayout) return detail.value
    busy.value = true
    try {
      const result = (
        await aiPageBuilderApi.updatePageLayout({
          name: detail.value.page.name,
          updatePageLayoutRequest: { version: detail.value.page.version, themeLayout },
        })
      ).data
      detail.value = { ...detail.value, page: result.page }
      queryClient.setQueryData(pageKeys.detail(result.page.name), result)
      return result
    } finally {
      busy.value = false
    }
  }
  async function prepareAgent<T = number>(
    snapshot?: (page: PageDetail) => Promise<T>,
  ): Promise<T | number> {
    if (locked.value) throw new Error('页面尚未就绪。')
    preparing.value = true
    try {
      const result = await save(true)
      if (!result) throw new Error('页面尚未就绪。')
      return snapshot ? await snapshot(result) : result.page.version
    } finally {
      preparing.value = false
    }
  }
  onScopeDispose(cancelValidation)
  return {
    detail,
    document,
    busy,
    agentRunning,
    preparing,
    locked,
    dirty,
    error,
    validationError,
    validationState,
    valid,
    canUndo,
    canRedo,
    adopt,
    change,
    patch,
    move,
    save,
    updateSlug,
    updateLayout,
    prepareAgent,
  }
}
