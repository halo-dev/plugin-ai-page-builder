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
    title: '图库',
    parentRevision: 'rev-1',
  },
  {
    name: 'rev-1',
    createdAt: '2026-10-16T10:10:39Z',
    createdBy: 'admin',
    source: 'manual',
    title: '未命名页面',
    parentRevision: 'rev-0',
  },
  {
    name: 'rev-0',
    createdAt: '2026-10-16T10:05:00Z',
    createdBy: 'admin',
    source: 'restore',
    title: '恢复的版本',
  },
]
const documents: Record<string, PageDocument> = {
  'rev-2': {
    schemaVersion: 2,
    mode: 'code',
    title: '图库',
    description: '',
    html: '<p>newer</p>',
    css: '.new{color:red}',
  },
  'rev-1': {
    schemaVersion: 2,
    mode: 'code',
    title: '未命名页面',
    description: '',
    html: '<p>older</p>',
    css: '',
  },
  'rev-0': {
    schemaVersion: 2,
    mode: 'code',
    title: '恢复的版本',
    description: '',
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
const mockPreviewGet = vi.fn<(url: string, config?: unknown) => Promise<{ data: string }>>(
  async () => ({ data: '<html><head></head><body>preview</body></html>' }),
)

vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: {
    // Arrow wrappers defer access: the factory runs before the consts are initialized.
    listPageRevisions: () => mockListPageRevisions(),
    getPageRevision: (args: { name: string; revision: string }) => mockGetPageRevision(args),
  },
  revisionPreviewUrl: (name: string, revision: string) =>
    `/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/${name}/revisions/${revision}/preview`,
}))
vi.mock('@halo-dev/api-client', () => ({
  axiosInstance: { get: (url: string, config?: unknown) => mockPreviewGet(url, config) },
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

async function openDiffTab(wrapper: ReturnType<typeof mountPanel>) {
  const diffTab = wrapper.findAll('button[role="tab"]').find((tab) => tab.text() === '代码差异')!
  await diffTab.trigger('click')
  await flushPromises()
}

describe('HistoryPanel', () => {
  beforeEach(() => {
    // The module-level query client caches across tests; reset it so each test refetches.
    queryClient.clear()
    mockListPageRevisions.mockClear()
    mockGetPageRevision.mockClear()
    mockPreviewGet.mockClear()
  })

  it('auto-selects the latest revision and renders its sandboxed preview iframe', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    expect(mockPreviewGet).toHaveBeenCalledWith(
      '/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about/revisions/rev-2/preview',
      expect.objectContaining({ responseType: 'text' }),
    )
    const iframe = wrapper.find('iframe')
    expect(iframe.exists()).toBe(true)
    // The base tag pins URL resolution to the preview's own URL, keeping theme assets
    // working and in-page anchors inside the preview instead of the console.
    expect(iframe.attributes('srcdoc')).toBe(
      `<html><head><base href="${location.origin}/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/about/revisions/rev-2/preview"></head><body>preview</body></html>`,
    )
    // Same-origin inline previews must never reach the console via window.parent:
    // same-origin for theme assets, but scripts stay disabled.
    expect(iframe.attributes('sandbox')).toBe('allow-same-origin')
    expect(wrapper.text()).toContain('图库')
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
