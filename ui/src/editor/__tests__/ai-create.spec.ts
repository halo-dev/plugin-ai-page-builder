import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import HomeView from '../../views/HomeView.vue'
import CreatePageView from '../../views/CreatePageView.vue'
import { queryClient } from '../../lib/query-client'
import { slugOptionalValidation, slugValidation } from '../../utils/slug'

const mocks = vi.hoisted(() => ({
  list: vi.fn<(...args: unknown[]) => unknown>(),
  clone: vi.fn<(...args: unknown[]) => unknown>(),
  export: vi.fn<(...args: unknown[]) => unknown>(),
  create: vi.fn<(...args: unknown[]) => unknown>(),
  status: vi.fn<(...args: unknown[]) => unknown>(),
  push: vi.fn<(location: unknown) => void>(),
  submit: vi.fn<() => void>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    listPages: mocks.list,
    createPage: mocks.create,
    clonePage: mocks.clone,
    exportPage: mocks.export,
  },
  aiPageBuilderAiApi: { getAiPageBuilderAiStatus: mocks.status },
  pagePreviewUrl: (name: string) => `/preview/${name}`,
  getPublicSiteUrl: () => Promise.resolve('https://www.example.com'),
}))
vi.mock('../../utils/error', () => ({
  errorMessage: (error: Error) => error.message,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: mocks.push }),
}))
vi.mock('@vueuse/router', async () => {
  const { ref } = await import('vue')
  return { useRouteQuery: (_key: string, defaultValue: unknown) => ref(defaultValue) }
})
// ui-shared's index pulls in pinia, which the console provides at runtime but tests lack.
vi.mock('@halo-dev/ui-shared', () => ({
  utils: {
    date: {
      timeAgo: () => '',
      format: (_date: unknown, format: string) =>
        format === 'YYYYMMDD-HHmmss' ? '20261021-153045' : '',
    },
  },
}))
vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h(
          'div',
          Object.values(slots).flatMap((slot) => slot?.()),
        ),
  })
  const button = defineComponent({
    props: ['disabled'],
    setup:
      (props, { slots }) =>
      () =>
        h('button', { disabled: props.disabled }, slots.default?.()),
  })
  return {
    ...Object.fromEntries(
      [
        'VAlert',
        'VDropdown',
        'VDropdownDivider',
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
      ].map((name) => [name, box]),
    ),
    VButton: button,
    Dialog: {},
    Toast: {
      error: vi.fn<(message: string) => void>(),
      success: vi.fn<(message: string) => void>(),
    },
  }
})
const FormKit = defineComponent({
  props: ['type', 'name', 'modelValue', 'label', 'validation', 'actions'],
  emits: ['update:modelValue', 'submit', 'node'],
  setup(props, { slots, emit }) {
    if (props.type === 'form') emit('node', { submit: mocks.submit })
    return () => h('div', slots.default?.({ state: { valid: true } }))
  },
})
// SearchInput is registered globally by the Halo console; in tests it only
// needs to honor the real component's contract of rendering the keyword FormKit.
const SearchInput = defineComponent({
  setup: () => () => h(FormKit, { type: 'text', name: 'keyword' }),
})

beforeEach(() => {
  vi.resetAllMocks()
  queryClient.clear()
  mocks.list.mockResolvedValue({ data: { items: [], total: 0 } })
  mocks.create.mockResolvedValue({ data: { page: { name: 'created' } } })
  mocks.status.mockResolvedValue({ data: { available: true, message: '' } })
})

