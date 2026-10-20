import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent, h, inject, provide, type InjectionKey } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import PublishPagePopper from '../PublishPagePopper.vue'
import type { PageDetail } from '../../api/generated'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  save: vi.fn(),
  slug: vi.fn(),
  publish: vi.fn(),
  toastError: vi.fn<(message: string) => void>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    getPage: mocks.get,
    savePage: mocks.save,
    updatePageProperties: mocks.slug,
    publishPage: mocks.publish,
  },
  getPublicSiteUrl: () => Promise.resolve('http://localhost'),
}))
vi.mock('../../utils/error', () => ({ errorMessage: (error: Error) => error.message }))
vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    Toast: { success: vi.fn(), error: mocks.toastError },
    VButton: defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: props.disabled }, slots.default?.()),
    }),
    VDropdown: defineComponent({
      name: 'VDropdown',
      props: ['shown'],
      emits: ['update:shown'],
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.popper?.()),
    }),
  }
})

type Fields = Record<string, string>
const formKey: InjectionKey<{
  values: () => Fields
  disabled: () => boolean
  update: (name: string, value: string) => void
}> = Symbol('form')
// Model only the host FormKit contract; assertions exercise the rendered fields and API calls.
const FormKit = defineComponent({
  props: ['type', 'name', 'label', 'modelValue', 'disabled', 'help'],
  emits: ['update:modelValue', 'node', 'submit'],
  setup(props, { slots, emit }) {
    if (props.type === 'form') {
      provide(formKey, {
        values: () => props.modelValue,
        disabled: () => props.disabled,
        update: (name, value) => emit('update:modelValue', { ...props.modelValue, [name]: value }),
      })
      const submit = () => emit('submit', props.modelValue)
      emit('node', { submit })
      const valid = computed(() => Object.values(props.modelValue as Fields).every(Boolean))
      return () =>
        h(
          'form',
          {
            onSubmit: (event: Event) => {
              event.preventDefault()
              submit()
            },
          },
          slots.default?.({ state: { valid: valid.value } }),
        )
    }
    const form = inject(formKey)!
    return () =>
      h('label', [
        props.label,
        h('input', {
          name: props.name,
          value: form.values()[props.name],
          disabled: form.disabled(),
          onInput: (event: Event) =>
            form.update(props.name, (event.target as HTMLInputElement).value),
        }),
        props.help ? h('p', props.help) : null,
      ])
  },
})

function detail(version = 1, title = '关于我们', slug: string | null = 'about-us'): PageDetail {
  return {
    page: {
      name: 'page',
      title,
      description: '',
      slug,
      version,
      themeLayout: true,
      draftRevision: `r${version}`,
      updatedAt: '2026-10-16T00:00:00Z',
      hasUnpublishedChanges: true,
    },
    document: {
      schemaVersion: 1,
      mode: 'code',
      html: '<p>Keep this content</p>',
      css: '',
      js: '',
    },
  }
}
function popper(slug: string | null = 'about-us') {
  return mount(PublishPagePopper, {
    props: { name: 'page', title: '关于我们', slug, version: 1 },
    global: { components: { FormKit } },
  })
}
async function publish(wrapper: ReturnType<typeof popper>) {
  await wrapper.get('button').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.get.mockResolvedValue({ data: detail() })
  mocks.save.mockResolvedValue({ data: detail(2, '公司介绍') })
  mocks.slug.mockResolvedValue({ data: detail(3, '公司介绍', 'company/about') })
  mocks.publish.mockResolvedValue({ data: detail(4, '公司介绍', 'company/about') })
})

