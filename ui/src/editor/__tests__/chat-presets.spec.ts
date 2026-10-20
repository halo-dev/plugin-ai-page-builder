import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, shallowRef } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import AiPanel from '../../components/AiPanel.vue'
import type { PageDetail } from '../../api/generated'

const chatLoading = vi.hoisted(() => ({ current: undefined as { value: boolean } | undefined }))

vi.mock('../../composables/usePageChat', () => {
  const loading = shallowRef(false)
  chatLoading.current = loading
  return {
    usePageChat: () => ({
      sessions: shallowRef([]),
      sessionId: shallowRef('one'),
      sessionBusy: shallowRef(false),
      hasMore: shallowRef(false),
      loadingEarlier: shallowRef(false),
      messages: shallowRef([]),
      preview: shallowRef(undefined),
      loading,
      available: shallowRef(true),
      modelName: shallowRef('model'),
      error: shallowRef(''),
      notice: shallowRef(''),
      running: shallowRef(false),
      activeRun: shallowRef(undefined),
      waitingForUser: shallowRef(false),
      pendingQuestion: shallowRef(undefined),
      questions: shallowRef([]),
      answering: shallowRef(false),
      answerError: shallowRef(''),
      canSend: shallowRef(true),
      lastPrompt: shallowRef(''),
      recover: async () => {},
    }),
  }
})
vi.mock('@halo-dev/components', () => ({
  Dialog: {},
  VDropdown: { template: '<div><slot /></div>' },
  VDropdownItem: { template: '<button><slot /></button>' },
  VLoading: { template: '<span />' },
}))

async function panel(document: PageDetail['document']) {
  const wrapper = mount(AiPanel, {
    props: {
      pageName: 'one',
      disabled: false,
      prepare: async () => 1,
      document,
      annotations: [],
    },
    global: { stubs: { FormKit: true }, directives: { 'close-popper': {} } },
  })
  await flushPromises()
  return wrapper
}
function preset(wrapper: Awaited<ReturnType<typeof panel>>, label: string) {
  return wrapper.findAll('button').find((button) => button.text() === label)!
}

describe('new session presets', () => {
  it('reuses page-type presets when the page has no content', async () => {
    const wrapper = await panel({
      schemaVersion: 2,
      mode: 'code',
      title: '未命名页面',
      html: '',
      css: '',
    })
    expect(wrapper.text()).toContain('从一个想法开始')
    expect(wrapper.text()).toContain('企业与品牌')
    expect(wrapper.text()).toContain('商务与服务')
    expect(wrapper.text()).toContain('公司介绍')
    expect(wrapper.text()).not.toContain('专题导读')
    expect(wrapper.text()).not.toContain('想调整哪里？')
    expect(wrapper.text()).not.toContain('适配主题风格')

    await preset(wrapper, '个人、团队与内容').trigger('click')
    expect(wrapper.text()).toContain('专题导读')
    await preset(wrapper, '专题导读').trigger('click')
    expect(wrapper.get('textarea').element.value).toContain('文章专题导读页')
    wrapper.unmount()
  })

  it('shows adjustment presets when the page already has content', async () => {
    const wrapper = await panel({
      schemaVersion: 2,
      mode: 'code',
      title: '已有内容',
      html: '<h1>已经存在的页面</h1>',
      css: '',
    })
    expect(wrapper.text()).toContain('想调整哪里？')
    expect(wrapper.text()).toContain('常见调整')
    expect(wrapper.text()).toContain('适配主题风格')
    expect(wrapper.text()).toContain('检查内容准确性')
    expect(wrapper.text()).not.toContain('从一个想法开始')
    expect(wrapper.text()).not.toContain('公司介绍')

    await preset(wrapper, '适配主题风格').trigger('click')
    expect(wrapper.get('textarea').element.value).toContain('当前主题的视觉规范')
    wrapper.unmount()
  })

  it('keeps the selected preset group while the panel is loading', async () => {
    const wrapper = await panel({
      schemaVersion: 2,
      mode: 'code',
      title: '未命名页面',
      html: '',
      css: '',
    })
    await preset(wrapper, '个人、团队与内容').trigger('click')
    expect(wrapper.text()).toContain('专题导读')

    chatLoading.current!.value = true
    await nextTick()
    expect(wrapper.text()).not.toContain('专题导读')

    chatLoading.current!.value = false
    await nextTick()
    expect(wrapper.text()).toContain('专题导读')
    wrapper.unmount()
  })
})
