import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useWorkingDocument } from '../useWorkingDocument'
import type { PageDetail, PageDocument } from '../../api/generated'
const api = vi.hoisted(() => ({
  save: vi.fn<(...args: unknown[]) => unknown>(),
  validate: vi.fn<(...args: unknown[]) => unknown>(),
  updateSlug: vi.fn<(...args: unknown[]) => unknown>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: { savePage: api.save, updatePageSlug: api.updateSlug },
  aiPageBuilderChatApi: { validatePageContent: api.validate },
}))
vi.mock('../../utils/error', () => ({
  errorMessage: (e: Error) => e.message,
}))
const legacy: PageDocument = {
  schemaVersion: 1,
  mode: 'visual',
  title: '旧页面',
  html: '<p>Hello</p>',
  css: ':root { --color: red; }',
  projectData: { old: true },
  editorVersion: 'old',
}
function page(document = legacy, version = 1): PageDetail {
  return { document, page: { name: 'page', version, draftRevision: `r${version}` } } as PageDetail
}
let scope = effectScope()
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  api.validate.mockResolvedValue(undefined)
  scope = effectScope()
})
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})
function setup() {
  const state = scope.run(useWorkingDocument)!
  state.adopt(page(), true)
  return state
}
describe('working document', () => {
  it('does not dirty, convert or save an untouched legacy document before agent start', async () => {
    const state = setup()
    expect(state.dirty.value).toBe(false)
    expect(await state.prepareAgent()).toBe(1)
    expect(api.save).not.toHaveBeenCalled()
    expect(state.document.value).toEqual(legacy)
    expect(state.canUndo.value).toBe(false)
  })
  it('saves a manual edit once before agent and makes committed page one undoable change', async () => {
    const state = setup()
    state.patch({ html: '<p>Manual</p>' })
    expect(state.document.value?.mode).toBe('code')
    await vi.advanceTimersByTimeAsync(400)
    api.save.mockImplementation(async (args) => ({
      data: page(
        (args as { savePageRequest: { document: PageDocument } }).savePageRequest.document,
        2,
      ),
    }))
    expect(await state.prepareAgent()).toBe(2)
    expect(api.save).toHaveBeenCalledTimes(1)
    state.adopt(page({ ...state.document.value!, html: '<p>AI</p>' }, 3))
    state.move('undo')
    expect(state.document.value?.html).toBe('<p>Manual</p>')
    expect(state.dirty.value).toBe(true)
    expect(state.detail.value?.page.version).toBe(3)
    state.move('redo')
    expect(state.document.value?.html).toBe('<p>AI</p>')
    expect(state.dirty.value).toBe(false)
  })
  it('debounces validation and rejects stale success after newer invalid CSS', async () => {
    const state = setup()
    let finish!: () => void
    api.validate.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    state.patch({ css: 'p {color:red}' })
    await vi.advanceTimersByTimeAsync(399)
    expect(api.validate).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    state.patch({ css: 'invalid' })
    api.validate.mockRejectedValueOnce(new Error('CSS 无效'))
    await vi.advanceTimersByTimeAsync(400)
    finish()
    await Promise.resolve()
    expect(state.valid.value).toBe(false)
    expect(state.validationError.value).toBe('CSS 无效')
    expect(state.document.value?.css).toBe('invalid')
    await expect(state.prepareAgent()).rejects.toThrow('校验')
    expect(api.save).not.toHaveBeenCalled()
  })
  it('locks preparation while allowing its internal save and snapshot to finish', async () => {
    const state = setup()
    state.patch({ title: 'Before send' })
    let finish!: () => void
    api.save.mockImplementationOnce(async (args) => {
      await new Promise<void>((resolve) => {
        finish = resolve
      })
      return {
        data: page(
          (args as { savePageRequest: { document: PageDocument } }).savePageRequest.document,
          2,
        ),
      }
    })
    const prepared = state.prepareAgent(async (saved) => ({ version: saved.page.version }))
    expect(state.locked.value).toBe(true)
    state.patch({ title: 'Blocked' })
    expect(state.document.value?.title).toBe('Before send')
    expect(await state.save()).toBeUndefined()
    finish()
    expect(await prepared).toEqual({ version: 2 })
    expect(state.locked.value).toBe(false)
    expect(api.save).toHaveBeenCalledTimes(1)
  })
  it('validates, saves and undoes JS as part of the same versioned document', async () => {
    const state = setup()
    state.patch({ js: 'document.body.dataset.ready = "yes"' })
    expect(state.document.value?.schemaVersion).toBe(2)
    expect(state.dirty.value).toBe(true)
    expect(state.valid.value).toBe(false)
    await vi.advanceTimersByTimeAsync(400)
    expect(api.validate).toHaveBeenCalledWith(
      {
        validatePageContentRequest: {
          html: legacy.html,
          css: legacy.css,
          js: 'document.body.dataset.ready = "yes"',
        },
      },
      { signal: expect.any(AbortSignal) },
    )
    api.save.mockImplementation(async (args) => ({
      data: page(
        (args as { savePageRequest: { document: PageDocument } }).savePageRequest.document,
        2,
      ),
    }))
    await state.save()
    expect(state.dirty.value).toBe(false)
    state.move('undo')
    expect(state.document.value?.js ?? '').toBe('')
    state.move('redo')
    expect(state.document.value?.js).toContain('dataset.ready')
  })
  it('updates only page metadata while preserving dirty content, validation and undo history', async () => {
    const state = setup()
    state.patch({
      html: '<p>Unsaved</p>',
      title: 'Unsaved title',
      description: 'Unsaved description',
    })
    const working = state.document.value
    const saved = state.detail.value!.document
    const result = page(legacy, 2)
    result.page.slug = 'company/about'
    api.updateSlug.mockResolvedValue({ data: result })
    await state.updateSlug('company/about')
    expect(api.updateSlug).toHaveBeenCalledWith({
      name: 'page',
      updatePageSlugRequest: { version: 1, slug: 'company/about' },
    })
    expect(state.detail.value?.page).toEqual(result.page)
    expect(state.detail.value?.document).toBe(saved)
    expect(state.document.value).toBe(working)
    expect(state.dirty.value).toBe(true)
    expect(state.validationState.value).toBe('pending')
    expect(api.save).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(400)
    expect(state.valid.value).toBe(true)
    state.move('undo')
    expect(state.document.value).toEqual(legacy)
    expect(state.dirty.value).toBe(false)
    state.move('redo')
    expect(state.document.value).toEqual(working)
    await vi.advanceTimersByTimeAsync(400)
    api.save.mockResolvedValue({ data: page(working, 3) })
    await state.save()
    expect(api.save).toHaveBeenCalledWith({
      name: 'page',
      savePageRequest: { version: 2, document: working, source: 'manual' },
    })
  })
  it('keeps a clean document clean and uses the new version for agent preparation', async () => {
    const state = setup()
    api.updateSlug.mockResolvedValue({ data: page(legacy, 2) })
    await state.updateSlug('company/about')
    expect(state.dirty.value).toBe(false)
    expect(state.canUndo.value).toBe(false)
    expect(await state.prepareAgent()).toBe(2)
    expect(api.save).not.toHaveBeenCalled()
  })
  it('locks slug updates and preserves state on conflict', async () => {
    const state = setup()
    state.patch({ title: 'Unsaved' })
    const before = state.detail.value
    let fail!: (error: Error) => void
    api.updateSlug.mockReturnValue(
      new Promise((_, reject) => {
        fail = reject
      }),
    )
    const request = state.updateSlug('company/about')
    expect(state.locked.value).toBe(true)
    expect(await state.updateSlug('another')).toBeUndefined()
    expect(await state.save()).toBeUndefined()
    fail(new Error('Conflict'))
    await expect(request).rejects.toThrow('Conflict')
    expect(state.locked.value).toBe(false)
    expect(state.detail.value).toBe(before)
    expect(state.document.value?.title).toBe('Unsaved')
    expect(state.dirty.value).toBe(true)
    expect(api.updateSlug).toHaveBeenCalledTimes(1)
  })
  it.each(['agentRunning', 'preparing'] as const)('blocks slug updates while %s', async (lock) => {
    const state = setup()
    state[lock].value = true
    expect(await state.updateSlug('company/about')).toBeUndefined()
    expect(api.updateSlug).not.toHaveBeenCalled()
  })
  it('freezes manual changes and undo while an agent is running', () => {
    const state = setup()
    state.patch({ title: 'Changed' })
    state.agentRunning.value = true
    state.patch({ title: 'Dropped' })
    state.move('undo')
    expect(state.document.value?.title).toBe('Changed')
  })
})
