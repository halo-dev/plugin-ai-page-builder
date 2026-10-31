type ErrorResponse = {
  status?: number
  data?: { detail?: string; message?: string }
}

export function errorMessage(error: unknown): string {
  const candidate = error as {
    response?: ErrorResponse
    code?: string
    message?: string
    request?: unknown
  }
  const response = candidate?.response
  const status = response?.status
  if (status === 409) return '页面已被其他操作更新。本地内容已保留，请查看最新版本后重试。'
  const rawMessage = error instanceof Error ? error.message : ''
  if (candidate?.code === 'ERR_CANCELED') return '请求已取消。'
  if (candidate?.code === 'ECONNABORTED' || (!response && /timeout/i.test(rawMessage)))
    return '请求超时，服务器响应较慢，请稍后重试。'
  if (
    (!response && !!candidate?.request) ||
    candidate?.code === 'ERR_NETWORK' ||
    /^network error$/i.test(rawMessage) ||
    /^failed to fetch$/i.test(rawMessage)
  )
    return '网络连接失败，请检查网络后重试。'
  if (status === 401) return '登录状态已失效，请重新登录后再试。'
  if (status === 403) return '没有权限执行此操作，请联系管理员。'
  if (status === 404) return '请求的内容不存在或已被删除。'
  if (status === 429) return '请求过于频繁，请稍后再试。'
  if (status && status >= 500) return '服务器暂时不可用，请稍后重试。'
  if (response?.data?.detail || response?.data?.message)
    return response.data.detail || response.data.message || '操作失败，请稍后重试。'
  // SDK HTTP errors retain the response body as their message.
  if (rawMessage.trim().startsWith('{')) {
    try {
      const problem = JSON.parse(rawMessage) as {
        detail?: string
        message?: string
        title?: string
      }
      return problem.detail || problem.message || problem.title || '请求失败，请稍后重试。'
    } catch {
      return '请求失败，请稍后重试。'
    }
  }
  return rawMessage || '操作失败，请稍后重试。'
}
