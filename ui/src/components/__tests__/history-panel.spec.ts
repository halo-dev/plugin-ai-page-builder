import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { PageDocument, RevisionSummary } from '../../api/generated'
import { queryClient } from '../../lib/query-client'
import HistoryPanel from '../HistoryPanel.vue'

const revisions: RevisionSummary[] = [
  {
    name: 'rev-2',
    createdAt: '2026-10-16T10:15:09Z',
    createdBy: 'admin',
    source: 'ai',
    parentRevision: 'rev-1',
  },
  {
    name: 'rev-1',
    createdAt: '2026-10-16T10:10:39Z',
    createdBy: 'admin',
    source: 'manual',
    parentRevision: 'rev-0',
  },
  {
    name: 'rev-0',
    createdAt: '2026-10-16T10:05:00Z',
    createdBy: 'admin',
    source: 'restore',
  },
]
const documents: Record<string, PageDocument> = {
  'rev-2': {
    schemaVersion: 2,
    mode: 'code',
    html: '<p>newer</p>',
    css: '.new{color:red}',
  },
  'rev-1': {
    schemaVersion: 2,
    mode: 'code',
    html: '<p>older</p>',
    css: '',
  },
  'rev-0': {
    schemaVersion: 2,
    mode: 'code',
    html: '<p>oldest</p>',
    css: '',
  },
}
const mockListPageRevisions = vi.fn<() => Promise<{ data: RevisionSummary[] }>>(async () => ({
  data: revisions,
}))
const mockGetPageRevision = vi.fn<
  (args: { name: string; revision: string }) => Promise<{
    data: { summary: RevisionSummary; document: PageDocument }
  }>
>(async (args) => ({
  data: {
    summary: revisions.find((revision) => revision.name === args.revision)!,
    document: documents[args.revision]!,
  },
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    // Arrow wrappers defer access: the factory runs before the consts are initialized.
    listPageRevisions: () => mockListPageRevisions(),
    getPageRevision: (args: { name: string; revision: string }) => mockGetPageRevision(args),
  },
  revisionPreviewUrl: (name: string, revision: string) =>
    `/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/${name}/revisions/${revision}/preview`,
}))
// ui-shared's index pulls in pinia, which the console provides at runtime but tests lack.
vi.mock('@halo-dev/ui-shared', () => ({
  utils: { date: { timeAgo: (value: string) => `timeAgo:${value}` } },
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
// CodeMirror cannot render in jsdom; the diff editor is covered by its own rendering in app.
vi.mock('../SourceComparison.vue', () => ({
  default: {
    props: ['before', 'after'],
    template: '<div data-test="comparison">{{ before.html }}->{{ after.html }}</div>',
  },
}))

const current: PageDocument = {
  schemaVersion: 2,
  mode: 'code',
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

async function openDiffTab(wrapper: ReturnType<typeof mountPanel>) {
  const diffTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === '代码差异')!
  await diffTab.trigger('click')
  await flushPromises()
}

describe('HistoryPanel', () => {
  beforeEach(() => {
    // The preview preflight only needs a successful response; jsdom has no real server.
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, type: 'basic', status: 200 })),
    )
    // The module-level query client caches across tests; reset it so each test refetches.
    queryClient.clear()
    mockListPageRevisions.mockClear()
    mockGetPageRevision.mockClear()
  })

  it('auto-selects the latest revision and runs it in the sandboxed preview iframe', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const iframe = wrapper.find('iframe')
    expect(iframe.exists()).toBe(true)
    // Same isolation as the "运行" tab: the preview endpoint sets its own sandbox CSP,
    // and scripts never receive the console's origin.
    expect(iframe.attributes('src')).toBe(
      '/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about/revisions/rev-2/preview',
    )
    expect(iframe.attributes('srcdoc')).toBeUndefined()
    expect(iframe.attributes('sandbox')).toBe('allow-scripts')
    expect(wrapper.text()).toContain('历史只记录页面内容')
    expect(wrapper.text()).toContain('timeAgo:2026-10-16T10:15:09Z')
  })

  it('switches the preview iframe when another revision is selected', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.findAll('nav button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('iframe').attributes('src')).toBe(
      '/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about/revisions/rev-1/preview',
    )
  })

  it('shows a retry when the preview request fails, then reloads it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: false, type: 'basic', status: 403 })),
    )
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('预览加载失败')
    expect(wrapper.find('iframe').exists()).toBe(false)

    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, type: 'basic', status: 200 })),
    )
    const retry = wrapper.findAll('button').find((button) => button.text() === '重新加载')!
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.find('iframe').exists()).toBe(true)
  })

  it('marks draft, published and restored revisions with badges', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const items = wrapper.findAll('nav button')
    expect(items).toHaveLength(3)
    expect(items[0]!.text()).toContain('当前草稿')
    expect(items[0]!.text()).toContain('AI 生成')
    expect(items[1]!.text()).toContain('已发布')
    expect(items[1]!.text()).toContain('手动保存')
    expect(items[2]!.text()).toContain('恢复')
  })

  it('compares the selected revision with its previous version on the diff tab', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('[data-test="comparison"]').exists()).toBe(false)
    await openDiffTab(wrapper)
    const comparison = wrapper.find('[data-test="comparison"]')
    expect(comparison.exists()).toBe(true)
    // rev-2 is compared against rev-1, not against the current draft.
    expect(comparison.text()).toBe('<p>older</p>-><p>newer</p>')
    expect(wrapper.text()).toContain('上一版本 → 选中版本')
    expect(wrapper.text()).not.toContain('当前标题')
  })

  it('treats the earliest revision as having no previous content', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    const items = wrapper.findAll('nav button')
    await items[2]!.trigger('click')
    await flushPromises()
    await openDiffTab(wrapper)
    expect(wrapper.text()).toContain('首个版本，修改前无内容')
    expect(wrapper.find('[data-test="comparison"]').text()).toBe('-><p>oldest</p>')
  })

  it('shows an error with retry in the diff tab when the revision detail fails', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    // The query client retries once; fail both attempts for the next revision only.
    mockGetPageRevision.mockRejectedValueOnce(new Error('boom'))
    mockGetPageRevision.mockRejectedValueOnce(new Error('boom'))
    const items = wrapper.findAll('nav button')
    await items[2]!.trigger('click')
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 1500))
    await flushPromises()
    await openDiffTab(wrapper)
    expect(wrapper.text()).toContain('版本内容加载失败')
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
