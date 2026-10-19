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
  save: vi.fn<(...args: unknown[]) => unknown>(),
  unpublish: vi.fn<(...args: unknown[]) => unknown>(),
  warning: vi.fn<(options: { onConfirm: () => void }) => void>(),
  routeQuery: {} as Record<string, unknown>,
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    listPages: mocks.list,
    getPage: mocks.get,
    updatePageSlug: mocks.updateSlug,
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
  return {
    useRouteQuery: (key: string, defaultValue: unknown) =>
      ref(key in mocks.routeQuery ? mocks.routeQuery[key] : defaultValue),
  }
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
function settingsField(settings: ReturnType<typeof mount>, name: string) {
  return settings
    .findAllComponents({ name: 'FormKit' })
    .find((field) => field.attributes('name') === name)!
}
async function submitSettings(settings: ReturnType<typeof mount>) {
  settings.findComponent({ ref: 'form' }).vm.$emit('submit')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  queryClient.clear()
  mocks.routeQuery = {}
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
        FilterDropdown: true,
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
  it('passes status and sort filters from the route to the list request', async () => {
    mocks.routeQuery = { status: 'published', sort: 'created' }
    const wrapper = mountHome()
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      keyword: '',
      status: 'published',
      sort: 'created',
    })
    wrapper.unmount()
  })
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
  it('does not revive a closed modal when a late save resolves', async () => {
    const slow = deferred<unknown>()
    mocks.save.mockReturnValue(slow.promise)
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    settingsField(settings, 'title').vm.$emit('update:modelValue', 'New title')
    await flushPromises()
    await submitSettings(settings)
    // Close the modal before the save resolves.
    settings.vm.$emit('close')
    await flushPromises()
    expect(wrapper.findComponent(PageSettings).exists()).toBe(false)
    slow.resolve({ data: detail('a', { version: 2 }) })
    await flushPromises()
    expect(wrapper.findComponent(PageSettings).exists()).toBe(false)
    // The cache still received the result, so the list shows the new state.
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 2 },
    })
    wrapper.unmount()
  })
  it('ignores a save response from a previous session of the same page', async () => {
    let getCalls = 0
    mocks.get.mockImplementation(() => {
      getCalls++
      return Promise.resolve({ data: detail('a', { version: getCalls }) })
    })
    const first = deferred<unknown>()
    let saveCalls = 0
    mocks.save.mockImplementation(() => {
      saveCalls++
      return saveCalls === 1
        ? first.promise
        : Promise.resolve({ data: detail('a', { version: 3 }) })
    })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    // Session 1: a save (v1 → v2) stays in flight while the modal closes.
    const firstSettings = wrapper.findComponent(PageSettings)
    settingsField(firstSettings, 'title').vm.$emit('update:modelValue', 'First')
    await flushPromises()
    await submitSettings(firstSettings)
    firstSettings.vm.$emit('close')
    await flushPromises()
    // Session 2: reopen the same page, now at v2.
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    // The session-1 response arrives after the new session opened; it must
    // neither revive that session nor overwrite the fresh form values.
    first.resolve({ data: detail('a', { version: 2 }) })
    await flushPromises()
    const secondSettings = wrapper.findComponent(PageSettings)
    expect(secondSettings.props('document').title).toBe('Page a')
    settingsField(secondSettings, 'title').vm.$emit('update:modelValue', 'Second')
    await flushPromises()
    await submitSettings(secondSettings)
    expect(mocks.save).toHaveBeenLastCalledWith({
      name: 'a',
      savePageRequest: {
        version: 2,
        document: expect.objectContaining({ title: 'Second' }),
        source: 'manual',
      },
    })
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 3 },
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
  it('ignores a stale conflict refresh that resolves after a newer save', async () => {
    mocks.updateSlug.mockRejectedValue({ response: { status: 409 }, message: 'Slug exists' })
    const refresh = deferred<unknown>()
    let getCalls = 0
    mocks.get.mockImplementation(() => {
      getCalls++
      return getCalls === 1
        ? Promise.resolve({ data: detail('a', { version: 1 }) })
        : refresh.promise
    })
    mocks.save.mockResolvedValue({ data: detail('a', { version: 2 }) })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    settingsField(settings, 'slug').vm.$emit('update:modelValue', 'taken-path')
    await flushPromises()
    await submitSettings(settings)
    // The conflict refresh (still v1) is in flight; a newer save lands v2.
    expect(getCalls).toBe(2)
    settingsField(settings, 'slug').vm.$emit('update:modelValue', 'a-path')
    settingsField(settings, 'title').vm.$emit('update:modelValue', 'Changed')
    await flushPromises()
    await submitSettings(settings)
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 2 },
    })
    refresh.resolve({ data: detail('a', { version: 1 }) })
    await flushPromises()
    // The stale refresh must not roll the cache back.
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 2 },
    })
    wrapper.unmount()
  })
  it('ignores a late save response that resolves after a newer refresh', async () => {
    mocks.updateSlug.mockRejectedValue({ response: { status: 409 }, message: 'Slug exists' })
    const refresh = deferred<unknown>()
    const slowSave = deferred<unknown>()
    let getCalls = 0
    mocks.get.mockImplementation(() => {
      getCalls++
      return getCalls === 1
        ? Promise.resolve({ data: detail('a', { version: 1 }) })
        : refresh.promise
    })
    mocks.save.mockReturnValue(slowSave.promise)
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    settingsField(settings, 'title').vm.$emit('update:modelValue', 'New title')
    await flushPromises()
    await submitSettings(settings)
    // A slug conflict starts a refresh while the save is still in flight.
    await settings
      .props('saveSlug')('taken-path')
      .catch(() => undefined)
    refresh.resolve({ data: detail('a', { version: 3 }) })
    await flushPromises()
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 3 },
    })
    // The v2 save response arrives last; it must not roll anything back.
    slowSave.resolve({ data: detail('a', { version: 2 }) })
    await flushPromises()
    expect(queryClient.getQueryData(pageKeys.detail('a'))).toMatchObject({
      page: { version: 3 },
    })
    // Reopening builds the next save on the refreshed version.
    mocks.save.mockResolvedValue({ data: detail('a', { version: 4 }) })
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const reopened = wrapper.findComponent(PageSettings)
    settingsField(reopened, 'title').vm.$emit('update:modelValue', 'Again')
    await flushPromises()
    await submitSettings(reopened)
    expect(mocks.save).toHaveBeenLastCalledWith({
      name: 'a',
      savePageRequest: {
        version: 3,
        document: expect.objectContaining({ title: 'Again' }),
        source: 'manual',
      },
    })
    wrapper.unmount()
  })
  it('saves the theme layout with the form instead of on toggle', async () => {
    mocks.save.mockResolvedValue({ data: detail('a', { version: 2, themeLayout: false }) })
    const wrapper = mountHome()
    await flushPromises()
    await openSettingsFor(wrapper, 0)
    await flushPromises()
    const settings = wrapper.findComponent(PageSettings)
    settingsField(settings, 'themeLayout').vm.$emit('update:modelValue', false)
    await flushPromises()
    expect(mocks.save).not.toHaveBeenCalled()
    await submitSettings(settings)
    expect(mocks.save).toHaveBeenCalledWith({
      name: 'a',
      savePageRequest: {
        version: 1,
        document: expect.objectContaining({ title: 'Page a' }),
        source: 'manual',
        themeLayout: false,
      },
    })
    wrapper.unmount()
  })
  it('re-reads the latest version when unpublish is confirmed', async () => {
    mocks.list.mockResolvedValue({
      data: { items: [summary('a', { publishedRevision: 'r1' })], total: 1 },
    })
    mocks.get
      .mockResolvedValueOnce({ data: detail('a', { publishedRevision: 'r1' }) })
      .mockResolvedValue({ data: detail('a', { version: 2, publishedRevision: 'r1' }) })
    mocks.updateSlug.mockRejectedValue({ response: { status: 409 }, message: 'Slug exists' })
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
    // A conflict refresh completes while the confirmation dialog is open,
    // bumping the optimistic-lock version.
    await settings
      .props('saveSlug')('taken-path')
      .catch(() => undefined)
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
