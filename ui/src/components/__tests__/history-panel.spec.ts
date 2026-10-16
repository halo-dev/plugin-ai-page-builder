import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { PageDocument, RevisionSummary } from '../../api/generated'
import HistoryPanel from '../HistoryPanel.vue'

const revisions: RevisionSummary[] = [
  {
    name: 'rev-2',
    createdAt: '2026-10-16T10:15:09Z',
    createdBy: 'admin',
    source: 'ai',
    title: '图库',
  },
  {
    name: 'rev-1',
    createdAt: '2026-10-16T10:10:39Z',
    createdBy: 'admin',
    source: 'manual',
    title: '未命名页面',
  },
]
const revisionDocument: PageDocument = {
  schemaVersion: 2,
  mode: 'code',
  title: '图库',
  description: '',
  html: '<p>old</p>',
  css: '.old{color:red}',
}
const mockListPageRevisions = vi.fn<() => Promise<{ data: RevisionSummary[] }>>(async () => ({
  data: revisions,
}))
const mockGetPageRevision = vi.fn<
  () => Promise<{ data: { summary: RevisionSummary; document: PageDocument } }>
>(async () => ({ data: { summary: revisions[0]!, document: revisionDocument } }))

vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    // Arrow wrappers defer access: the factory runs before the consts are initialized.
    listPageRevisions: () => mockListPageRevisions(),
    getPageRevision: () => mockGetPageRevision(),
  },
  revisionPreviewUrl: (name: string, revision: string) =>
    `/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/${name}/revisions/${revision}/preview`,
}))
vi.mock('@halo-dev/components', () => ({
  Toast: { success: vi.fn<() => void>(), error: vi.fn<() => void>() },
  VModal: { template: '<div><slot /><slot name="footer" /></div>' },
  VLoading: { template: '<div data-test="loading" />' },
  VEmpty: { props: ['title'], template: '<div data-test="empty">{{ title }}</div>' },
  VAlert: {
    props: ['title'],
    template: '<div data-test="alert">{{ title }}<slot name="actions" /></div>',
  },
  VButton: {
    props: ['disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
}))

const current: PageDocument = {
  schemaVersion: 2,
  mode: 'code',
  title: '当前标题',
  description: '',
  html: '<p>new</p>',
  css: '',
}

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(HistoryPanel, {
    props: {
      name: 'about',
      current,
      busy: false,
      draftRevision: 'rev-2',
      publishedRevision: 'rev-1',
      ...props,
    },
  })
}

describe('HistoryPanel', () => {
  beforeEach(() => {
    mockListPageRevisions.mockClear()
    mockGetPageRevision.mockClear()
  })

  it('auto-selects the latest revision and renders its preview iframe', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const iframe = wrapper.find('iframe')
    expect(iframe.exists()).toBe(true)
    expect(iframe.attributes('src')).toBe(
      '/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about/revisions/rev-2/preview',
    )
    expect(wrapper.text()).toContain('图库')
  })

  it('marks draft and published revisions with badges', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const items = wrapper.findAll('nav button')
    expect(items).toHaveLength(2)
    expect(items[0]!.text()).toContain('当前草稿')
    expect(items[1]!.text()).toContain('已发布')
    expect(items[1]!.text()).toContain('手动保存')
  })

  it('shows source comparison on the diff tab', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('pre').exists()).toBe(false)
    const diffTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === '代码差异')!
    await diffTab.trigger('click')
    await flushPromises()
    const blocks = wrapper.findAll('pre')
    expect(blocks.length).toBeGreaterThan(0)
    expect(wrapper.text()).toContain('当前标题')
    expect(wrapper.text()).toContain('图库')
  })

  it('emits restore with the selected revision name', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const restore = wrapper.findAll('button').find((button) => button.text() === '恢复为新草稿')!
    await restore.trigger('click')
    expect(wrapper.emitted('restore')).toEqual([['rev-2']])
    const items = wrapper.findAll('nav button')
    await items[1]!.trigger('click')
    await flushPromises()
    await restore.trigger('click')
    expect(wrapper.emitted('restore')).toEqual([['rev-2'], ['rev-1']])
  })
})
