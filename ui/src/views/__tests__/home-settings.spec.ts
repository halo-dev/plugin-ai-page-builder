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
