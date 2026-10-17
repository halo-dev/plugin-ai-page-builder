import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ImportPageModal from '../ImportPageModal.vue'
import CreatePagePanel from '../CreatePagePanel.vue'
import { pageKeys, queryClient } from '../../../lib/query-client'

const mocks = vi.hoisted(() => ({ importPage: vi.fn(), push: vi.fn(), status: vi.fn() }))
vi.mock('../../../api/clients', () => ({
  aiPageBuilderApi: { importPage: mocks.importPage },
  aiPageBuilderAiApi: { getAiPageBuilderAiStatus: mocks.status },
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@halo-dev/components', async (importOriginal) => {
  const original = await importOriginal<typeof import('@halo-dev/components')>()
  const { defineComponent, h } = await import('vue')
  return {
    ...original,
    VButton: defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: props.disabled }, slots.default?.()),
    }),
  }
})

enableAutoUnmount(afterEach)
afterEach(() => vi.useRealTimers())
beforeEach(() => {
  vi.restoreAllMocks()
  vi.resetAllMocks()
  queryClient.clear()
  mocks.importPage.mockResolvedValue({ data: { page: { name: 'imported-page' } } })
  mocks.status.mockResolvedValue({ data: { available: true, message: '' } })
})

function modal() {
  return mount(ImportPageModal)
}
function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((node) => node.text() === text)!
}
async function select(wrapper: ReturnType<typeof modal>, file: File) {
  const input = wrapper.get<HTMLInputElement>('input[type=file]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}
async function submit(wrapper: ReturnType<typeof modal>) {
  await button(wrapper, '导入').trigger('click')
  await flushPromises()
}

describe('page archive import', () => {
  it('opens the import modal next to manual creation and cancels it', async () => {
    const wrapper = mount(CreatePagePanel, {
      global: { stubs: { ChatComposer: true, ManualCreateModal: true } },
    })
    await flushPromises()
    const manual = button(wrapper, '手动创建页面')
    const entry = button(wrapper, '导入页面')
    expect(entry.classes()).toEqual(manual.classes())
    expect(entry.element.parentElement).toBe(manual.element.parentElement)
    await entry.trigger('click')
    expect(wrapper.findComponent(ImportPageModal).exists()).toBe(true)
    await button(wrapper, '取消').trigger('click')
    expect(wrapper.findComponent(ImportPageModal).exists()).toBe(false)
  })

  it('imports the selected file, invalidates page queries and opens the returned page', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = modal()
    expect(wrapper.text()).toContain('页面包可能包含 JavaScript 脚本')
    const file = new File(['zip'], 'page.ZIP', { type: 'application/octet-stream' })
    await select(wrapper, file)
    await submit(wrapper)
    expect(mocks.importPage).toHaveBeenCalledTimes(1)
    expect(mocks.importPage.mock.calls[0]![0].body).toBe(file)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: pageKeys.all })
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'AiPageBuilderEditor',
      params: { name: 'imported-page' },
    })
  })

  it('does not submit without a file', async () => {
    const wrapper = modal()
    await submit(wrapper)
    expect(mocks.importPage).not.toHaveBeenCalled()
    expect(wrapper.get('[role=alert]').text()).toBe('请选择 ZIP 文件。')
  })

  it.each(['page.txt', 'page.zip.txt', 'page'])('rejects the invalid filename %s', async (name) => {
    const wrapper = modal()
    await select(wrapper, new File(['zip'], name, { type: 'application/zip' }))
    await submit(wrapper)
    expect(mocks.importPage).not.toHaveBeenCalled()
    expect(wrapper.get('[role=alert]').text()).toBe('请选择 .zip 格式的文件。')
  })

  it('rejects files over 4 MiB and accepts the exact size limit after correcting the file', async () => {
    const wrapper = modal()
    await select(wrapper, new File([new Uint8Array(4 * 1024 * 1024 + 1)], 'large.zip'))
    await submit(wrapper)
    expect(mocks.importPage).not.toHaveBeenCalled()
    expect(wrapper.get('[role=alert]').text()).toBe('ZIP 文件大小不能超过 4 MiB。')
    await select(wrapper, new File([new Uint8Array(4 * 1024 * 1024)], 'page.zip'))
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    await submit(wrapper)
    expect(mocks.importPage).toHaveBeenCalledTimes(1)
  })

  it('preserves the file on failure and allows retry without selecting again', async () => {
    mocks.importPage.mockRejectedValueOnce({ response: { data: { detail: 'ZIP 文件无效' } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = modal()
    const file = new File(['zip'], 'page.zip')
    await select(wrapper, file)
    await submit(wrapper)
    expect(wrapper.get('[role=alert]').text()).toBe('ZIP 文件无效')
    expect(mocks.push).not.toHaveBeenCalled()
    expect(invalidate).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(button(wrapper, '导入').attributes('disabled')).toBeUndefined()
    await submit(wrapper)
    expect(mocks.importPage).toHaveBeenCalledTimes(2)
    expect(mocks.importPage.mock.calls[1]![0].body).toBe(file)
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    expect(mocks.push).toHaveBeenCalledTimes(1)
  })

  it('blocks rapid resubmission, file changes, cancel, header close, backdrop and Escape while pending', async () => {
    vi.useFakeTimers()
    let resolve!: (value: unknown) => void
    mocks.importPage.mockReturnValue(new Promise((done) => (resolve = done)))
    const wrapper = modal()
    await select(wrapper, new File(['zip'], 'page.zip'))
    const importButton = button(wrapper, '导入')
    const firstClick = importButton.trigger('click')
    const secondClick = importButton.trigger('click')
    await Promise.all([firstClick, secondClick])
    await flushPromises()
    expect(mocks.importPage).toHaveBeenCalledTimes(1)
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    expect(importButton.attributes('disabled')).toBeDefined()
    expect(button(wrapper, '取消').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[aria-label=关闭]').attributes('disabled')).toBeDefined()
    await button(wrapper, '取消').trigger('click')
    await wrapper.get('[aria-label=关闭]').trigger('click')
    await wrapper.get('.modal-layer').trigger('click')
    await wrapper.get('input').trigger('keyup', { key: 'Escape' })
    await wrapper.get('[role=dialog]').trigger('keyup', { key: 'Escape' })
    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.get<HTMLElement>('.modal-content').element.style.display).not.toBe('none')
    resolve({ data: { page: { name: 'imported-page' } } })
    await flushPromises()
    expect(button(wrapper, '取消').attributes('disabled')).toBeUndefined()
    await wrapper.get('[aria-label=关闭]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('allows Escape to close the idle modal', async () => {
    vi.useFakeTimers()
    const wrapper = modal()
    await wrapper.get('[role=dialog]').trigger('keyup', { key: 'Escape' })
    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
