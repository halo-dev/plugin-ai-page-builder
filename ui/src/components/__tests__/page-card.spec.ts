import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import PageCard from '../PageCard.vue'
import type { PageChatActiveRun, PageSummary } from '../../api/generated'

vi.mock('@halo-dev/components', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    VButton: defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, disabled: props.disabled }, slots.default?.()),
    }),
    VDropdown: { template: '<div><slot /><slot name="popper" /></div>' },
    VDropdownDivider: { template: '<div />' },
    VDropdownItem: {
      props: ['disabled'],
      emits: ['click'],
      template: '<div :data-disabled="disabled" @click="$emit(\'click\')"><slot /></div>',
    },
    VStatusDot: defineComponent({
      props: ['state', 'text'],
      setup: (props) => () => h('span', props.text),
    }),
    Toast: {
      error: vi.fn<(message: string) => void>(),
      success: vi.fn<(message: string) => void>(),
      info: vi.fn<(message: string) => void>(),
    },
  }
})

// ui-shared's index pulls in pinia, which the console provides at runtime but tests lack.
vi.mock('@halo-dev/ui-shared', () => ({
  utils: {
    date: {
      timeAgo: (value: string) => `timeAgo:${value}`,
      format: (value: string) => `format:${value}`,
    },
  },
}))

const SlotStub = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.()),
})

function summary(extra: Record<string, unknown> = {}): PageSummary {
  return {
    name: 'page',
    slug: 'page-path',
    title: 'Page',
    version: 1,
    themeLayout: true,
    draftRevision: 'r1',
    hasUnpublishedChanges: false,
    ...extra,
  } as PageSummary
}

function mountCard(
  item: PageSummary,
  disabled = false,
  currentTheme?: PageSummary['publishedTheme'],
  run?: PageChatActiveRun,
) {
  return mount(PageCard, {
    props: { item, currentTheme, disabled, run },
    global: {
      stubs: { RouterLink: true, PagePreviewCard: true, PublishPagePopper: SlotStub },
      directives: { 'close-popper': {}, tooltip: {} },
    },
  })
}

