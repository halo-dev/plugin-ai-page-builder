import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({
  get: vi.fn<(...args: unknown[]) => unknown>(),
}))
vi.mock('../../api/clients', () => ({ aiPageBuilderApi: { getPage: mocks.get } }))

function detail(revision: string, html = '<p>Preview</p>') {
  return {
    page: { draftRevision: revision, themeLayout: false },
    document: { html, css: 'p { color: red; }' },
  }
}

beforeEach(() => {
  vi.resetModules()
  mocks.get.mockReset()
})

describe('card preview loading', () => {
  it('shares requests and caches by the revision returned by the server', async () => {
    const { loadCardPreview } = await import('../card-preview')
    mocks.get.mockResolvedValue({ data: detail('r2') })
    const first = loadCardPreview('page', 'r1')
    expect(loadCardPreview('page', 'r1')).toBe(first)
    await first
    await flushPromises()
    expect(await loadCardPreview('page', 'r2')).toEqual({
      html: '<p>Preview</p>',
      css: 'p { color: red; }',
      themeLayout: false,
    })
    expect(mocks.get).toHaveBeenCalledOnce()
    await loadCardPreview('page', 'r3')
    expect(mocks.get).toHaveBeenCalledTimes(2)
  })

  it('loads no more than three previews at once and continues after a failure', async () => {
    const { loadCardPreview } = await import('../card-preview')
    const jobs: Array<{
      resolve: (value: { data: ReturnType<typeof detail> }) => void
      reject: (reason: Error) => void
    }> = []
    mocks.get.mockImplementation(
      () => new Promise((resolve, reject) => jobs.push({ resolve, reject })),
    )
    const requests = Array.from({ length: 5 }, (_, i) =>
      loadCardPreview(`p${i}`, 'r1').catch(() => null),
    )
    expect(mocks.get).toHaveBeenCalledTimes(3)
    jobs[0]!.reject(new Error('offline'))
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(4)
    jobs[1]!.resolve({ data: detail('r1') })
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(5)
    jobs.slice(2).forEach((job) => job.resolve({ data: detail('r1') }))
    await Promise.all(requests)
    mocks.get.mockResolvedValue({ data: detail('r1') })
    await loadCardPreview('p0', 'r1')
    expect(mocks.get).toHaveBeenCalledTimes(6)
  })
})