describe('page creation', () => {
  it('focuses the chat input on entry without stealing focus after prompt updates', async () => {
    const wrapper = mount(CreatePageView, {
      attachTo: document.body,
      global: { stubs: { FormKit }, directives: { tooltip: {} } },
    })
    try {
      await flushPromises()
      expect(document.activeElement).toBe(wrapper.get('textarea').element)
      const preset = wrapper.findAll('button').find((button) => button.text() === '公司介绍')!
      preset.element.focus()
      await preset.trigger('click')
      expect(wrapper.get('textarea').element.value).toContain('公司介绍')
      expect(document.activeElement).toBe(preset.element)
    } finally {
      wrapper.unmount()
    }
  })
  it('hides the toolbar once an empty workspace loads', async () => {
    let resolveList!: (value: unknown) => void
    mocks.list.mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve
      }),
    )
    const wrapper = mount(HomeView, {
      global: { stubs: { FormKit, SearchInput, RouterLink: true } },
    })
    try {
      await flushPromises()
      expect(wrapper.findComponent(SearchInput).exists()).toBe(true)
      resolveList({ data: { items: [], total: 0 } })
      await flushPromises()
      expect(wrapper.findComponent(SearchInput).exists()).toBe(false)
      expect(wrapper.find('textarea').exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })
  it('opens hierarchical public paths while drafts still preview by name', async () => {
    mocks.list.mockResolvedValue({
      data: {
        items: [
          {
            name: 'published-id',
            slug: 'company/about',
            title: 'Published',
            version: 1,
            publishedRevision: 'r1',
          },
          { name: 'draft-id', slug: 'company/draft', title: 'Draft', version: 1 },
        ],
        total: 2,
      },
    })
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const wrapper = mount(HomeView, {
      global: {
        stubs: { FormKit, SearchInput, PagePreviewCard: true, RouterLink: true },
        directives: { 'close-popper': {} },
      },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('/company/about')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '访问 ↗')!
      .trigger('click')
    await flushPromises()
    expect(open).toHaveBeenLastCalledWith(
      'https://www.example.com/company/about',
      '_blank',
      'noopener',
    )
    const previewButtons = wrapper
      .findAll('button')
      .filter((button) => button.text() === '预览 ↗')
    await previewButtons[previewButtons.length - 1]!.trigger('click')
    expect(open).toHaveBeenLastCalledWith('/preview/draft-id', '_blank', 'noopener')
    open.mockRestore()
    wrapper.unmount()
  })
  it('validates hierarchical paths without accepting empty or unsafe segments', () => {
    const pattern = slugValidation[1]![1] as RegExp
    for (const slug of ['about', 'company/about-us', 'a1/b2/c3', 'a'.repeat(200)]) {
      expect(pattern.test(slug), slug).toBe(true)
    }
    for (const slug of [
      '',
      '/about',
      'about/',
      'a//b',
      'A/b',
      '-a/b',
      'a/b-',
      'a--b',
      'a/../b',
      'a%2fb',
      'a?b',
      'a b',
    ]) {
      expect(pattern.test(slug), slug).toBe(false)
    }
    expect(slugValidation[2]).toEqual(['length', 1, 200])
  })
  it('clones the saved draft using its CAS version and opens the new page', async () => {
    mocks.list.mockResolvedValue({
      data: {
        items: [{ name: 'original', slug: 'original', title: 'Original', version: 7 }],
        total: 1,
      },
    })
    mocks.clone.mockResolvedValue({ data: { page: { name: 'copy' } } })
    const wrapper = mount(HomeView, {
      global: {
        stubs: { FormKit, SearchInput, PagePreviewCard: true, RouterLink: true },
        directives: { 'close-popper': {} },
      },
    })
    await flushPromises()
    const cloneItem = wrapper.findAll('div').find((item) => item.text() === '克隆页面')!
    await cloneItem.trigger('click')
    await flushPromises()
    const form = wrapper
      .findAllComponents(FormKit)
      .find((item) => item.props('modelValue')?.slug === '')!
    expect(
      form
        .findAllComponents(FormKit)
        .find((field) => field.props('name') === 'slug')!
        .props('validation'),
    ).toEqual(slugOptionalValidation)
    form.vm.$emit('update:modelValue', { title: 'Copy', slug: 'company/about-copy' })
    await flushPromises()
    form.vm.$emit('submit')
    await flushPromises()
    expect(mocks.clone).toHaveBeenCalledWith({
      name: 'original',
      clonePageRequest: { version: 7, title: 'Copy', slug: 'company/about-copy' },
    })
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'AiPageBuilderEditor',
      params: { name: 'copy' },
    })
    wrapper.unmount()
  })
  it('downloads a ZIP from the saved draft export action', async () => {
    mocks.list.mockResolvedValue({
      data: {
        items: [{ name: 'original', slug: 'original', title: 'Original', version: 7 }],
        total: 1,
      },
    })
    mocks.export.mockResolvedValue({ data: new Blob(['zip']) })
    const objectUrl = vi.fn<() => string>(() => 'blob:test')
    vi.stubGlobal(
      'URL',
      Object.assign(URL, {
        createObjectURL: objectUrl,
        revokeObjectURL: vi.fn<(url: string) => void>(),
      }),
    )
    const downloads: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push(this.download)
    })
    const wrapper = mount(HomeView, {
      global: {
        stubs: { FormKit, SearchInput, PagePreviewCard: true, RouterLink: true },
        directives: { 'close-popper': {} },
      },
    })
    await flushPromises()
    await wrapper
      .findAll('div')
      .find((item) => item.text() === '导出')!
      .trigger('click')
    await flushPromises()
    expect(mocks.export).toHaveBeenCalledWith({ name: 'original' }, { responseType: 'blob' })
    expect(objectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(click).toHaveBeenCalledOnce()
    expect(downloads).toEqual(['original-20261021-153045.zip'])
    click.mockRestore()
    vi.unstubAllGlobals()
    wrapper.unmount()
  })
  it('navigates to the dedicated create route from the list header', async () => {
    mocks.list.mockResolvedValue({
      data: {
        items: [{ name: 'original', slug: 'original', title: 'Original', version: 7 }],
        total: 1,
      },
    })
    const wrapper = mount(HomeView, {
      global: {
        stubs: { FormKit, SearchInput, PagePreviewCard: true, RouterLink: true },
        directives: { 'close-popper': {} },
      },
    })
    await flushPromises()
    expect(wrapper.findAll('button').filter((button) => button.text() === '新建页面')).toHaveLength(
      1,
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '新建页面')!
      .trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({ name: 'AiPageBuilderNew' })
    wrapper.unmount()
  })
  it('shows the chat panel on the empty list and creates with a placeholder title', async () => {
    const wrapper = mount(HomeView, { global: { stubs: { FormKit, SearchInput } } })
    await flushPromises()
    expect(wrapper.text()).toContain('想做一个什么页面？')
    // A preset chip fills the composer prompt.
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '公司介绍')!
      .trigger('click')
    const prompt = wrapper.get('textarea').element.value
    expect(prompt).toContain('公司介绍')
    // The theme layout toggle defaults to on.
    const layoutChip = wrapper.findAll('button').find((button) => button.text() === '主题布局')!
    expect(layoutChip.attributes('aria-pressed')).toBe('true')
    await wrapper.get('button[aria-label="发送"]').trigger('click')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith({
      createPageRequest: { title: '未命名页面', themeLayout: true },
    })
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'AiPageBuilderEditor',
      params: { name: 'created' },
      state: { aiPrompt: prompt, aiModel: '' },
    })
    wrapper.unmount()
  })
  it('keeps a manual title-only creation entry and submits through the FormKit node', async () => {
    const wrapper = mount(HomeView, { global: { stubs: { FormKit, SearchInput } } })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '手动创建页面')!
      .trigger('click')
    const fields = wrapper.findAllComponents(FormKit)
    expect(
      fields.filter((field) => field.props('name')).map((field) => field.props('name')),
    ).toEqual(['title', 'themeLayout'])
    expect(fields.find((field) => field.props('name') === 'title')!.props('validation')).toContain(
      'required',
    )
    expect(fields.some((field) => field.props('type') === 'submit')).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '创建页面')!
      .trigger('click')
    expect(mocks.submit).toHaveBeenCalledOnce()
    expect(mocks.create).not.toHaveBeenCalled()
    const form = fields.find((field) => field.props('modelValue')?.title === '')!
    form.vm.$emit('update:modelValue', { title: '关于我们', themeLayout: false })
    await flushPromises()
    form.vm.$emit('submit')
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith({
      createPageRequest: { title: '关于我们', themeLayout: false },
    })
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'AiPageBuilderEditor',
      params: { name: 'created' },
    })
    wrapper.unmount()
  })
})