describe('page card', () => {
  it('shows draft state and the admin preview action for an unpublished page', () => {
    const wrapper = mountCard(summary())
    expect(wrapper.text()).toContain('未发布')
    expect(wrapper.text()).toContain('预览')
    expect(wrapper.text()).not.toContain('访问 ↗')
    expect(wrapper.text()).toContain('发布')
    expect(wrapper.text()).not.toContain('更新发布')
  })

  it('shows published state with both admin preview and public actions', () => {
    const wrapper = mountCard(summary({ publishedRevision: 'r2', hasUnpublishedChanges: true }))
    expect(wrapper.text()).toContain('有未发布修改')
    expect(wrapper.text()).toContain('预览 ↗')
    expect(wrapper.text()).toContain('访问 ↗')
    expect(wrapper.text()).toContain('更新发布')
    expect(wrapper.text()).toContain('取消发布')
  })

  it('shows an active run and disables destructive actions while generating', () => {
    const run: PageChatActiveRun = {
      pageName: 'page',
      sessionId: 'page',
      runId: 'run',
      status: 'running',
      startedAt: '2026-10-26T00:00:00Z',
    }
    const wrapper = mountCard(summary({ publishedRevision: 'r2' }), false, undefined, run)
    expect(wrapper.text()).toContain('AI 生成中')
    const item = (label: string) =>
      wrapper.findAll('[data-disabled]').find((element) => element.text() === label)!
    expect(item('设置').attributes('data-disabled')).toBe('true')
    expect(item('更新发布').attributes('data-disabled')).toBe('true')
    expect(item('取消发布').attributes('data-disabled')).toBe('true')
    expect(item('删除页面').attributes('data-disabled')).toBe('true')
    expect(item('克隆页面').attributes('data-disabled')).toBe('false')
  })

  it('shows a waiting run instead of a generating one', () => {
    const wrapper = mountCard(summary(), false, undefined, {
      pageName: 'page',
      sessionId: 'page',
      runId: 'run',
      status: 'waiting_for_user',
      startedAt: '2026-10-26T00:00:00Z',
      pendingQuestion: {
        id: 'question',
        createdAt: '2026-10-26T00:00:00Z',
        questions: [{ id: 'style', title: '想要什么风格？', options: [], allowCustomAnswer: true }],
      },
    })
    expect(wrapper.text()).toContain('等待回答')
    expect(wrapper.text()).not.toContain('AI 生成中')
  })

  it('stops an active run from the action menu', async () => {
    const item = summary()
    const wrapper = mountCard(item, false, undefined, {
      pageName: 'page',
      sessionId: 'page',
      runId: 'run',
      status: 'running',
      startedAt: '2026-10-26T00:00:00Z',
    })
    const stop = wrapper.findAll('div').find((node) => node.text() === '停止生成')!
    expect(stop.attributes('data-disabled')).toBe('false')
    await stop.trigger('click')
    expect(wrapper.emitted('stop')).toEqual([[item]])
    expect(mountCard(summary()).text()).not.toContain('停止生成')
  })

  it('shows the relative update time on the card', () => {
    const wrapper = mountCard(summary({ updatedAt: '2026-10-16T10:15:09Z' }))
    expect(wrapper.text()).toContain('timeAgo:2026-10-16T10:15:09Z')
  })

  it('warns when the published revision was saved under another theme', () => {
    const wrapper = mountCard(
      summary({
        publishedRevision: 'r2',
        publishedTheme: { name: 'theme-earth', displayName: 'Earth' },
      }),
      false,
      { name: 'theme-nebula', displayName: 'Nebula' },
    )
    expect(wrapper.text()).toContain('主题已更换')
  })

  it('does not warn without a published theme, a different theme or theme layout', () => {
    const earth = { name: 'theme-earth', displayName: 'Earth' }
    const nebula = { name: 'theme-nebula', displayName: 'Nebula' }

    expect(mountCard(summary({ publishedRevision: 'r2' }), false, nebula).text()).not.toContain(
      '主题已更换',
    )
    expect(
      mountCard(summary({ publishedRevision: 'r2', publishedTheme: earth }), false, earth).text(),
    ).not.toContain('主题已更换')
    expect(
      mountCard(
        summary({ publishedRevision: 'r2', publishedTheme: earth, themeLayout: false }),
        false,
        nebula,
      ).text(),
    ).not.toContain('主题已更换')
    expect(mountCard(summary({ publishedTheme: earth }), false, nebula).text()).not.toContain(
      '主题已更换',
    )
  })

  it('emits card actions with the page summary', async () => {
    const item = summary({ publishedRevision: 'r2' })
    const wrapper = mountCard(item)
    const button = wrapper.findAll('button').find((node) => node.text() === '预览 ↗')!
    await button.trigger('click')
    const visitButton = wrapper.findAll('button').find((node) => node.text() === '访问 ↗')!
    await visitButton.trigger('click')
    const itemText = (text: string) => wrapper.findAll('div').find((node) => node.text() === text)!
    await itemText('设置').trigger('click')
    await itemText('克隆页面').trigger('click')
    await itemText('导出').trigger('click')
    await itemText('删除页面').trigger('click')
    expect(wrapper.emitted('preview')).toEqual([[item]])
    expect(wrapper.emitted('visit')).toEqual([[item]])
    expect(wrapper.emitted('settings')).toEqual([[item]])
    expect(wrapper.emitted('clone')).toEqual([[item]])
    expect(wrapper.emitted('export')).toEqual([[item]])
    expect(wrapper.emitted('remove')).toEqual([[item]])
  })

  it('disables the action menu while a page mutation is pending', () => {
    const wrapper = mountCard(summary({ publishedRevision: 'r2' }), true)
    const menu = wrapper.get('button[aria-label="Page的操作"]')
    expect(menu.attributes('disabled')).toBeDefined()
  })
})
