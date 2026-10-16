import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, shallowRef } from 'vue'
import {
  applyUIMessageChunk,
  createUIMessageReducer,
  type UIMessage,
  type UIMessageChunk,
} from '@halo-dev/ai-foundation-sdk'
import { stagedPage, useStreamingPreview } from '../useStreamingPreview'
const validate = vi.hoisted(() => vi.fn<(...args: unknown[]) => Promise<void>>())
vi.mock('../../api/clients', () => ({ aiPageBuilderChatApi: { validatePageContent: validate } }))
let scope = effectScope()
const hash = 'a'.repeat(64)
beforeEach(() => {
  scope = effectScope()
  vi.useFakeTimers()
  validate.mockReset()
  validate.mockResolvedValue()
})
afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})
function setup() {
  const messages = shallowRef<UIMessage[]>([])
  const runId = shallowRef<string>()
  const state = scope.run(() =>
    useStreamingPreview({
      messages,
      runId,
      baseline: () => ({ html: '<p>Original</p>', css: 'p{color:red}' }),
    }),
  )!
  let reducer = createUIMessageReducer({ messageId: 'assistant' })
  runId.value = 'run'
  function chunk(chunk: UIMessageChunk) {
    reducer = applyUIMessageChunk(reducer, chunk)
    messages.value = [reducer.message]
  }
  return { ...state, chunk, runId, messages }
}
describe('temporary streamed candidates', () => {
  it('discards partial arguments at a recovery boundary and uses fresh tool ids with the validated baseline', async () => {
    const state = setup()
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'attempt-1-good',
      toolName: 'update_current_page',
      input: { html: '<p>Saved</p>' },
    })
    state.accept({
      runId: 'run',
      toolCallId: 'attempt-1-good',
      baseHash: hash,
      html: '<p>Saved</p>',
      css: 'p{color:blue}',
    })
    state.chunk({
      type: 'tool-input-start',
      toolCallId: 'attempt-1-partial',
      toolName: 'update_current_page',
    })
    state.chunk({
      type: 'tool-input-delta',
      toolCallId: 'attempt-1-partial',
      inputTextDelta: '{"html":"<p>truncated',
    })
    state.restartAttempt()
    expect(state.preview.value).toBeUndefined()
    state.chunk({
      type: 'tool-input-start',
      toolCallId: 'attempt-2-partial',
      toolName: 'update_current_page',
    })
    state.chunk({
      type: 'tool-input-delta',
      toolCallId: 'attempt-2-partial',
      inputTextDelta: '{"css":"p{color:green}"}',
    })
    await vi.advanceTimersByTimeAsync(250)
    expect(state.preview.value?.content).toEqual({ html: '<p>Saved</p>', css: 'p{color:green}' })
    expect(state.preview.value?.source).not.toContain('truncated')
  })
  it('accepts named staged events through the actual SDK reducer before showing a candidate', () => {
    const state = setup()
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'one',
      toolName: 'patch_current_page',
      input: {},
    })
    const data = { runId: 'run', toolCallId: 'one', baseHash: hash, html: '<p>Staged</p>', css: '' }
    state.chunk({ type: 'data-page-staged', name: 'page-staged', id: 'one', data })
    state.accept(data)
    expect(state.preview.value?.content?.html).toBe('<p>Staged</p>')
    expect(state.preview.value?.status).toBe('staged')
    state.chunk({ type: 'data-page-committed', name: 'page-committed', id: 'run', data: {} })
  })
  it('uses actual SDK partial JSON with Unicode and throttles escaped source before validation', async () => {
    const state = setup()
    state.chunk({ type: 'tool-input-start', toolCallId: 'one', toolName: 'update_current_page' })
    state.chunk({ type: 'tool-input-delta', toolCallId: 'one', inputTextDelta: '{"html":"<p>你好' })
    await vi.advanceTimersByTimeAsync(100)
    expect(state.preview.value?.source).toContain('你好')
    expect(state.preview.value?.content).toBeUndefined()
    state.chunk({ type: 'tool-input-delta', toolCallId: 'one', inputTextDelta: '🌍</p>"}' })
    await vi.advanceTimersByTimeAsync(250)
    expect(validate).toHaveBeenLastCalledWith(
      { validatePageContentRequest: { html: '<p>你好🌍</p>', css: 'p{color:red}' } },
      { signal: expect.any(AbortSignal) },
    )
    expect(state.preview.value?.content?.html).toBe('<p>你好🌍</p>')
  })
  it('keeps last valid content on invalid CSS and ignores old tool responses', async () => {
    const state = setup()
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'one',
      toolName: 'update_current_page',
      input: { html: '<p>First</p>' },
    })
    await vi.advanceTimersByTimeAsync(250)
    let finish!: () => void
    validate.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'two',
      toolName: 'update_current_page',
      input: { html: '<p>Old response</p>' },
    })
    await vi.advanceTimersByTimeAsync(250)
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'three',
      toolName: 'update_current_page',
      input: { html: '<p>Newest</p>' },
    })
    await vi.advanceTimersByTimeAsync(250)
    finish()
    await Promise.resolve()
    expect(state.preview.value?.content?.html).toBe('<p>Newest</p>')
    validate.mockRejectedValueOnce(new Error('invalid'))
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'three',
      toolName: 'update_current_page',
      input: { css: 'invalid' },
    })
    await vi.advanceTimersByTimeAsync(250)
    expect(state.preview.value?.status).toBe('waiting')
    expect(state.preview.value?.content?.html).toBe('<p>Newest</p>')
  })
  it('shows patch source without applying it and accepts only staged data for the current run/tool', async () => {
    const state = setup()
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'one',
      toolName: 'patch_current_page',
      input: {
        baseHash: hash,
        patches: [{ field: 'html', oldText: 'Original', newText: 'Changed' }],
      },
    })
    await vi.advanceTimersByTimeAsync(250)
    expect(validate).not.toHaveBeenCalled()
    expect(state.preview.value?.source).toContain('oldText')
    state.accept({ runId: 'other', toolCallId: 'one', baseHash: hash, html: '<p>Bad</p>', css: '' })
    expect(state.preview.value?.content).toBeUndefined()
    state.accept({
      runId: 'run',
      toolCallId: 'one',
      baseHash: hash,
      html: '<p>Changed</p>',
      css: '',
    })
    expect(state.preview.value?.status).toBe('staged')
    expect(state.preview.value?.content?.html).toBe('<p>Changed</p>')
    state.discard()
    state.accept({ runId: 'run', toolCallId: 'one', baseHash: hash, html: '<p>Late</p>', css: '' })
    expect(state.preview.value).toBeUndefined()
  })
  it('invalidates pending validation on cancellation and run changes', async () => {
    const state = setup()
    let finish!: () => void
    validate.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    state.chunk({
      type: 'tool-input-available',
      toolCallId: 'one',
      toolName: 'update_current_page',
      input: { html: '<p>Candidate</p>', css: '' },
    })
    await vi.advanceTimersByTimeAsync(250)
    state.runId.value = undefined
    finish()
    await Promise.resolve()
    expect(state.preview.value).toBeUndefined()
  })
  it('strictly validates staged shape and byte limits', () => {
    const valid = { runId: 'r', toolCallId: 't', baseHash: hash, html: '', css: '' }
    expect(stagedPage(valid, 'r')).toEqual(valid)
    expect(stagedPage({ ...valid, extra: true }, 'r')).toBeUndefined()
    expect(stagedPage({ ...valid, html: null }, 'r')).toBeUndefined()
    expect(stagedPage({ ...valid, baseHash: 'oops' }, 'r')).toBeUndefined()
    expect(stagedPage({ ...valid, html: '你'.repeat(200001) }, 'r')).toBeUndefined()
  })
})
