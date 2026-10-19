import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import EditorView from '../../views/EditorView.vue'
import PageSettings from '../../components/PageSettings.vue'
import PublishPagePopper from '../../components/PublishPagePopper.vue'
import RuntimePreview from '../../components/RuntimePreview.vue'
import SourceEditor from '../../components/SourceEditor.vue'
import type { PageDetail } from '../../api/generated'
import { queryClient } from '../../lib/query-client'
const mocks = vi.hoisted(() => ({
  get: vi.fn<(...args: unknown[]) => unknown>(),
  save: vi.fn<(...args: unknown[]) => unknown>(),
  updateSlug: vi.fn<(...args: unknown[]) => unknown>(),
  themeContext: vi.fn<(...args: unknown[]) => unknown>(),
  warning: vi.fn<(options: { onCancel: () => void }) => void>(),
  toastError: vi.fn<(message: string) => void>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: { getPage: mocks.get, savePage: mocks.save, updatePageSlug: mocks.updateSlug },
  aiPageBuilderChatApi: {
    getThemeContext: mocks.themeContext,
    validatePageContent: vi.fn<(...args: unknown[]) => unknown>(),
  },
  chatUrl: (name: string) => `/pages/${name}/chat`,
  pagePreviewUrl: (name: string) => `/preview/${name}`,
  getPublicSiteUrl: () => Promise.resolve('http://localhost'),
}))
vi.mock('../../utils/error', () => ({
  errorMessage: (e: Error) => e.message,
}))
vi.mock('vue-router', () => ({
  useRoute: () => reactive({ params: { name: 'one' } }),
  useRouter: () => ({ push: vi.fn<(...args: unknown[]) => unknown>() }),
  onBeforeRouteLeave: vi.fn<(...args: unknown[]) => unknown>(),
  onBeforeRouteUpdate: vi.fn<(...args: unknown[]) => unknown>(),
}))
vi.mock('@halo-dev/components', () => ({
  VButton: defineComponent({
    props: ['disabled'],
    setup:
      (props, { slots, attrs }) =>
      () =>
        h('button', { ...attrs, disabled: props.disabled }, slots.default?.()),
  }),
  VModal: defineComponent({
    name: 'VModal',
    emits: ['close'],
    setup(_, { slots, emit, expose }) {
      expose({ close: () => emit('close') })
      return () => h('div', [slots.default?.(), slots.footer?.()])
    },
  }),
  VDropdown: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.popper?.()]),
  }),
  VSpace: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  VLoading: defineComponent({ setup: () => () => h('div') }),
  VStatusDot: defineComponent({ setup: () => () => h('div') }),
  VAlert: defineComponent({ props: ['title'], setup: (props) => () => h('div', props.title) }),
  IconArrowLeft: defineComponent({ setup: () => () => h('i') }),
  Dialog: { warning: mocks.warning },
  Toast: {
    success: vi.fn<(...args: unknown[]) => unknown>(),
    info: vi.fn<(...args: unknown[]) => unknown>(),
    error: mocks.toastError,
  },
}))
vi.mock('../../components/AiPanel.vue', () => ({
  default: defineComponent({ setup: () => () => h('textarea', { class: 'chat-input' }) }),
}))
vi.mock('../../components/HistoryPanel.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}))
const page = {
  page: { name: 'one', slug: 'one', version: 1, draftRevision: 'r1' },
  document: { mode: 'visual', title: 'Original', html: '<p>Hello</p>', css: '', schemaVersion: 1 },
} as PageDetail
const globals = {
  stubs: {
    VisualEditor: defineComponent({
      name: 'VisualEditor',
      props: ['name', 'document', 'validated', 'disabled', 'device'],
      emits: ['update:document'],
      setup: () => () => h('visual-editor-stub'),
    }),
    FormKit: defineComponent({
      name: 'FormKit',
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.default?.({ state: { valid: true } })),
    }),
    VCodemirror: true,
  },
}
beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve({ ok: true, type: 'basic', status: 200 })),
  )
  vi.clearAllMocks()
  queryClient.clear()
  mocks.get.mockResolvedValue({ data: page })
  mocks.themeContext.mockResolvedValue({
    data: {
      themeName: 'theme-test',
      displayName: 'Test',
      layoutState: 'SUPPORTED',
      sources: [{ path: 'base.css', kind: 'css', content: 'body { color: navy }' }],
      notes: [],
      previewCss: 'body { color: navy }',
    },
  })
})
async function editor() {
  const wrapper = mount(EditorView, { global: globals })
  await flushPromises()
  return wrapper
}
describe('workspace integration', () => {
  it('adopts a title saved during first publication and locks working edits until completion', async () => {
    const wrapper = await editor()
    const popper = wrapper.getComponent(PublishPagePopper)
    const canvas = wrapper.findComponent({ name: 'VisualEditor' })
    popper.vm.$emit('pending', true)
    await flushPromises()
    expect(canvas.props('disabled')).toBe(true)
    canvas.vm.$emit('update:document', { ...page.document, title: 'Concurrent edit' })
    const updated = {
      page: { ...page.page, title: 'Published title', version: 2, draftRevision: 'r2' },
      document: { ...page.document, title: 'Published title' },
    }
    popper.vm.$emit('updated', updated)
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('Published title')
    expect(canvas.props('document')).toEqual(updated.document)
    popper.vm.$emit('published', {
      ...updated,
      page: { ...updated.page, version: 3, publishedRevision: 'r2' },
    })
    popper.vm.$emit('pending', false)
    await flushPromises()
    expect(canvas.props('disabled')).toBe(false)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '保存')!
        .attributes('disabled'),
    ).toBeDefined()
    wrapper.unmount()
  })
  it('defaults to canvas and does not save merely opening or switching views', async () => {
    const wrapper = await editor()
    expect(wrapper.find('visual-editor-stub').exists()).toBe(true)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '源码')!
      .trigger('click')
    expect(wrapper.findComponent(SourceEditor).exists()).toBe(true)
    expect(mocks.save).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('runs the saved document in a sandboxed theme preview iframe', async () => {
    const wrapper = await editor()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '源码')!
      .trigger('click')
    const source = wrapper.findComponent(SourceEditor)
    source.vm.$emit('change', { js: 'unsaved()' })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '运行')!
      .trigger('click')
    await flushPromises()
    const runtime = wrapper.findComponent(RuntimePreview)
    expect(runtime.props('document')).toEqual(page.document)
    expect(runtime.props('src')).toBe('/preview/one')
    const frame = runtime.find('iframe')
    expect(frame.attributes('sandbox')).toBe('allow-scripts')
    expect(frame.attributes('src')).toBe('/preview/one')
    expect(frame.attributes('srcdoc')).toBeUndefined()
    expect(mocks.save).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('loads theme context once without modifying the document or making it dirty', async () => {
    let resolveContext!: (value: unknown) => void
    mocks.themeContext.mockReturnValue(
      new Promise((resolve) => {
        resolveContext = resolve
      }),
    )
    const wrapper = await editor()
    const canvas = wrapper.findComponent({ name: 'VisualEditor' })
    expect(canvas.props('document')).toEqual(page.document)
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '保存')!
        .attributes('disabled'),
    ).toBeDefined()
    resolveContext({
      data: {
        themeName: 'theme-test',
        displayName: 'Test',
        layoutState: 'SUPPORTED',
        sources: [{ path: 'base.css', kind: 'css', content: 'private theme source' }],
        notes: [],
        previewCss: 'body { color: navy }',
      },
    })
    await flushPromises()
    expect(canvas.props('name')).toBe('one')
    expect(canvas.props('document')).toEqual(page.document)
    expect(wrapper.text()).not.toContain('private theme source')
    expect(window.document.head.textContent).not.toContain('body { color: navy }')
    expect(
      wrapper
        .findAll('button')
        .find((b) => b.text() === '保存')!
        .attributes('disabled'),
    ).toBeDefined()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '源码')!
      .trigger('click')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '编辑')!
      .trigger('click')
    expect(mocks.themeContext).toHaveBeenCalledTimes(1)
    expect(mocks.save).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('keeps manual editing and saving available when theme context fails', async () => {
    mocks.themeContext.mockRejectedValue(new Error('Theme unavailable'))
    const wrapper = await editor()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    const canvas = wrapper.findComponent({ name: 'VisualEditor' })
    expect(canvas.props('disabled')).toBe(false)
    const edited = { ...page.document, title: 'Manually edited' }
    canvas.vm.$emit('update:document', edited)
    await flushPromises()
    mocks.save.mockResolvedValue({ data: { ...page, document: edited } })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledWith({
      name: 'one',
      savePageRequest: { version: 1, document: edited, source: 'manual' },
    })
    expect(mocks.themeContext).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('keeps native chat undo while page undo remains unified', async () => {
    const wrapper = await editor()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '源码')!
      .trigger('click')
    wrapper.findComponent(SourceEditor).vm.$emit('change', { html: '<p>Changed</p>' })
    await flushPromises()
    const input = wrapper.find('textarea').element
    const nativeUndo = new KeyboardEvent('keydown', {
      key: 'z',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    })
    input.dispatchEvent(nativeUndo)
    expect(nativeUndo.defaultPrevented).toBe(false)
    expect(wrapper.findComponent(SourceEditor).props('document').html).toBe('<p>Changed</p>')
    window.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true }),
    )
    await flushPromises()
    expect(wrapper.findComponent(SourceEditor).props('document').html).toBe('<p>Hello</p>')
    wrapper.unmount()
  })
  it('reports save failures with a toast and keeps the dirty draft', async () => {
    const wrapper = await editor()
    await wrapper
      .findAll('button')
      .find((b) => b.attributes('aria-label') === '页面设置')!
      .trigger('click')
    wrapper.findComponent(PageSettings).vm.$emit('apply', { title: 'Unsaved', description: '' })
    await flushPromises()
    mocks.save.mockRejectedValue(new Error('Conflict'))
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .trigger('click')
    await flushPromises()
    expect(mocks.toastError).toHaveBeenCalledWith('Conflict')
    expect(wrapper.findAll('button').some((b) => b.text() === '载入最新版本')).toBe(false)
    expect(wrapper.find('h1').text()).toBe('Unsaved')
    wrapper.unmount()
  })
  it('saves the path independently and keeps both working edits and settings inputs', async () => {
    mocks.get.mockResolvedValue({
      data: { ...page, page: { ...page.page, publishedRevision: 'r1' } },
    })
    mocks.updateSlug.mockResolvedValue({
      data: {
        ...page,
        page: { ...page.page, slug: 'company/about', version: 2, publishedRevision: 'r1' },
      },
    })
    const wrapper = await editor()
    const canvas = wrapper.findComponent({ name: 'VisualEditor' })
    canvas.vm.$emit('update:document', { ...page.document, title: 'Working title' })
    await flushPromises()
    await wrapper.find('[aria-label="页面设置"]').trigger('click')
    const settings = wrapper.findComponent(PageSettings)
    const fields = settings.findAllComponents({ name: 'FormKit' })
    fields
      .find((field) => field.attributes('name') === 'title')!
      .vm.$emit('update:modelValue', 'Buffered title')
    fields
      .find((field) => field.attributes('name') === 'slug')!
      .vm.$emit('update:modelValue', 'company/about')
    await flushPromises()
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(mocks.updateSlug).toHaveBeenCalledWith({
      name: 'one',
      updatePageSlugRequest: { version: 1, slug: 'company/about' },
    })
    expect(wrapper.find('h1').text()).toBe('Buffered title')
    expect(canvas.props('name')).toBe('one')
    expect(mocks.save).not.toHaveBeenCalled()
    mocks.save.mockResolvedValue({
      data: {
        ...page,
        page: { ...page.page, version: 3 },
        document: { ...page.document, title: 'Buffered title' },
      },
    })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存')!
      .trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledWith({
      name: 'one',
      savePageRequest: {
        version: 2,
        document: expect.objectContaining({ title: 'Buffered title' }),
        source: 'manual',
      },
    })
    wrapper.unmount()
  })
  it('saves the layout toggle and rolls back on failure', async () => {
    const saveLayout = vi.fn<(themeLayout: boolean) => Promise<PageDetail | undefined>>()
    saveLayout.mockRejectedValueOnce(new Error('页面已变化')).mockResolvedValueOnce(page)
    const wrapper = mount(PageSettings, {
      props: {
        document: page.document,
        slug: 'one',
        themeLayout: true,
        disabled: false,
        published: false,
        saveSlug: vi.fn(),
        saveLayout,
      },
      global: globals,
    })
    const field = wrapper
      .findAllComponents({ name: 'FormKit' })
      .find((input) => input.attributes('name') === 'themeLayout')!
    field.vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(saveLayout).toHaveBeenNthCalledWith(1, false)
    // Failure rolls the checkbox back to the stored value.
    field.vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(saveLayout).toHaveBeenNthCalledWith(2, false)
    wrapper.unmount()
  })
  it('keeps the path input and reports the failure with a toast, then allows retry', async () => {
    const saveSlug = vi.fn<(slug: string) => Promise<PageDetail | undefined>>()
    saveSlug.mockRejectedValueOnce(new Error('路径已存在')).mockResolvedValueOnce(page)
    const wrapper = mount(PageSettings, {
      props: {
        document: page.document,
        slug: 'one',
        themeLayout: true,
        disabled: false,
        published: false,
        saveSlug,
        saveLayout: vi.fn(),
      },
      global: globals,
    })
    wrapper
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'slug')!
      .vm.$emit('update:modelValue', 'company/about')
    await flushPromises()
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(mocks.toastError).toHaveBeenCalledWith('路径已存在')
    expect(wrapper.emitted('apply')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
    // The input keeps the edited value so the user can correct and retry.
    const slug = wrapper
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'slug')!
    slug.vm.$emit('update:modelValue', 'company/new')
    await flushPromises()
    slug.vm.$emit('update:modelValue', 'company/about')
    await flushPromises()
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(saveSlug).toHaveBeenNthCalledWith(2, 'company/about')
    expect(wrapper.emitted('apply')).toEqual([[{}]])
    wrapper.unmount()
  })
  it('does not emit apply when unmounted while the slug save is in flight', async () => {
    let resolveSlug!: (value: PageDetail) => void
    const saveSlug = vi.fn<(slug: string) => Promise<PageDetail | undefined>>(
      () => new Promise((resolve) => (resolveSlug = resolve)),
    )
    const wrapper = mount(PageSettings, {
      props: {
        document: page.document,
        slug: 'one',
        themeLayout: true,
        disabled: false,
        published: false,
        saveSlug,
        saveLayout: vi.fn(),
      },
      global: globals,
    })
    const fields = wrapper.findAllComponents({ name: 'FormKit' })
    fields
      .find((field) => field.attributes('name') === 'title')!
      .vm.$emit('update:modelValue', 'Buffered title')
    fields
      .find((field) => field.attributes('name') === 'slug')!
      .vm.$emit('update:modelValue', 'two')
    await flushPromises()
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(saveSlug).toHaveBeenCalledWith('two')
    wrapper.unmount()
    resolveSlug(page)
    await flushPromises()
    expect(wrapper.emitted('apply')).toBeUndefined()
  })
  it('blocks submit while the layout save is in flight but still allows close', async () => {
    let resolveLayout!: (value: PageDetail) => void
    const saveLayout = vi.fn<(themeLayout: boolean) => Promise<PageDetail | undefined>>(
      () => new Promise((resolve) => (resolveLayout = resolve)),
    )
    const saveSlug = vi.fn<(slug: string) => Promise<PageDetail | undefined>>()
    const wrapper = mount(PageSettings, {
      props: {
        document: page.document,
        slug: 'one',
        themeLayout: true,
        disabled: false,
        published: false,
        saveSlug,
        saveLayout,
      },
      global: globals,
    })
    const fields = wrapper.findAllComponents({ name: 'FormKit' })
    fields
      .find((field) => field.attributes('name') === 'slug')!
      .vm.$emit('update:modelValue', 'two')
    fields
      .find((field) => field.attributes('name') === 'themeLayout')!
      .vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(saveLayout).toHaveBeenCalledWith(false)
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(saveSlug).not.toHaveBeenCalled()
    expect(wrapper.emitted('apply')).toBeUndefined()
    // VModal hides itself before emitting close, so closing is always allowed;
    // the parent discards the late layout result instead.
    wrapper.findComponent({ name: 'VModal' }).vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(1)
    resolveLayout(page)
    await flushPromises()
    wrapper.unmount()
  })
  it('emits an empty settings patch when a legacy description is untouched', async () => {
    const wrapper = mount(PageSettings, {
      props: {
        document: page.document,
        slug: 'one',
        themeLayout: true,
        disabled: false,
        published: false,
        saveSlug: vi.fn(),
        saveLayout: vi.fn(),
      },
      global: globals,
    })
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    expect(wrapper.emitted('apply')).toEqual([[{}]])
    wrapper.unmount()
  })
  it('does not mutate the document when cancelling settings', async () => {
    const wrapper = mount(PageSettings, {
      props: {
        document: page.document,
        slug: 'one',
        themeLayout: true,
        disabled: false,
        published: false,
        saveSlug: vi.fn(),
        saveLayout: vi.fn(),
      },
      global: globals,
    })
    const fields = wrapper.findAllComponents({ name: 'FormKit' })
    const title = fields.find((field) => field.attributes('name') === 'title')
    title?.vm.$emit('update:modelValue', 'Discard me')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '取消')!
      .trigger('click')
    expect(wrapper.emitted('apply')).toBeUndefined()
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(page.document.title).toBe('Original')
    wrapper.unmount()
  })
})
