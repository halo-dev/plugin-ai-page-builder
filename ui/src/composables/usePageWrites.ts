import { computed, onScopeDispose, shallowRef } from 'vue'
import { aiPageBuilderApi } from '../api/clients'
import type {
  PageDetail,
  PageDocument,
  PageSummary,
  UpdatePagePropertiesRequest,
} from '../api/generated'
import { pageKeys, queryClient } from '../lib/query-client'

export type PageWriteTarget = Pick<PageSummary, 'name' | 'title' | 'slug' | 'version'>
type Draft = { document: PageDocument }
export type PagePropertiesPatch = Omit<UpdatePagePropertiesRequest, 'version'>
type Confirm = (page: PageWriteTarget) => Promise<boolean>
type Accepted = (detail: PageDetail, content: boolean) => void

/** One editing session. Successful writes survive failures, but never revive an old session. */
export function usePageWrites(onAccepted?: Accepted) {
  const target = shallowRef<PageWriteTarget>()
  const detail = shallowRef<PageDetail>()
  const pending = shallowRef(false)
  let generation = 0

  function reset(page?: PageWriteTarget | PageDetail) {
    generation++
    pending.value = false
    detail.value = page && 'page' in page ? page : undefined
    target.value = page && 'page' in page ? page.page : page
  }

  function sync(page: PageWriteTarget) {
    if (!target.value || target.value.name !== page.name) reset(page)
    else if (page.version > target.value.version) target.value = page
  }

  function cache(result: PageDetail, refreshList: boolean) {
    const key = pageKeys.detail(result.page.name)
    const previous = queryClient.getQueryData<PageDetail>(key)
    if (!previous || previous.page.version <= result.page.version) {
      queryClient.setQueryData(key, result)
      if (refreshList) void queryClient.invalidateQueries({ queryKey: [...pageKeys.all, 'list'] })
      void queryClient.invalidateQueries({ queryKey: pageKeys.revisions(result.page.name) })
    }
  }

  function accept(result: PageDetail, session: number, content: boolean, refreshList = true) {
    cache(result, refreshList)
    if (
      session !== generation ||
      result.page.name !== target.value?.name ||
      result.page.version < target.value.version
    )
      return
    target.value = result.page
    detail.value = result
    onAccepted?.(result, content)
  }

  function receive(result: PageDetail, content = true) {
    accept(result, generation, content)
  }

  async function refresh() {
    const page = target.value
    const session = generation
    if (!page) return
    const result = (await aiPageBuilderApi.getPage({ name: page.name })).data
    accept(result, session, false)
    return session === generation ? detail.value : undefined
  }

  async function run(
    action: (
      write: (request: Promise<{ data: PageDetail }>, content?: boolean) => Promise<PageDetail>,
      current: () => PageWriteTarget,
    ) => Promise<PageDetail | undefined>,
    confirm?: Confirm,
    latestAfterConfirm = false,
  ) {
    const base = target.value
    const session = generation
    if (!base || pending.value) return undefined
    if (confirm && !(await confirm(base))) return undefined
    if (session !== generation || target.value?.name !== base.name) return undefined
    if (!latestAfterConfirm && target.value.version !== base.version) {
      throw new Error('页面已更新，已取消本次操作，请重试。')
    }
    if (pending.value) return undefined
    pending.value = true
    let version = target.value.version
    let wrote = false
    const current = () => {
      if (session !== generation || target.value?.name !== base.name) {
        throw new Error('页面已切换，已停止后续操作。')
      }
      if (target.value.version !== version) throw new Error('页面已更新，已停止后续操作。')
      return target.value
    }
    const write = async (request: Promise<{ data: PageDetail }>, content = false) => {
      const result = (await request).data
      wrote = true
      // A list refresh can remove this operation's card and dispose its session.
      accept(result, session, content, false)
      version = result.page.version
      if (session !== generation) throw new Error('页面已切换，已停止后续操作。')
      return result
    }
    try {
      const result = await action(write, current)
      return session === generation ? result : undefined
    } catch (error) {
      if (session !== generation) return undefined
      throw error
    } finally {
      if (session === generation) pending.value = false
      if (wrote) void queryClient.invalidateQueries({ queryKey: [...pageKeys.all, 'list'] })
    }
  }

  function save(draft: Draft) {
    return run((write, current) => {
      const page = current()
      return write(
        aiPageBuilderApi.savePage({
          name: page.name,
          savePageRequest: { version: page.version, source: 'manual', ...draft },
        }),
        true,
      )
    })
  }

  function updateProperties(properties: PagePropertiesPatch) {
    return run((write, current) => {
      const page = current()
      return write(
        aiPageBuilderApi.updatePageProperties({
          name: page.name,
          updatePagePropertiesRequest: { version: page.version, ...properties },
        }),
      )
    })
  }

  function restore(revision: string, draft: () => Draft | undefined, confirm: Confirm) {
    return run(async (write, current) => {
      const unsaved = draft()
      if (unsaved) {
        const page = current()
        await write(
          aiPageBuilderApi.savePage({
            name: page.name,
            savePageRequest: { version: page.version, source: 'manual', ...unsaved },
          }),
          true,
        )
      }
      const page = current()
      return write(
        aiPageBuilderApi.restorePageRevision({
          name: page.name,
          restoreRequest: { version: page.version, revision },
        }),
        true,
      )
    }, confirm)
  }

  /** First publication retains every successful metadata save for a later retry. */
  function publish(metadata: { title: string; slug: string }, verify: Confirm) {
    return run(async (write, current) => {
      const base = current()
      if (!(await verify(base))) return undefined
      if (current().version !== base.version) throw new Error('页面已更新，请重新确认发布。')
      if (metadata.title !== base.title || metadata.slug !== base.slug) {
        const page = current()
        await write(
          aiPageBuilderApi.updatePageProperties({
            name: page.name,
            updatePagePropertiesRequest: { version: page.version, ...metadata },
          }),
        )
      }
      const page = current()
      return write(
        aiPageBuilderApi.publishPage({
          name: page.name,
          mutationRequest: { version: page.version },
        }),
      )
    })
  }

  function publishUpdate(confirm?: Confirm) {
    return run((write, current) => {
      const page = current()
      return write(
        aiPageBuilderApi.publishPage({
          name: page.name,
          mutationRequest: { version: page.version },
        }),
      )
    }, confirm)
  }

  // Settings allow changes while their confirmation is open; the editor confirms a fixed version.
  function unpublish(confirm?: Confirm, latestAfterConfirm = false) {
    return run(
      (write, current) => {
        const page = current()
        return write(
          aiPageBuilderApi.unpublishPage({
            name: page.name,
            mutationRequest: { version: page.version },
          }),
        )
      },
      confirm,
      latestAfterConfirm,
    )
  }

  onScopeDispose(() => reset())
  return {
    target: computed(() => target.value),
    detail: computed(() => detail.value),
    pending: computed(() => pending.value),
    reset,
    sync,
    receive,
    refresh,
    save,
    updateProperties,
    restore,
    publish,
    publishUpdate,
    unpublish,
  }
}
export type PageWrites = ReturnType<typeof usePageWrites>
