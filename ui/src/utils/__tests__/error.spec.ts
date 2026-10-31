import { describe, expect, it } from 'vitest'
import { errorMessage } from '../error'

describe('errorMessage', () => {
  it('translates network and timeout failures', () => {
    expect(errorMessage({ request: {}, message: 'Network Error' })).toBe(
      '网络连接失败，请检查网络后重试。',
    )
    expect(
      errorMessage({ request: {}, code: 'ECONNABORTED', message: 'timeout of 0ms exceeded' }),
    ).toBe('请求超时，服务器响应较慢，请稍后重试。')
  })

  it('maps HTTP statuses to actionable Chinese copy', () => {
    expect(errorMessage({ response: { status: 401 } })).toContain('登录状态已失效')
    expect(errorMessage({ response: { status: 404 } })).toContain('不存在')
    expect(errorMessage({ response: { status: 503 } })).toContain('服务器暂时不可用')
  })

  it('keeps a server detail for ordinary validation failures', () => {
    expect(errorMessage({ response: { status: 400, data: { detail: '标题不能为空' } } })).toBe(
      '标题不能为空',
    )
  })
})
