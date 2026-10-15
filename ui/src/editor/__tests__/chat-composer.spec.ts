import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import ChatComposer from '../../components/chat/ChatComposer.vue'

vi.mock('@halo-dev/components', () => ({
  VButton: defineComponent({
    props: ['disabled'],
    setup:
      (props, { slots }) =>
      () =>
        h('button', { disabled: props.disabled }, slots.default?.()),
  }),
}))
const button = defineComponent({
  props: ['disabled'],
  setup:
    (props, { slots }) =>
    () =>
      h('button', { disabled: props.disabled }, slots.default?.()),
})
function composer() {
  return mount(ChatComposer, {
    props: {
      prompt: '你好',
      model: 'model',
      disabled: false,
      running: false,
      canSend: true,
      canStop: false,
      available: true,
    },
    global: { stubs: { VButton: button, FormKit: true } },
  })
}

describe('lightweight chat composer', () => {
  it('sends Enter but preserves Chinese composition, Shift Enter and repeated keys', async () => {
    const wrapper = composer()
    const input = wrapper.find('textarea')
    await input.trigger('compositionstart')
    await input.trigger('keydown', { key: 'Enter' })
    await input.trigger('compositionend')
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    await input.trigger('keydown', { key: 'Enter', keyCode: 229 })
    await input.trigger('keydown', { key: 'Enter', shiftKey: true })
    await input.trigger('keydown', { key: 'Enter', repeat: true })
    expect(wrapper.emitted('send')).toBeUndefined()
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toHaveLength(1)
    wrapper.unmount()
  })
  it('allows annotation-only submission by click and Enter, while preserving send guards', async () => {
    const wrapper = composer()
    const input = wrapper.get('textarea')
    await wrapper.setProps({ prompt: '  ' })
    expect(wrapper.get('button[aria-label="发送"]').attributes('disabled')).toBeDefined()
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toBeUndefined()
    await wrapper.setProps({ hasAnnotations: true })
    expect(wrapper.get('button[aria-label="发送"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('button[aria-label="发送"]').trigger('click')
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toHaveLength(2)
    for (const props of [{ canSend: false }, { disabled: true }, { available: false }]) {
      await wrapper.setProps({ canSend: true, disabled: false, available: true, ...props })
      expect(wrapper.get('button[aria-label="发送"]').attributes('disabled')).toBeDefined()
      await input.trigger('keydown', { key: 'Enter' })
      expect(wrapper.emitted('send')).toHaveLength(2)
    }
    wrapper.unmount()
  })
  it('grows with content and exposes stop while running', async () => {
    const wrapper = composer()
    const input = wrapper.find('textarea')
    Object.defineProperty(input.element, 'scrollHeight', { value: 140, configurable: true })
    await input.setValue('Long message')
    expect(input.element.style.height).toBe('140px')
    await wrapper.setProps({ running: true, canStop: true })
    expect(input.attributes('disabled')).toBeDefined()
    const stop = wrapper.find('button')
    expect(stop.attributes('aria-label')).toBe('停止')
    await stop.trigger('click')
    expect(wrapper.emitted('stop')).toHaveLength(1)
    wrapper.unmount()
  })
  it('switches to steering while a run is active and locks again when it cannot steer', async () => {
    const wrapper = composer()
    const input = wrapper.get('textarea')
    await wrapper.setProps({ running: true, canStop: true, canSteer: true })
    expect(input.attributes('disabled')).toBeUndefined()
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('steer')).toHaveLength(1)
    expect(wrapper.emitted('send')).toBeUndefined()
    await wrapper.setProps({ prompt: '  ' })
    expect(wrapper.get('button[aria-label="发送补充要求"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ prompt: '标题再短一点' })
    await wrapper.get('button[aria-label="发送补充要求"]').trigger('click')
    expect(wrapper.emitted('steer')).toHaveLength(2)
    await wrapper.setProps({ canSteer: false })
    expect(input.attributes('disabled')).toBeDefined()
    expect(wrapper.find('button[aria-label="发送补充要求"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('supports a custom placeholder and a footer slot', async () => {
    const wrapper = mount(ChatComposer, {
      props: {
        prompt: '',
        model: 'model',
        disabled: false,
        running: false,
        canSend: false,
        canStop: false,
        available: true,
        placeholder: '描述想要的页面',
      },
      slots: { footer: '<span data-test="chip">主题布局</span>' },
      global: { stubs: { VButton: button, FormKit: true } },
    })
    expect(wrapper.get('textarea').attributes('placeholder')).toBe('描述想要的页面')
    expect(wrapper.get('[data-test="chip"]').text()).toBe('主题布局')
    wrapper.unmount()
  })
})
