import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import HomeView from '../../views/HomeView.vue'
import PageSettings from '../../components/PageSettings.vue'
import { pageKeys, queryClient } from '../../lib/query-client'

const mocks = vi.hoisted(() => ({
  list: vi.fn<(...args: unknown[]) => unknown>(),
  get: vi.fn<(...args: unknown[]) => unknown>(),
  updateSlug: vi.fn<(...args: unknown[]) => unknown>(),
  updateLayout: vi.fn<(...args: unknown[]) => unknown>(),
  save: vi.fn<(...args: unknown[]) => unknown>(),
  unpublish: vi.fn<(...args: unknown[]) => unknown>(),
  warning: vi.fn<(options: { onConfirm: () => void }) => void>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    listPages: mocks.list,
    getPage: mocks.get,
    updatePageSlug: mocks.updateSlug,
    updatePageLayout: mocks.updateLayout,
    savePage: mocks.save,
    unpublishPage: mocks.unpublish,
    publishPage: vi.fn<(...args: unknown[]) => unknown>(),
    deletePage: vi.fn<(...args: unknown[]) => unknown>(),
    exportPage: vi.fn<(...args: unknown[]) => unknown>(),
    clonePage: vi.fn<(...args: unknown[]) => unknown>(),
  },
  aiPageBuilderAiApi: {},
  pagePreviewUrl: (name: string) => `/preview/${name}`,
  getPublicSiteUrl: () => Promise.resolve('http://localhost'),
}))
vi.mock('../../utils/error', () => ({
  errorMessage: (error: Error) => error.message,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn<(location: unknown) => void>() }),
}))
vi.mock('@vueuse/router', async () => {
  const { ref } = await import('vue')
  return { useRouteQuery: (_key: string, defaultValue: unknown) => ref(defaultValue) }
})
vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  const box = (name: string) =>
    defineComponent({
      name,
      setup:
        (_, { slots }) =>
        () =>
          h(
            'div',
            Object.values(slots).flatMap((slot) => slot?.()),
          ),
    })
  return {
    ...Object.fromEntries(
      [
        'VAlert',
        'VDropdown',
        'VDropdownItem',
        'VEmpty',
        'VLoading',
        'VModal',
        'VPageHeader',
        'VPagination',
        'VSpace',
        'VStatusDot',
        'IconAddCircle',
        'IconGrid',
        'IconMore',
      ].map((name) => [name, box(name)]),
    ),
    VButton: defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, disabled: props.disabled }, slots.default?.()),
    }),
    Dialog: { warning: mocks.warning },
    Toast: {
      error: vi.fn<(message: string) => void>(),
      success: vi.fn<(message: string) => void>(),
      info: vi.fn<(message: string) => void>(),
    },
  }
})
const FormKit = defineComponent({
  name: 'FormKit',
  emits: ['update:modelValue', 'submit'],
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.()),
})
const SearchInput = defineComponent({
  setup: () => () => h(FormKit, { type: 'text', name: 'keyword' }),
})
function summary(name: string, extra: Record<string, unknown> = {}) {
  return {
    name,
    slug: `${name}-path`,
    title: `Page ${name}`,
    version: 1,
    themeLayout: true,
    draftRevision: 'r1',
    hasUnpublishedChanges: false,
    ...extra,
  }
}
function detail(name: string, extra: Record<string, unknown> = {}) {
  return {
    page: summary(name, extra),
    document: { mode: 'visual', title: `Page ${name}`, html: '', css: '', schemaVersion: 1 },
  }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => (resolve = res))
  return { promise, resolve }
}
async function openSettingsFor(wrapper: ReturnType<typeof mount>, index: number) {
  const items = wrapper.findAll('div').filter((node) => node.text() === '设置')
  await items[index]!.trigger('click')
}
beforeEach(() => {
  vi.resetAllMocks()
  queryClient.clear()
  mocks.list.mockResolvedValue({
    data: { items: [summary('a'), summary('b')], total: 2 },
  })
  mocks.get.mockImplementation((...args: unknown[]) => {
    const { name } = args[0] as { name: string }
    return Promise.resolve({ data: detail(name) })
  })
})
function mountHome() {
  return mount(HomeView, {
    global: {
      stubs: {
        FormKit,
        SearchInput,
        PagePreviewCard: true,
        PublishPagePopper: defineComponent({
          setup:
            (_, { slots }) =>
            () =>
              h('div', slots.default?.()),
        }),
        ClonePageModal: true,
        CreatePagePanel: true,
        RouterLink: true,
      },
      directives: { 'close-popper': {} },
    },
  })
}
describe('page settings from the list', () => {
  it('discards a stale detail response when another card was opened later', async () => {
    const slow = deferred<unknown>()
    mocks.get.mockImplementation((...args: unknown[]) => {
      const { name } = args[0] as { name: string }
      return name === 'a' ? slow.promise : Promise.resolve({ data: detail('b') })
    })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await openSettingsFor(wrapper, 1)
    await flushPromises()
    expect(wrapper.findComponent(PageSettings).props('slug')).toBe('b-path')
    slow.resolve({ data: detail('a') })
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    expect(settings.props('slug')).toBe('b-path')
    expect(settings.props('document').title).toBe('Page b')
    wrapper.unmount()
  })
  it('refetches the detail when the modal is reopened', async () => {
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(1)
    wrapper.findComponent(PageSettings).vm.$emit('close')
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('does not revive a closed modal when a late mutation resolves', async () => {
    const slow = deferred<unknown>()
    mocks.updateLayout.mockReturnValue(slow.promise)
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    // Start a layout save, then close the modal before it resolves.
    const layoutPromise = settings.props('saveLayout')(false)
    settings.vm.$emit('close')
    await flushPromises()
    expect(wrapper.findComponent(PageSettings).exists()).toBe(false)
    slow.resolve({ data: detail('a', { version: 2, themeLayout: false }) })
    await layoutPromise
    await flushPromises()
    expect(wrapper.findComponent(PageSettings).exists()).toBe(false)
    // The cache still received the result, so the list shows the new state.
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 2 },
    })
    wrapper.unmount()
  })
  it('ignores a late mutation response from a previous session of the same page', async () => {
    let getCalls = 0
    mocks.get.mockImplementation(() => {
      getCalls++
      return Promise.resolve({ data: detail('a', { version: getCalls }) })
    })
    const first = deferred<unknown>()
    let layoutCalls = 0
    mocks.updateLayout.mockImplementation(() => {
      layoutCalls++
      return layoutCalls === 1
        ? first.promise
        : Promise.resolve({ data: detail('a', { version: 3, themeLayout: false }) })
    })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    // Session 1: layout save (v1 → v2) stays in flight while the modal closes.
    const staleSave = wrapper.findComponent(PageSettings).props('saveLayout')(false)
    wrapper.findComponent(PageSettings).vm.$emit('close')
    await flushPromises()
    // Session 2: reopen the same page (now at v2) and save again (v2 → v3).
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    await wrapper.findComponent(PageSettings).props('saveLayout')(false)
    await flushPromises()
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 3 },
    })
    // The session-1 response arrives last; it must not roll anything back.
    first.resolve({ data: detail('a', { version: 2 }) })
    await staleSave
    await flushPromises()
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 3 },
    })
    // The modal still holds v3, so the next save builds on it.
    await wrapper.findComponent(PageSettings).props('saveLayout')(true)
    expect(mocks.updateLayout).toHaveBeenLastCalledWith({
      name: 'a',
      updatePageLayoutRequest: { version: 3, themeLayout: true },
    })
    wrapper.unmount()
  })
  it('refreshes the detail after a version conflict so retry uses the new version', async () => {
    mocks.save
      .mockRejectedValueOnce({ response: { status: 409 }, message: 'Page changed' })
      .mockResolvedValue({ data: detail('a', { version: 3 }) })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(1)
    mocks.get.mockResolvedValue({ data: detail('a', { version: 2 }) })
    const settings = wrapper.findComponent(PageSettings)
    settings
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'title')!
      .vm.$emit('update:modelValue', 'New title')
    await flushPromises()
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    // The 409 triggers a refetch; the modal stays open with its form buffer.
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent(PageSettings).exists()).toBe(true)
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    expect(mocks.save).toHaveBeenLastCalledWith({
      name: 'a',
      savePageRequest: {
        version: 2,
        document: expect.objectContaining({ title: 'New title' }),
        source: 'manual',
      },
    })
    wrapper.unmount()
  })
  it('keeps only edited fields when retrying after a conflict refresh', async () => {
    mocks.save
      .mockRejectedValueOnce({ response: { status: 409 }, message: 'Page changed' })
      .mockResolvedValue({ data: detail('a', { version: 3 }) })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    // Another session changed the slug and description; the refresh brings
    // them in. The user only edited the title.
    mocks.get.mockResolvedValue({
      data: {
        page: summary('a', { version: 2, slug: 'someone-elses-path' }),
        document: {
          mode: 'visual',
          title: 'Page a',
          description: 'other desc',
          html: '',
          css: '',
          schemaVersion: 1,
        },
      },
    })
    const settings = wrapper.findComponent(PageSettings)
    settings
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'title')!
      .vm.$emit('update:modelValue', 'My title')
    await flushPromises()
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    // The unedited slug/description must follow the refresh, not revert it.
    expect(mocks.updateSlug).not.toHaveBeenCalled()
    expect(mocks.save).toHaveBeenLastCalledWith({
      name: 'a',
      savePageRequest: {
        version: 2,
        document: expect.objectContaining({ title: 'My title', description: 'other desc' }),
        source: 'manual',
      },
    })
    wrapper.unmount()
  })
  it('ignores a conflict refresh that resolves after a newer save', async () => {
    mocks.updateSlug.mockRejectedValue({ response: { status: 409 }, message: 'Slug exists' })
    const refresh = deferred<unknown>()
    let getCalls = 0
    mocks.get.mockImplementation(() => {
      getCalls++
      return getCalls === 1
        ? Promise.resolve({ data: detail('a', { version: 1 }) })
        : refresh.promise
    })
    mocks.updateLayout.mockResolvedValue({ data: detail('a', { version: 2, themeLayout: false }) })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    settings
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'slug')!
      .vm.$emit('update:modelValue', 'taken-path')
    await flushPromises()
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    // The conflict refresh (still v1) is in flight; the layout save lands v2.
    expect(getCalls).toBe(2)
    await settings.props('saveLayout')(false)
    await flushPromises()
    refresh.resolve({ data: detail('a', { version: 1 }) })
    await flushPromises()
    // The stale refresh must not roll the modal back: next save builds on v2.
    await wrapper.findComponent(PageSettings).props('saveLayout')(true)
    expect(mocks.updateLayout).toHaveBeenLastCalledWith({
      name: 'a',
      updatePageLayoutRequest: { version: 2, themeLayout: true },
    })
    wrapper.unmount()
  })
  it('ignores a late save response that resolves after a newer refresh', async () => {
    mocks.updateSlug.mockRejectedValue({ response: { status: 409 }, message: 'Slug exists' })
    const refresh = deferred<unknown>()
    const layoutSave = deferred<unknown>()
    let getCalls = 0
    mocks.get.mockImplementation(() => {
      getCalls++
      return getCalls === 1
        ? Promise.resolve({ data: detail('a', { version: 1 }) })
        : refresh.promise
    })
    mocks.updateLayout.mockReturnValue(layoutSave.promise)
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    settings
      .findAllComponents({ name: 'FormKit' })
      .find((field) => field.attributes('name') === 'slug')!
      .vm.$emit('update:modelValue', 'taken-path')
    await flushPromises()
    settings.findComponent({ ref: 'form' }).vm.$emit('submit')
    await flushPromises()
    // Conflict refresh in flight; the layout save response is even slower.
    const save = settings.props('saveLayout')(false)
    refresh.resolve({ data: detail('a', { version: 3, themeLayout: false }) })
    await flushPromises()
    layoutSave.resolve({ data: detail('a', { version: 2, themeLayout: false }) })
    await save
    await flushPromises()
    // The v2 save response must not roll the v3 modal back.
    await wrapper.findComponent(PageSettings).props('saveLayout')(true)
    expect(mocks.updateLayout).toHaveBeenLastCalledWith({
      name: 'a',
      updatePageLayoutRequest: { version: 3, themeLayout: true },
    })
    wrapper.unmount()
  })
  it('re-reads the latest version when unpublish is confirmed', async () => {
    mocks.list.mockResolvedValue({
      data: { items: [summary('a', { publishedRevision: 'r1' })], total: 1 },
    })
    mocks.get.mockResolvedValue({ data: detail('a', { publishedRevision: 'r1' }) })
    mocks.updateLayout.mockResolvedValue({ data: detail('a', { version: 2, themeLayout: false }) })
    mocks.unpublish.mockResolvedValue({
      data: detail('a', { version: 3, publishedRevision: null }),
    })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    settings.vm.$emit('unpublish')
    expect(mocks.warning).toHaveBeenCalledTimes(1)
    // A layout save completes while the confirmation dialog is open, bumping
    // the optimistic-lock version.
    await settings.props('saveLayout')(false)
    await flushPromises()
    const options = mocks.warning.mock.calls[0]![0]
    await options.onConfirm()
    await flushPromises()
    expect(mocks.unpublish).toHaveBeenCalledWith({
      name: 'a',
      mutationRequest: { version: 2 },
    })
    wrapper.unmount()
  })
})
