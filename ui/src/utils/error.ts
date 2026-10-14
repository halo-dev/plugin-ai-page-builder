export function errorMessage(error: unknown): string {
  const response = (
    error as { response?: { status?: number; data?: { detail?: string; message?: string } } }
  )?.response
  if (response?.status === 409)
    return '页面已被其他操作更新。本地内容已保留，请查看最新版本后重试。'
  if (response?.data?.detail || response?.data?.message)
    return response.data.detail || response.data.message || '操作失败，请稍后重试。'
  const message = error instanceof Error ? error.message : '操作失败，请稍后重试。'
  // SDK HTTP errors retain the response body as their message.
  if (message.trim().startsWith('{')) {
    try {
      const problem = JSON.parse(message) as { detail?: string; message?: string; title?: string }
      return problem.detail || problem.message || problem.title || '请求失败，请稍后重试。'
    } catch {
      return '请求失败，请稍后重试。'
    }
  }
  return message
}
