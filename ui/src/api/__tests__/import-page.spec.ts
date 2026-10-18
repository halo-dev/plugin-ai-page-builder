import { beforeEach, describe, expect, it, vi } from 'vitest'
import { API_BASE, aiPageBuilderApi } from '../clients'

const mocks = vi.hoisted(() => ({
  request:
    vi.fn<
      (config: {
        url: string
        method?: string
        headers?: Record<string, string>
        data?: unknown
      }) => Promise<{ data: unknown }>
    >(),
  defaults: { baseURL: '' },
}))
vi.mock('@halo-dev/api-client', () => ({
  axiosInstance: { request: mocks.request, defaults: mocks.defaults },
}))

beforeEach(() => {
  vi.resetAllMocks()
  mocks.defaults.baseURL = ''
})

describe('page archive review request', () => {
  it.each(['', 'https://halo.example'])('posts the raw ZIP with baseURL %s', async (baseURL) => {
    mocks.defaults.baseURL = baseURL
    const file = new File(['zip'], 'page.zip')
    const review = { reviewId: 'review-1', verdict: 'safe' }
    mocks.request.mockResolvedValue({ data: review })

    const response = await aiPageBuilderApi.reviewPageImport({ body: file })

    expect(response.data).toBe(review)
    expect(mocks.request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        url: `${API_BASE}/pages/import/review`,
        headers: { 'Content-Type': 'application/zip' },
      }),
    )
    expect(
      new URL(mocks.request.mock.calls[0]![0].url, 'http://localhost').searchParams.get('model'),
    ).toBeNull()
    expect(mocks.request.mock.calls[0]![0].data).toBe(file)
  })

  it('sends the selected review model as a query parameter', async () => {
    mocks.request.mockResolvedValue({ data: { reviewId: 'review-1', verdict: 'safe' } })

    await aiPageBuilderApi.reviewPageImport({
      body: new File(['zip'], 'page.zip'),
      model: 'model-a',
    })

    const url = new URL(mocks.request.mock.calls[0]![0].url, 'http://localhost')
    expect(url.searchParams.get('model')).toBe('model-a')
  })
})

describe('page archive import request', () => {
  it.each(['', 'https://halo.example'])('posts the raw ZIP with baseURL %s', async (baseURL) => {
    mocks.defaults.baseURL = baseURL
    const file = new File(['zip'], 'page.zip')
    const detail = { page: { name: 'imported-page' } }
    mocks.request.mockResolvedValue({ data: detail })

    const response = await aiPageBuilderApi.importPage({ reviewId: 'review-1', body: file })

    expect(response.data).toBe(detail)
    expect(mocks.request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/zip' },
      }),
    )
    const request = mocks.request.mock.calls[0]![0]
    const url = new URL(request.url, 'http://localhost')
    expect(url.pathname).toBe(`${API_BASE}/pages/import`)
    expect(url.searchParams.get('reviewId')).toBe('review-1')
    expect(url.searchParams.get('confirmed')).toBeNull()
    expect(request.data).toBe(file)
  })

  it('confirms a suspicious review', async () => {
    const file = new File(['zip'], 'page.zip')
    mocks.request.mockResolvedValue({ data: { page: { name: 'imported-page' } } })

    await aiPageBuilderApi.importPage({ reviewId: 'review-1', body: file, confirmed: true })

    const url = new URL(mocks.request.mock.calls[0]![0].url, 'http://localhost')
    expect(url.searchParams.get('reviewId')).toBe('review-1')
    expect(url.searchParams.get('confirmed')).toBe('true')
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
