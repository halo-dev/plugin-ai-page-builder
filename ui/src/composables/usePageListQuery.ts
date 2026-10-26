import { computed, watch } from 'vue'
import { useRouteQuery } from '@vueuse/router'
import { useQuery } from '@tanstack/vue-query'
import { aiPageBuilderApi, aiPageBuilderChatApi } from '../api/clients'
import { activeRunsKey, pageKeys, queryClient } from '../lib/query-client'

export function usePageListQuery() {
  const page = useRouteQuery<number>('page', 1, {
    transform: (value) => Math.max(1, Number(value) || 1),
  })
  const size = useRouteQuery<number>('size', 20, {
    transform: (value) => Math.max(1, Number(value) || 1),
  })
  const keyword = useRouteQuery<string>('keyword', '')
  const status = useRouteQuery<'published' | 'unpublished' | undefined>('status')
  const sort = useRouteQuery<'created' | undefined>('sort')
  watch([status, sort], () => {
    page.value = 1
  })
  const listParams = computed(() => ({
    page: page.value,
    size: size.value,
    keyword: keyword.value,
    status: status.value,
    sort: sort.value,
  }))
  const listQuery = useQuery({
    queryKey: computed(() => pageKeys.list(listParams.value)),
    queryFn: async () => (await aiPageBuilderApi.listPages(listParams.value)).data,
    keepPreviousData: true,
    queryClient,
  })
  const activeRunsQuery = useQuery({
    queryKey: activeRunsKey,
    queryFn: async () => (await aiPageBuilderChatApi.listActivePageChatRuns()).data,
    refetchInterval: 2000,
    queryClient,
  })
  const activeRuns = computed(
    () => new Map((activeRunsQuery.data.value?.items || []).map((run) => [run.pageName, run])),
  )
  // A finished run changes the page's draft and thumbnail; refresh the cards once it leaves.
  // Compare run ids too: another run can take over the same page between two polls.
  // Seed from cached runs: on remount the watcher would otherwise miss the runs already listed.
  let seenActive = new Map<string, string>()
  watch(
    activeRuns,
    (current) => {
      const changed = [...seenActive].some(([name, runId]) => current.get(name)?.runId !== runId)
      seenActive = new Map([...current].map(([name, run]) => [name, run.runId]))
      if (changed) void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    },
    { immediate: true },
  )
  const items = computed(() => listQuery.data.value?.items || [])
  const total = computed(() => listQuery.data.value?.total || 0)
  const currentTheme = computed(() => listQuery.data.value?.currentTheme)
  // The empty workspace shows the create panel instead; keep the toolbar only
  // when there is something to search or filter, or while loading/erroring.
  const showToolbar = computed(
    () =>
      !!items.value.length ||
      !!keyword.value ||
      !!status.value ||
      listQuery.isLoading.value ||
      listQuery.isError.value,
  )
  return {
    page,
    size,
    keyword,
    status,
    sort,
    listQuery,
    items,
    total,
    currentTheme,
    activeRuns,
    showToolbar,
  }
}
