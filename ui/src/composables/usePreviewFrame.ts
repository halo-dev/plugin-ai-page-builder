import { onScopeDispose, shallowRef, watch } from 'vue'

/**
 * Preflights a server-rendered preview before showing it in an iframe. The iframe load event
 * also fires for error pages, so request the same URL first to surface auth/HTTP failures.
 */
export function usePreviewFrame(src: () => string) {
  const loading = shallowRef(false)
  const error = shallowRef('')
  const generation = shallowRef(0)
  let request = 0
  let controller: AbortController | undefined
  onScopeDispose(() => controller?.abort())

  async function reload() {
    controller?.abort()
    const url = src()
    if (!url) {
      loading.value = false
      error.value = ''
      return
    }
    const current = ++request
    const active = (controller = new AbortController())
    loading.value = true
    error.value = ''
    try {
      const response = await fetch(url, {
        credentials: 'include',
        redirect: 'manual',
        headers: { Accept: 'text/html' },
        signal: active.signal,
      })
      if (current !== request) return
      if (response.type === 'opaqueredirect') {
        error.value = '登录状态已失效或无权访问此预览，请重新登录后重试。'
      } else if (!response.ok) {
        error.value = `预览加载失败（HTTP ${response.status}），请重试。`
      } else {
        // The iframe loads the document again; drop this body instead of buffering it.
        void response.body?.cancel()
        generation.value++
      }
    } catch {
      if (current === request && !active.signal.aborted) {
        error.value = '预览加载失败，请检查网络后重试。'
      }
    } finally {
      if (current === request) loading.value = false
    }
  }

  watch(src, reload, { immediate: true })
  return { loading, error, generation, reload }
}
