import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { activeRunsKey, pageKeys, queryClient } from '../../lib/query-client'
import { markRunStarted } from '../../lib/pending-runs'
import { usePageListQuery } from '../usePageListQuery'

const api = vi.hoisted(() => ({
  listPages: vi.fn<(...args: unknown[]) => unknown>(),
  activeRuns: vi.fn<(...args: unknown[]) => unknown>(),
}))
vi.mock('../../api/clients', () => ({
  aiPageBuilderApi: { listPages: api.listPages },
  aiPageBuilderChatApi: { listActivePageChatRuns: api.activeRuns },
}))
vi.mock('@vueuse/router', () => ({
  useRouteQuery: (_key: string, defaultValue: unknown) => ref(defaultValue),
}))

const Harness = defineComponent({
  setup() {
    const state = usePageListQuery()
    return () => h('div', String(state.activeRuns.value.size))
  },
})

const run = {
  pageName: 'page',
  sessionId: 'page',
  runId: 'run',
  status: 'running',
  startedAt: '2026-10-26T00:00:00Z',
}

beforeEach(() => {
  vi.resetAllMocks()
  queryClient.clear()
  api.listPages.mockResolvedValue({
    data: { items: [{ name: 'page', title: 'Page', version: 1 }], total: 1 },
  })
})

describe('page list runs', () => {
  it('refreshes the list when a cached run finishes after remounting', async () => {
    api.activeRuns.mockResolvedValue({ data: { items: [run] } })
    const first = mount(Harness)
    await flushPromises()
    expect(first.text()).toBe('1')
    first.unmount()

    // The cached run is already listed, so no watcher fires until it disappears.
    api.activeRuns.mockResolvedValue({ data: { items: [] } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const second = mount(Harness)
    await flushPromises()
    await queryClient.refetchQueries({ queryKey: activeRunsKey })
    await flushPromises()
    expect(second.text()).toBe('0')
    expect(invalidate).toHaveBeenCalledWith({ queryKey: pageKeys.all })
    second.unmount()
    invalidate.mockRestore()
  })

  it('refreshes the list when a locally started run finishes before the first poll', async () => {
    // The list mounts after the run already completed, so it never sees the run active.
    api.activeRuns.mockResolvedValue({ data: { items: [] } })
    markRunStarted('page')
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = mount(Harness)
    await flushPromises()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: pageKeys.all })
    wrapper.unmount()
    invalidate.mockRestore()
  })

  it('refreshes the list when another run takes over the same page', async () => {
    api.activeRuns.mockResolvedValue({ data: { items: [run] } })
    const first = mount(Harness)
    await flushPromises()
    expect(first.text()).toBe('1')
    first.unmount()

    api.activeRuns.mockResolvedValue({ data: { items: [{ ...run, runId: 'next' }] } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const second = mount(Harness)
    await flushPromises()
    await queryClient.refetchQueries({ queryKey: activeRunsKey })
    await flushPromises()
    expect(second.text()).toBe('1')
    expect(invalidate).toHaveBeenCalledWith({ queryKey: pageKeys.all })
    second.unmount()
    invalidate.mockRestore()
  })
})
