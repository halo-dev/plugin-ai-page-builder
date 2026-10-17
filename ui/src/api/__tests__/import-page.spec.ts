import { beforeEach, describe, expect, it, vi } from 'vitest'
import { API_BASE, aiPageBuilderApi } from '../clients'

const mocks = vi.hoisted(() => ({ request: vi.fn(), defaults: { baseURL: '' } }))
vi.mock('@halo-dev/api-client', () => ({
  axiosInstance: { request: mocks.request, defaults: mocks.defaults },
}))

beforeEach(() => {
  vi.resetAllMocks()
  mocks.defaults.baseURL = ''
})

describe('page archive request', () => {
  it.each(['', 'https://halo.example'])('posts the raw ZIP with baseURL %s', async (baseURL) => {
    mocks.defaults.baseURL = baseURL
    const file = new File(['zip'], 'page.zip')
    const detail = { page: { name: 'imported-page' } }
    mocks.request.mockResolvedValue({ data: detail })
    const response = await aiPageBuilderApi.importPage({ body: file })
    expect(response.data).toBe(detail)
    expect(mocks.request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        url: `${API_BASE}/pages/import`,
        headers: { 'Content-Type': 'application/zip' },
      }),
    )
    expect(mocks.request.mock.calls[0]![0].data).toBe(file)
  })

  it('continues to serialize ordinary page creation as JSON', async () => {
    mocks.request.mockResolvedValue({ data: { page: { name: 'new-page' } } })
    const request = { title: '手动创建', themeLayout: true }
    await aiPageBuilderApi.createPage({ createPageRequest: request })
    expect(mocks.request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        url: `${API_BASE}/pages`,
        headers: { 'Content-Type': 'application/json' },
        data: JSON.stringify(request),
      }),
    )
  })
})
