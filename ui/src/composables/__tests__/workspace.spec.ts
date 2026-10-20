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
  warning: vi.fn<(options: { onCancel: () => void; onConfirm: () => void }) => void>(),
  publish: vi.fn(),
  restore: vi.fn(),
  route: { params: { name: 'one' } },
  toastError: vi.fn<(message: string) => void>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    getPage: mocks.get,
    savePage: mocks.save,
    updatePageProperties: mocks.updateSlug,
    publishPage: mocks.publish,
    restorePageRevision: mocks.restore,
  },
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
  useRoute: () => mocks.route,
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
  __esModule: true,
  default: defineComponent({
    name: 'HistoryPanel',
    emits: ['restore'],
    setup: () => () => h('div'),
  }),
}))
const page = {
  page: {
    name: 'one',
    title: 'Original',
    slug: 'one',
    version: 1,
    themeLayout: true,
    description: '',
    updatedAt: '',
    hasUnpublishedChanges: false,
    draftRevision: 'r1',
  },
  document: { mode: 'visual', html: '<p>Hello</p>', css: '', schemaVersion: 1 },
} as PageDetail
const globals = {
  directives: { tooltip: {} },
  stubs: {
    VisualEditor: defineComponent({
      name: 'VisualEditor',
      props: ['name', 'document', 'pageVersion', 'validated', 'disabled', 'device'],
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
  vi.resetAllMocks()
  mocks.route = reactive({ params: { name: 'one' } })
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
async function validated(wrapper: ReturnType<typeof mount>) {
  await vi.waitFor(() =>
    expect(wrapper.findComponent({ name: 'VisualEditor' }).props('validated')).toBe(true),
  )
}
async function editor() {
  const wrapper = mount(EditorView, { global: globals })
  await flushPromises()
  return wrapper
}
describe('workspace integration', () => {
  it('keeps the content version pinned after a settings conflict instead of accepting unseen content', async () => {
    const wrapper = await editor()
    const working = { ...page.document, html: '<p>Local unsaved content</p>' }
    wrapper.getComponent({ name: 'VisualEditor' }).vm.$emit('update:document', working)
    await flushPromises()
    await validated(wrapper)
    mocks.updateSlug.mockRejectedValue({ response: { status: 409 }, message: 'Page changed' })
    mocks.get.mockResolvedValue({
      data: {
        page: { ...page.page, version: 2, draftRevision: 'remote' },
        document: { ...page.document, html: '<p>Remote changes</p>' },
      },
    })
    await wrapper.get('[aria-label="页面设置"]').trigger('click')
    wrapper.getComponent(PageSettings).vm.$emit('apply', { title: 'New title' })
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(wrapper.getComponent({ name: 'VisualEditor' }).props('document')).toEqual(working)
    expect(wrapper.text()).toContain('载入最新版本')
    wrapper.getComponent(PageSettings).vm.$emit('close')
    await flushPromises()
    mocks.save.mockRejectedValue({ response: { status: 409 }, message: 'Page changed' })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存')!
      .trigger('click')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledWith({
      name: 'one',
      savePageRequest: { version: 1, document: working, source: 'manual' },
    })
    wrapper.unmount()
  })
  it('does not warn about unsaved changes while loading another page from a clean editor', async () => {
    const wrapper = await editor()
    try {
      const initial = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(initial)
      expect(initial.defaultPrevented).toBe(false)
      mocks.get.mockReturnValue(new Promise(() => {}))
      mocks.route.params.name = 'two'
      await flushPromises()
      const loading = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(loading)
      expect(loading.defaultPrevented).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })
  it('adopts a title saved during first publication and locks working edits until completion', async () => {
    const wrapper = await editor()
    const popper = wrapper.getComponent(PublishPagePopper)
    const canvas = wrapper.findComponent({ name: 'VisualEditor' })
    const updated = {
      page: { ...page.page, title: 'Published title', version: 2, draftRevision: 'r2' },
      document: page.document,
    }
    mocks.updateSlug.mockResolvedValue({ data: updated })
    let finish!: (result: { data: PageDetail }) => void
    mocks.publish.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const operation = popper
      .props('writes')!
      .publish({ title: 'Published title', slug: 'one' }, async () => true)
    await flushPromises()
    expect(canvas.props('disabled')).toBe(true)
    canvas.vm.$emit('update:document', { ...page.document, html: '<p>Concurrent edit</p>' })
    expect(wrapper.find('h1').text()).toBe('Published title')
    expect(canvas.props('document')).toEqual(updated.document)
    finish({ data: { ...updated, page: { ...updated.page, version: 3, publishedRevision: 'r2' } } })
    await operation
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
  it('does not restore an old page after switching routes during confirmation', async () => {
    const wrapper = await editor()
    await wrapper.get('[aria-label="历史版本"]').trigger('click')
    await flushPromises()
    wrapper.getComponent({ name: 'HistoryPanel' }).vm.$emit('restore', 'old-one')
    await flushPromises()
    const confirmation = mocks.warning.mock.calls[mocks.warning.mock.calls.length - 1]![0]
    mocks.get.mockResolvedValue({
      data: {
        ...page,
        page: { ...page.page, name: 'two', title: 'Second' },
        document: page.document,
      },
    })
    mocks.route.params.name = 'two'
    await flushPromises()
    confirmation.onConfirm()
    await flushPromises()
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.restore).not.toHaveBeenCalled()
    expect(wrapper.get('h1').text()).toBe('Second')
    wrapper.unmount()
  })
  it('saves edits before restoring history and adopts the restored draft', async () => {
    const wrapper = await editor()
    wrapper.getComponent({ name: 'VisualEditor' }).vm.$emit('update:document', {
      ...page.document,
      html: '<p>Unsaved title</p>',
    })
    await flushPromises()
    await validated(wrapper)
    await wrapper.get('[aria-label="历史版本"]').trigger('click')
    await flushPromises()
    mocks.save.mockResolvedValue({
      data: {
        ...page,
        page: { ...page.page, version: 2 },
        document: { ...page.document, html: '<p>Unsaved title</p>' },
      },
    })
    mocks.restore.mockResolvedValue({
      data: {
        ...page,
        page: { ...page.page, version: 3 },
        document: { ...page.document, html: '<p>Restored</p>' },
      },
    })
    mocks.warning.mockImplementation(({ onConfirm }) => onConfirm())
    wrapper.getComponent({ name: 'HistoryPanel' }).vm.$emit('restore', 'old-one')
    await flushPromises()
    expect(mocks.restore).toHaveBeenCalledWith({
      name: 'one',
      restoreRequest: { version: 2, revision: 'old-one' },
    })
    expect(wrapper.get('h1').text()).toBe('Original')
    expect(wrapper.getComponent({ name: 'VisualEditor' }).props('document').html).toBe(
      '<p>Restored</p>',
    )
    expect(wrapper.findComponent({ name: 'HistoryPanel' }).exists()).toBe(false)
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
    const edited = { ...page.document, html: '<p>Manually edited</p>' }
    canvas.vm.$emit('update:document', edited)
    await flushPromises()
    await validated(wrapper)
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
    wrapper
      .getComponent({ name: 'VisualEditor' })
      .vm.$emit('update:document', { ...page.document, html: '<p>Unsaved</p>' })
    await flushPromises()
    await validated(wrapper)
    mocks.save.mockRejectedValue(new Error('Conflict'))
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .trigger('click')
    await flushPromises()
    expect(mocks.toastError).toHaveBeenCalledWith('Conflict')
    expect(wrapper.findAll('button').some((b) => b.text() === '载入最新版本')).toBe(false)
    expect(wrapper.getComponent({ name: 'VisualEditor' }).props('document').html).toBe(
      '<p>Unsaved</p>',
    )
    wrapper.unmount()
  })
  it('saves the path independently and keeps both working edits and settings inputs', async () => {
    mocks.get.mockResolvedValue({
      data: { ...page, page: { ...page.page, publishedRevision: 'r1' } },
    })
    mocks.updateSlug.mockResolvedValue({
      data: {
        ...page,
        page: {
          ...page.page,
          slug: 'company/about',
          title: 'Buffered title',
          version: 2,
          publishedRevision: 'r1',
        },
      },
    })
    const wrapper = await editor()
    const canvas = wrapper.findComponent({ name: 'VisualEditor' })
    canvas.vm.$emit('update:document', { ...page.document, html: '<p>Working title</p>' })
    await flushPromises()
    await validated(wrapper)
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
      updatePagePropertiesRequest: { version: 1, title: 'Buffered title', slug: 'company/about' },
    })
    expect(wrapper.find('h1').text()).toBe('Buffered title')
    expect(canvas.props('name')).toBe('one')
    expect(mocks.save).not.toHaveBeenCalled()
    mocks.save.mockResolvedValue({
      data: {
        ...page,
        page: { ...page.page, version: 3 },
        document: { ...page.document, html: '<p>Working title</p>' },
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
        document: expect.objectContaining({ html: '<p>Working title</p>' }),
        source: 'manual',
      },
    })
    wrapper.unmount()
  })
  it('saves layout as a property and refreshes preview without creating a content edit', async () => {
    mocks.updateSlug.mockResolvedValue({
      data: { ...page, page: { ...page.page, version: 2, themeLayout: false } },
    })
    const wrapper = await editor()
    const canvas = wrapper.findComponent({ name: 'VisualEditor' })
    await wrapper.find('[aria-label="页面设置"]').trigger('click')
    const settings = wrapper.findComponent(PageSettings)
    settings
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'themeLayout')!
      .vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(mocks.updateSlug).not.toHaveBeenCalled()
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(mocks.updateSlug).toHaveBeenCalledWith({
      name: 'one',
      updatePagePropertiesRequest: { version: 1, themeLayout: false },
    })
    expect(mocks.save).not.toHaveBeenCalled()
    expect(canvas.props('pageVersion')).toBe(2)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === '保存')!
        .attributes('disabled'),
    ).toBeDefined()
    wrapper.unmount()
  })
  it('applies the layout with the form instead of saving on toggle', async () => {
    const saveSlug = vi.fn<(slug: string) => Promise<PageDetail | undefined>>()
    const wrapper = mount(PageSettings, {
      props: {
        page: page.page,
        disabled: false,
        published: false,
      },
      global: globals,
    })
    const field = wrapper
      .findAllComponents({ name: 'FormKit' })
      .find((input) => input.attributes('name') === 'themeLayout')!
    field.vm.$emit('update:modelValue', false)
    await flushPromises()
    // Toggling only changes the form buffer; the parent saves on apply.
    expect(saveSlug).not.toHaveBeenCalled()
    expect(wrapper.emitted('apply')).toBeUndefined()
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(wrapper.emitted('apply')).toEqual([[{ themeLayout: false }]])
    wrapper.unmount()
  })
  it('submits all edited properties together without mutating the page', async () => {
    const wrapper = mount(PageSettings, {
      props: { page: page.page, disabled: false, published: false },
      global: globals,
    })
    const fields = wrapper.findAllComponents({ name: 'FormKit' })
    for (const [name, value] of Object.entries({
      title: 'New title',
      slug: 'new-path',
      description: 'New description',
      themeLayout: false,
    })) {
      fields
        .find((field) => field.attributes('name') === name)!
        .vm.$emit('update:modelValue', value)
    }
    await flushPromises()
    expect(wrapper.emitted('apply')).toBeUndefined()
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    expect(wrapper.emitted('apply')).toEqual([
      [
        {
          title: 'New title',
          slug: 'new-path',
          description: 'New description',
          themeLayout: false,
        },
      ],
    ])
    expect(page.page.title).toBe('Original')
    wrapper.unmount()
  })
  it('preserves edited fields while adopting refreshed properties', async () => {
    const wrapper = mount(PageSettings, {
      props: { page: page.page, disabled: false, published: false },
      global: globals,
    })
    wrapper
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'title')!
      .vm.$emit('update:modelValue', 'Buffered')
    await flushPromises()
    await wrapper.setProps({
      page: { ...page.page, version: 2, slug: 'remote-path', description: 'Remote description' },
    })
    wrapper.findComponent({ ref: 'form' }).vm.$emit('submit')
    expect(wrapper.emitted('apply')).toEqual([[{ title: 'Buffered' }]])
    wrapper.unmount()
  })
  it('emits an empty settings patch when a legacy description is untouched', async () => {
    const wrapper = mount(PageSettings, {
      props: {
        page: page.page,
        disabled: false,
        published: false,
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
        page: page.page,
        disabled: false,
        published: false,
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
    expect(page.page.title).toBe('Original')
    wrapper.unmount()
  })
})
