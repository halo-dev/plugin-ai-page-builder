import { flushPromises, mount } from '@vue/test-utils'
import { shallowRef } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AiPanel from '../../components/AiPanel.vue'
import type { AnnotationDraft } from '../annotations'

const mocks = vi.hoisted(() => ({
  send: vi.fn<(prompt: string, retry?: boolean) => Promise<boolean>>(),
}))
vi.mock('../../composables/usePageChat', () => ({
  usePageChat: () => ({
    sessions: shallowRef([]),
    sessionId: shallowRef('one'),
    sessionBusy: shallowRef(false),
    hasMore: shallowRef(false),
    loadingEarlier: shallowRef(false),
    messages: shallowRef([]),
    preview: shallowRef(undefined),
    loading: shallowRef(false),
    available: shallowRef(true),
    modelName: shallowRef('model'),
    error: shallowRef(''),
    notice: shallowRef(''),
    running: shallowRef(false),
    activeRun: shallowRef(undefined),
    waitingForUser: shallowRef(false),
    pendingQuestion: shallowRef(undefined),
    questions: shallowRef([]),
    answering: shallowRef(false),
    answerError: shallowRef(''),
    canSend: shallowRef(true),
    lastPrompt: shallowRef(''),
    recover: async () => {},
    send: mocks.send,
  }),
}))
vi.mock('@halo-dev/components', () => ({
  Dialog: {},
  VDropdown: { template: '<div><slot /></div>' },
  VDropdownItem: { template: '<button><slot /></button>' },
  VLoading: { template: '<span />' },
}))
const annotation: AnnotationDraft = {
  id: 'a',
  path: [0],
  tag: 'h2',
  summary: 'Heading',
  html: '<h2>Heading</h2>',
  comment: '放大标题',
  baseHash: 'hash',
  stale: false,
}
async function panel() {
  const wrapper = mount(AiPanel, {
    props: {
      pageName: 'one',
      disabled: false,
      prepare: async () => 1,
      document: { schemaVersion: 2, mode: 'code', html: '', css: '' },
      annotations: [],
    },
    global: { stubs: { FormKit: true }, directives: { 'close-popper': {} } },
  })
  await flushPromises()
  await wrapper.setProps({ annotations: [annotation] })
  return wrapper
}
beforeEach(() => mocks.send.mockReset())

describe('annotation chat submission', () => {
  it('sends annotations without extra text and collapses the list immediately', async () => {
    let finish!: (sent: boolean) => void
    mocks.send.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const wrapper = await panel()
    const toggle = () => wrapper.get('button[aria-expanded]')
    expect(toggle().attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('button[aria-label="发送"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('button[aria-label="发送"]').trigger('click')
    expect(mocks.send).toHaveBeenCalledWith('按照批注修改页面')
    expect(toggle().attributes('aria-expanded')).toBe('false')
    expect(wrapper.props('annotations')).toEqual([annotation])
    finish(true)
    await flushPromises()
    await toggle().trigger('click')
    expect(wrapper.text()).toContain('放大标题')
    await wrapper.setProps({ annotations: [annotation, { ...annotation, id: 'b' }] })
    expect(toggle().attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it.each(['', '  调整间距  '])('restores the original input on failure: %j', async (text) => {
    mocks.send.mockResolvedValue(false)
    const wrapper = await panel()
    await wrapper.get('textarea').setValue(text)
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(mocks.send).toHaveBeenCalledWith(text.trim() || '按照批注修改页面')
    expect(wrapper.get('textarea').element.value).toBe(text)
    expect(wrapper.props('annotations')).toEqual([annotation])
    expect(wrapper.get('button[aria-expanded]').attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })
})
