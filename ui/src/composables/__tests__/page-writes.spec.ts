import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { usePageWrites } from '../usePageWrites'
import { pageKeys, queryClient } from '../../lib/query-client'
import type { PageDetail } from '../../api/generated'

const api = vi.hoisted(() => ({
  getPage: vi.fn(),
  savePage: vi.fn(),
  updatePageProperties: vi.fn(),
  publishPage: vi.fn(),
  unpublishPage: vi.fn(),
  restorePageRevision: vi.fn(),
}))
vi.mock('../../api/clients', () => ({ aiPageBuilderApi: api }))
function page(version = 1, name = 'page', title = 'Title', slug = 'path'): PageDetail {
  return {
    page: {
      name,
      title,
      slug,
      version,
      themeLayout: true,
      description: '',
      updatedAt: '',
      hasUnpublishedChanges: true,
      draftRevision: `r${version}`,
    },
    document: { schemaVersion: 2, mode: 'code', html: '<p>Saved</p>', css: '', js: '' },
  } as PageDetail
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
let scope = effectScope()
beforeEach(() => {
  vi.resetAllMocks()
  queryClient.clear()
  scope = effectScope()
  api.getPage.mockResolvedValue({ data: page() })
})
afterEach(() => scope.stop())
function setup() {
  const writes = scope.run(() => usePageWrites())!
  writes.reset(page())
  return writes
}
const confirmed = async () => true

describe('page writes', () => {
  it('saves properties atomically and retries publication without resaving them', async () => {
    const writes = setup()
    const metadata = { title: 'New', slug: 'new-path' }
    api.updatePageProperties.mockRejectedValueOnce(new Error('Path taken'))
    await expect(writes.publish(metadata, confirmed)).rejects.toThrow('Path taken')
    expect(writes.detail.value?.page.version).toBe(1)
    expect(api.publishPage).not.toHaveBeenCalled()
    api.updatePageProperties.mockResolvedValue({ data: page(2, 'page', 'New', 'new-path') })
    api.publishPage.mockRejectedValueOnce(new Error('Publish failed'))
    await expect(writes.publish(metadata, confirmed)).rejects.toThrow('Publish failed')
    expect(writes.detail.value?.page.version).toBe(2)
    api.publishPage.mockResolvedValue({ data: page(3, 'page', 'New', 'new-path') })
    await writes.publish(metadata, confirmed)
    expect(api.savePage).not.toHaveBeenCalled()
    expect(api.updatePageProperties).toHaveBeenCalledTimes(2)
    expect(api.updatePageProperties).toHaveBeenLastCalledWith({
      name: 'page',
      updatePagePropertiesRequest: { version: 1, ...metadata },
    })
    expect(api.publishPage).toHaveBeenLastCalledWith({
      name: 'page',
      mutationRequest: { version: 2 },
    })
  })

  it('holds the write lock through script verification and all publication steps', async () => {
    const writes = setup()
    const verification = deferred<boolean>()
    api.publishPage.mockResolvedValue({ data: page(2) })
    const operation = writes.publish({ title: 'Title', slug: 'path' }, () => verification.promise)
    expect(writes.pending.value).toBe(true)
    expect(await writes.save({ document: page().document })).toBeUndefined()
    expect(api.savePage).not.toHaveBeenCalled()
    verification.resolve(true)
    await operation
    expect(writes.pending.value).toBe(false)
  })

  it('does not let a previous session overwrite or unlock a reopened session of the same page', async () => {
    const writes = setup()
    const oldSave = deferred<{ data: PageDetail }>()
    const newSave = deferred<{ data: PageDetail }>()
    api.savePage.mockReturnValueOnce(oldSave.promise).mockReturnValueOnce(newSave.promise)
    const old = writes.save({ document: page().document })
    writes.reset(page(3))
    const current = writes.save({ document: page(3).document })
    oldSave.resolve({ data: page(2) })
    expect(await old).toBeUndefined()
    expect(writes.pending.value).toBe(true)
    expect(writes.detail.value?.page.version).toBe(3)
    newSave.resolve({ data: page(4) })
    await current
    expect(writes.pending.value).toBe(false)
    expect(queryClient.getQueryData(pageKeys.detail('page'))).toMatchObject({
      page: { version: 4 },
    })
  })

  it('caches an old operation result but stops its remaining publication steps', async () => {
    const writes = setup()
    const titleSave = deferred<{ data: PageDetail }>()
    api.updatePageProperties.mockReturnValue(titleSave.promise)
    const operation = writes.publish({ title: 'New', slug: 'new-path' }, confirmed)
    await vi.waitFor(() => expect(api.updatePageProperties).toHaveBeenCalled())
    writes.reset(page(1, 'other'))
    titleSave.resolve({ data: page(2, 'page', 'New') })
    expect(await operation).toBeUndefined()
    expect(writes.detail.value?.page.name).toBe('other')
    expect(api.updatePageProperties).toHaveBeenCalledTimes(1)
    expect(api.publishPage).not.toHaveBeenCalled()
    expect(queryClient.getQueryData(pageKeys.detail('page'))).toMatchObject({
      page: { version: 2 },
    })
  })

  it('stops remaining writes if a refresh overtakes an intermediate publication save', async () => {
    const writes = setup()
    const titleSave = deferred<{ data: PageDetail }>()
    api.updatePageProperties.mockReturnValue(titleSave.promise)
    const operation = writes.publish({ title: 'New', slug: 'new-path' }, confirmed)
    await vi.waitFor(() => expect(api.updatePageProperties).toHaveBeenCalled())
    api.getPage.mockResolvedValue({ data: page(3, 'page', 'Concurrent') })
    await writes.refresh()
    titleSave.resolve({ data: page(2, 'page', 'New') })
    await expect(operation).rejects.toThrow('页面已更新')
    expect(writes.detail.value?.page.version).toBe(3)
    expect(api.updatePageProperties).toHaveBeenCalledTimes(1)
    expect(api.publishPage).not.toHaveBeenCalled()
  })

  it('abandons a confirmed operation when its session was closed and reopened', async () => {
    const writes = setup()
    const confirmation = deferred<boolean>()
    const operation = writes.unpublish(() => confirmation.promise)
    writes.reset()
    writes.reset(page())
    confirmation.resolve(true)
    expect(await operation).toBeUndefined()
    expect(api.unpublishPage).not.toHaveBeenCalled()
  })

  it('rejects a publication whose confirmed version changed while awaiting the user', async () => {
    const writes = setup()
    const confirmation = deferred<boolean>()
    const operation = writes.publishUpdate(() => confirmation.promise)
    writes.receive(page(2))
    confirmation.resolve(true)
    await expect(operation).rejects.toThrow('页面已更新')
    expect(api.publishPage).not.toHaveBeenCalled()
  })

  it('uses the current settings version after the user confirms unpublishing', async () => {
    const writes = setup()
    const confirmation = deferred<boolean>()
    api.unpublishPage.mockResolvedValue({ data: page(3) })
    const operation = writes.unpublish(() => confirmation.promise, true)
    writes.receive(page(2), false)
    confirmation.resolve(true)
    await operation
    expect(api.unpublishPage).toHaveBeenCalledWith({
      name: 'page',
      mutationRequest: { version: 2 },
    })
  })

  it.each(['save', 'refresh'] as const)(
    'never rolls back a newer version when %s arrives late',
    async (late) => {
      const writes = setup()
      const save = deferred<{ data: PageDetail }>()
      const refresh = deferred<{ data: PageDetail }>()
      api.savePage.mockReturnValue(save.promise)
      api.getPage.mockReturnValue(refresh.promise)
      const saving = writes.save({ document: page().document })
      const refreshing = writes.refresh()
      if (late === 'save') {
        refresh.resolve({ data: page(3) })
        await refreshing
        save.resolve({ data: page(2) })
        await saving
      } else {
        save.resolve({ data: page(3) })
        await saving
        refresh.resolve({ data: page(2) })
        await refreshing
      }
      expect(writes.detail.value?.page.version).toBe(3)
      expect(queryClient.getQueryData(pageKeys.detail('page'))).toMatchObject({
        page: { version: 3 },
      })
    },
  )

  it('stops restore when preserving the current draft fails', async () => {
    const writes = setup()
    api.savePage.mockRejectedValue(new Error('Conflict'))
    await expect(
      writes.restore('old', () => ({ document: page().document }), confirmed),
    ).rejects.toThrow('Conflict')
    expect(api.restorePageRevision).not.toHaveBeenCalled()
    expect(writes.pending.value).toBe(false)
  })

  it('restores using the version returned by saving the current draft', async () => {
    const writes = setup()
    api.savePage.mockResolvedValue({ data: page(2) })
    api.restorePageRevision.mockResolvedValue({ data: page(3) })
    await writes.restore(
      'old',
      () => ({ document: page().document, themeLayout: false }),
      confirmed,
    )
    expect(api.restorePageRevision).toHaveBeenCalledWith({
      name: 'page',
      restoreRequest: { version: 2, revision: 'old' },
    })
    expect(writes.detail.value?.page.version).toBe(3)
  })

  it('rejects a changed version during verification without fetching content', async () => {
    const writes = setup()
    const verification = deferred<boolean>()
    const operation = writes.publish({ title: 'New', slug: 'path' }, () => verification.promise)
    writes.sync(page(2).page)
    verification.resolve(true)
    await expect(operation).rejects.toThrow('页面已更新')
    expect(api.getPage).not.toHaveBeenCalled()
    expect(api.updatePageProperties).not.toHaveBeenCalled()
  })
})