describe('first publish confirmation', () => {
  it('prefills the generated title and path and publishes without unnecessary saves', async () => {
    const wrapper = popper()
    expect(wrapper.get<HTMLInputElement>('[name=title]').element.value).toBe('关于我们')
    expect(wrapper.get<HTMLInputElement>('[name=slug]').element.value).toBe('about-us')
    expect(wrapper.text()).not.toContain('随机')
    await publish(wrapper)
    // The script check reads the page once, but no save is needed.
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.slug).not.toHaveBeenCalled()
    expect(mocks.publish).toHaveBeenCalledWith({ name: 'page', mutationRequest: { version: 1 } })
    expect(wrapper.emitted('published')).toHaveLength(1)
  })

  it('warns before first publishing a page that contains scripts', () => {
    const wrapper = mount(PublishPagePopper, {
      props: { name: 'page', title: '关于我们', slug: 'about-us', version: 1, scripted: true },
      global: { components: { FormKit } },
    })
    expect(wrapper.text()).toContain('此页面包含脚本，发布后会在访客浏览器中自动执行')
  })

  it('detects scripts and the public site URL when the list provides no flag', async () => {
    const withScript = detail()
    withScript.document.js = 'console.log(1)'
    mocks.get.mockResolvedValue({ data: withScript })
    const wrapper = popper()
    await wrapper.findComponent({ name: 'VDropdown' }).vm.$emit('update:shown', true)
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledWith({ name: 'page' })
    expect(wrapper.text()).toContain('此页面包含脚本，发布后会在访客浏览器中自动执行')
    expect(wrapper.text()).toContain('http://localhost/about-us')
  })

  it('waits for script detection before allowing publish', async () => {
    let resolve!: (value: { data: PageDetail }) => void
    mocks.get.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const wrapper = popper()
    await wrapper.findComponent({ name: 'VDropdown' }).vm.$emit('update:shown', true)
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    resolve({ data: detail() })
    await flushPromises()
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  })

  it('does not adopt the version fetched for the script check', async () => {
    mocks.get.mockResolvedValue({ data: detail(9, 'Someone else edited', 'new-slug') })
    const wrapper = popper()
    await wrapper.findComponent({ name: 'VDropdown' }).vm.$emit('update:shown', true)
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[name=title]').element.value).toBe('关于我们')
    await publish(wrapper)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.slug).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.toastError).toHaveBeenCalledWith('页面已在别处更新，请关闭后重新打开发布面板')
  })

  it('rechecks the script warning when the list entry moves to a newer version', async () => {
    const wrapper = popper()
    await wrapper.findComponent({ name: 'VDropdown' }).vm.$emit('update:shown', true)
    await flushPromises()
    expect(wrapper.text()).not.toContain('此页面包含脚本')
    const scripted = detail(2)
    scripted.document.js = 'console.log(1)'
    mocks.get.mockResolvedValue({ data: scripted })
    await wrapper.setProps({ version: 2 })
    await flushPromises()
    expect(wrapper.text()).toContain('此页面包含脚本')
  })

  it('requires acknowledgement when detection only finds scripts at publish time', async () => {
    mocks.get.mockRejectedValueOnce(new Error('offline'))
    const wrapper = popper()
    await wrapper.findComponent({ name: 'VDropdown' }).vm.$emit('update:shown', true)
    await flushPromises()
    expect(wrapper.text()).not.toContain('此页面包含脚本')
    const scripted = detail()
    scripted.document.js = 'console.log(1)'
    mocks.get.mockResolvedValue({ data: scripted })
    await publish(wrapper)
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.toastError).toHaveBeenCalledWith('此页面包含脚本，请确认上方提示后再次点击发布。')
    expect(wrapper.text()).toContain('此页面包含脚本')
    await publish(wrapper)
    expect(mocks.publish).toHaveBeenCalledWith({ name: 'page', mutationRequest: { version: 1 } })
  })

  it('leaves a missing path empty and requires it before publishing', async () => {
    const wrapper = popper(null)
    expect(wrapper.get<HTMLInputElement>('[name=slug]').element.value).toBe('')
    expect(wrapper.find('[title]').exists()).toBe(false)
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.publish).not.toHaveBeenCalled()
    await wrapper.get('[name=slug]').setValue('company/about')
    await publish(wrapper)
    expect(mocks.slug).toHaveBeenCalledWith({
      name: 'page',
      updatePagePropertiesRequest: { version: 1, title: '关于我们', slug: 'company/about' },
    })
  })

  it('saves user edits while preserving page content and chains the returned versions', async () => {
    const wrapper = popper()
    await wrapper.get('[name=title]').setValue(' 公司介绍 ')
    await wrapper.get('[name=slug]').setValue('company/about')
    await publish(wrapper)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.slug).toHaveBeenCalledWith({
      name: 'page',
      updatePagePropertiesRequest: { version: 1, title: '公司介绍', slug: 'company/about' },
    })
    expect(mocks.publish).toHaveBeenCalledWith({ name: 'page', mutationRequest: { version: 3 } })
  })

  it('retries publication with the saved version without resaving either field', async () => {
    mocks.publish.mockRejectedValueOnce(new Error('暂时无法发布'))
    const wrapper = popper()
    await wrapper.get('[name=title]').setValue('公司介绍')
    await wrapper.get('[name=slug]').setValue('company/about')
    await publish(wrapper)
    expect(mocks.toastError).toHaveBeenCalledWith('暂时无法发布')
    mocks.get.mockResolvedValue({ data: detail(3, '公司介绍', 'company/about') })
    await publish(wrapper)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.slug).toHaveBeenCalledTimes(1)
    expect(mocks.publish).toHaveBeenLastCalledWith({
      name: 'page',
      mutationRequest: { version: 3 },
    })
  })

  it('keeps path conflicts editable and retries without partially saving the title', async () => {
    mocks.slug.mockRejectedValueOnce(new Error('访问路径已被占用'))
    const wrapper = popper()
    await wrapper.get('[name=title]').setValue('公司介绍')
    await wrapper.get('[name=slug]').setValue('taken')
    await publish(wrapper)
    expect(mocks.toastError).toHaveBeenCalledWith('访问路径已被占用')
    expect(mocks.publish).not.toHaveBeenCalled()
    await wrapper.get('[name=slug]').setValue('company/about')
    mocks.get.mockResolvedValue({ data: detail() })
    await publish(wrapper)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.slug).toHaveBeenLastCalledWith({
      name: 'page',
      updatePagePropertiesRequest: { version: 1, title: '公司介绍', slug: 'company/about' },
    })
  })

  it('does not publish after a stale-version title save fails', async () => {
    mocks.slug.mockRejectedValue(new Error('Page changed; reload before saving'))
    const wrapper = popper()
    await wrapper.get('[name=title]').setValue('公司介绍')
    await publish(wrapper)
    expect(mocks.slug.mock.calls[0]![0].updatePagePropertiesRequest.version).toBe(1)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.publish).not.toHaveBeenCalled()
    expect(mocks.toastError).toHaveBeenCalledWith('Page changed; reload before saving')
  })

  it('resyncs on reopening and freezes both fields during publication', async () => {
    const wrapper = popper()
    mocks.get.mockResolvedValue({ data: detail(3, '公司介绍', 'company/about') })
    await wrapper.setProps({ title: '公司介绍', slug: 'company/about', version: 3 })
    wrapper.getComponent({ name: 'VDropdown' }).vm.$emit('update:shown', true)
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[name=title]').element.value).toBe('公司介绍')
    expect(wrapper.get<HTMLInputElement>('[name=slug]').element.value).toBe('company/about')
    let resolve!: (value: { data: PageDetail }) => void
    mocks.publish.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await publish(wrapper)
    expect(wrapper.get('[name=title]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[name=slug]').attributes('disabled')).toBeDefined()
    await publish(wrapper)
    expect(mocks.publish).toHaveBeenCalledTimes(1)
    resolve({ data: detail(4) })
    await flushPromises()
    expect(wrapper.get('[name=title]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[name=slug]').attributes('disabled')).toBeUndefined()
  })

  it('blocks an already-open form when the editor becomes dirty or busy', async () => {
    const wrapper = popper()
    await wrapper.setProps({ disabled: true })
    expect(wrapper.get('[name=title]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.publish).not.toHaveBeenCalled()
    await wrapper.setProps({ disabled: false })
    await publish(wrapper)
    expect(mocks.publish).toHaveBeenCalledTimes(1)
  })

  it('does not submit while confirming an IME composition', async () => {
    const wrapper = popper()
    await wrapper.get('[name=title]').trigger('keydown', { key: 'Enter', isComposing: true })
    await flushPromises()
    expect(mocks.publish).not.toHaveBeenCalled()
    await wrapper.get('[name=title]').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(mocks.publish).toHaveBeenCalledTimes(1)
  })
})
