import { QueryClient } from '@tanstack/vue-query'

// Plugin modules have no app-level install hook, so every useQuery/useMutation
// receives this client explicitly instead of relying on VueQueryPlugin.
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 10_000,
    },
  },
})

// Kept outside pageKeys.all so finishing a run does not invalidate its own poll.
export const activeRunsKey = ['ai-page-builder-active-runs'] as const

export const pageKeys = {
  all: ['ai-page-builder'] as const,
  list: (params: { page: number; size: number; keyword: string }) =>
    [...pageKeys.all, 'list', params] as const,
  detail: (name: string) => [...pageKeys.all, 'detail', name] as const,
  revisions: (name: string) => [...pageKeys.all, 'revisions', name] as const,
  revision: (name: string, revision: string) =>
    [...pageKeys.all, 'revision', name, revision] as const,
  themeContext: ['ai-page-builder-theme-context'] as const,
}
