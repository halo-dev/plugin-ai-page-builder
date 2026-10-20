import { Toast } from '@halo-dev/components'
import { aiPageBuilderApi } from '../api/clients'
import type { PageDocument, PageSummary } from '../api/generated'
import { pageKeys, queryClient } from '../lib/query-client'
import { errorMessage } from '../utils/error'
import { usePageWrites } from './usePageWrites'

export type SettingsPatch = Partial<Pick<PageDocument, 'title' | 'description'>> & {
  themeLayout?: boolean
}

export function usePageSettingsEditor(options: {
  confirm: (title: string, description: string) => Promise<boolean>
}) {
  // Settings modal edits the saved page directly: the slug applies immediately,
  // title/description/layout are persisted as a saved draft on 应用.
  const writes = usePageWrites()
  let generation = 0

  function close() {
    generation++
    writes.reset()
  }

  async function open(item: PageSummary) {
    const current = ++generation
    writes.reset()
    try {
      const detail = await queryClient.fetchQuery({
        queryKey: pageKeys.detail(item.name),
        queryFn: async () => (await aiPageBuilderApi.getPage({ name: item.name })).data,
        staleTime: 0,
      })
      if (current === generation) writes.reset(detail)
    } catch (e) {
      if (current === generation) Toast.error(errorMessage(e))
    }
  }

  const isConflict = (e: unknown) =>
    (e as { response?: { status?: number } })?.response?.status === 409

  function refreshAfterConflict(e: unknown) {
    if (isConflict(e)) void writes.refresh().catch(() => {})
  }

  async function saveSlug(slug: string) {
    try {
      return await writes.updateSlug(slug)
    } catch (e) {
      refreshAfterConflict(e)
      throw e
    }
  }

  async function apply(patch: SettingsPatch) {
    const current = writes.detail.value
    if (!current) return
    if (!Object.keys(patch).length) {
      close()
      return
    }
    const { themeLayout, ...documentPatch } = patch
    try {
      const result = await writes.save({
        document: { ...current.document, ...documentPatch },
        ...(themeLayout === undefined ? {} : { themeLayout }),
      })
      if (result) {
        close()
        Toast.success('设置已保存')
      }
    } catch (e) {
      Toast.error(errorMessage(e))
      refreshAfterConflict(e)
    }
  }

  async function unpublish() {
    try {
      const result = await writes.unpublish(
        () => options.confirm('取消发布此页面？', '访客将无法访问此页面。'),
        true,
      )
      if (result) Toast.success('已取消发布')
    } catch (e) {
      Toast.error(errorMessage(e))
      refreshAfterConflict(e)
    }
  }

  return {
    detail: writes.detail,
    saving: writes.pending,
    open,
    close,
    saveSlug,
    apply,
    unpublish,
  }
}
