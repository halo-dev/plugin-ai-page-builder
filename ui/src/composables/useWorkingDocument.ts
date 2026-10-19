import { computed, onScopeDispose, shallowRef } from 'vue'
import { aiPageBuilderChatApi } from '../api/clients'
import { usePageWrites } from './usePageWrites'
import { errorMessage } from '../utils/error'
import type { PageDetail, PageDocument } from '../api/generated'
import {
  cloneDocument,
  createDocumentHistory,
  fingerprint,
  updateDocument,
} from '../editor/document'

export function useWorkingDocument() {
  const document = shallowRef<PageDocument>()
  const layout = shallowRef(false)
  const saving = shallowRef(false)
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
  const writes = usePageWrites((result, content) => {
    if (content) applyDocument(result)
  })
  const { detail, pending: busy } = writes
  const dirty = computed(
    () =>
      !!detail.value &&
      !!document.value &&
      (fingerprint(document.value) !== savedFingerprint.value ||
        layout.value !== detail.value.page.themeLayout),
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
      try {
        await aiPageBuilderChatApi.validatePageContent(
          {
            validatePageContentRequest: {
              html: snapshot.html,
              css: snapshot.css,
              js: snapshot.js ?? '',
            },
          },
          // Not muted: a 401 here must still raise the login-expired dialog, since a
          // failed validation disables save/publish and no other primary action could
          // re-trigger authentication.
          { signal: controller.signal },
        )
        if (request === sequence) validationState.value = 'valid'
      } catch (e) {
        if (request !== sequence) return
        validationState.value = 'invalid'
        validationError.value = errorMessage(e)
      }
    }, 400)
  }
  function applyDocument(result: PageDetail, reset = false) {
    cancelValidation()
    layout.value = result.page.themeLayout
    if (!history || reset) history = createDocumentHistory(result.document)
    else history.push(result.document)
    document.value = cloneDocument(result.document)
    savedFingerprint.value = fingerprint(result.document)
    validationState.value = 'valid'
    validationError.value = ''
    historyVersion.value++
  }
  function adopt(result: PageDetail, reset = false) {
    if (reset || !writes.target.value) {
      writes.reset(result)
      applyDocument(result, true)
    } else writes.receive(result)
  }
  function resetSession() {
    cancelValidation()
    writes.reset()
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
  function applySettings(
    value: Partial<Pick<PageDocument, 'title' | 'description'>> & { themeLayout?: boolean },
  ) {
    if (locked.value) return
    const { themeLayout, ...documentPatch } = value
    if (themeLayout !== undefined) layout.value = themeLayout
    if (Object.keys(documentPatch).length) patch(documentPatch)
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
    saving.value = true
    error.value = ''
    try {
      return await writes.save({ document: document.value, themeLayout: layout.value })
    } finally {
      saving.value = false
    }
  }
  async function updateSlug(slug: string) {
    if (locked.value || !detail.value) return undefined
    return writes.updateSlug(slug)
  }
  async function restore(revision: string, confirm: () => Promise<boolean>) {
    if (locked.value || !detail.value || !valid.value) return undefined
    return writes.restore(
      revision,
      () => {
        if (agentRunning.value || preparing.value) throw new Error('页面尚未就绪。')
        if (!valid.value) throw new Error('请先修正源码并等待校验通过。')
        return dirty.value && document.value
          ? { document: document.value, themeLayout: layout.value }
          : undefined
      },
      confirm,
    )
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
    layout,
    busy,
    saving,
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
    applySettings,
    move,
    save,
    updateSlug,
    prepareAgent,
    restore,
    resetSession,
    writes,
  }
}
