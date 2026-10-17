import { computed, onScopeDispose, shallowRef, watch, type Ref, type DeepReadonly } from 'vue'
import type { ToolPart, UIMessage } from '@halo-dev/ai-foundation-sdk'
import type { PageDocument } from '../api/generated'
import { aiPageBuilderChatApi, type SilentRequestConfig } from '../api/clients'

type Content = Pick<PageDocument, 'html' | 'css' | 'js'>
export interface StagedPage extends Content {
  runId: string
  toolCallId: string
  baseHash: string
}
export interface StreamingPreview {
  runId: string
  toolCallId: string
  content?: Content
  status: 'generating' | 'validating' | 'waiting' | 'staged' | 'checkpoint'
}
export function stagedPage(value: unknown, runId: string): StagedPage | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return
  const data = value as Record<string, unknown>
  const keys = ['runId', 'toolCallId', 'baseHash', 'html', 'css']
  if (
    Object.keys(data).some((key) => ![...keys, 'js'].includes(key)) ||
    keys.some((key) => typeof data[key] !== 'string') ||
    ('js' in data && typeof data.js !== 'string')
  )
    return
  if (
    data.runId !== runId ||
    !data.toolCallId ||
    (data.toolCallId as string).length > 200 ||
    !/^[a-f0-9]{64}$/.test(data.baseHash as string)
  )
    return
  if (
    (data.html as string).length > 200_000 ||
    (data.css as string).length > 100_000 ||
    (typeof data.js === 'string' && data.js.length > 100_000)
  )
    return
  return data as unknown as StagedPage
}

export function useStreamingPreview(options: {
  messages: Readonly<Ref<DeepReadonly<UIMessage[]> | UIMessage[] | undefined>>
  runId: Ref<string | undefined>
  baseline: () => Content | undefined
}) {
  const preview = shallowRef<StreamingPreview>()
  const accepted = shallowRef<StagedPage>()
  let baseline: Content | undefined
  let key = ''
  let sequence = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  let previousMessageIds = new Set<string>()
  let ignoredToolIds = new Set<string>()
  let discardedRun: string | undefined
  function cancelValidation() {
    sequence++
    clearTimeout(timer)
    controller?.abort()
  }
  function clear() {
    cancelValidation()
    preview.value = undefined
    accepted.value = undefined
    baseline = undefined
    key = ''
  }
  function discard() {
    discardedRun = options.runId.value
    clear()
  }
  watch(
    options.runId,
    (runId) => {
      clear()
      discardedRun = undefined
      ignoredToolIds = new Set()
      previousMessageIds = new Set((options.messages.value || []).map((message) => message.id))
      if (runId) baseline = options.baseline() ? { ...options.baseline()! } : undefined
    },
    { flush: 'sync' },
  )
  const tool = computed(() => {
    if (!options.runId.value || discardedRun === options.runId.value) return undefined
    const parts = (options.messages.value || [])
      .filter((message) => message.role === 'assistant' && !previousMessageIds.has(message.id))
      .flatMap<UIMessage['parts'][number] | DeepReadonly<UIMessage['parts'][number]>>(
        (message) => message.parts,
      )
    const tools = parts.filter(
      (part): part is DeepReadonly<ToolPart> | ToolPart =>
        'toolName' in part &&
        !ignoredToolIds.has(part.toolCallId) &&
        ['update_current_page', 'patch_current_page'].includes(part.toolName),
    )
    return tools[tools.length - 1]
  })
  function accept(value: unknown) {
    if (!options.runId.value || discardedRun === options.runId.value) return
    const data = stagedPage(value, options.runId.value)
    if (!data || (tool.value && tool.value.toolCallId !== data.toolCallId)) return
    cancelValidation()
    accepted.value = data
    key = `${data.runId}/${data.toolCallId}`
    preview.value = {
      runId: data.runId,
      toolCallId: data.toolCallId,
      content: { html: data.html, css: data.css, js: data.js ?? '' },
      status: 'staged',
    }
  }
  watch(
    tool,
    (part) => {
      const runId = options.runId.value
      if (!runId || !part) return
      const nextKey = `${runId}/${part.toolCallId}`
      if (nextKey !== key) {
        cancelValidation()
        baseline = accepted.value
          ? { html: accepted.value.html, css: accepted.value.css, js: accepted.value.js ?? '' }
          : baseline
        key = nextKey
        preview.value = {
          runId,
          toolCallId: part.toolCallId,
          status: 'generating',
        }
      }
      if (accepted.value?.toolCallId === part.toolCallId) return
      cancelValidation()
      const token = sequence
      // The SDK already parses partial JSON; never infer or apply a partial patch here.
      const input =
        part.input && typeof part.input === 'object' && !Array.isArray(part.input)
          ? (part.input as Record<string, unknown>)
          : {}
      timer = setTimeout(async () => {
        if (token !== sequence) return
        if (
          part.toolName === 'patch_current_page' ||
          !baseline ||
          (typeof input.html !== 'string' && typeof input.css !== 'string')
        )
          return
        const candidate = {
          html: typeof input.html === 'string' ? input.html : baseline.html,
          css: typeof input.css === 'string' ? input.css : baseline.css,
        }
        preview.value = { ...preview.value!, status: 'validating' }
        controller = new AbortController()
        const options: SilentRequestConfig = { signal: controller.signal, mute: true }
        try {
          await aiPageBuilderChatApi.validatePageContent(
            { validatePageContentRequest: candidate },
            options,
          )
          if (token === sequence)
            preview.value = { ...preview.value!, content: candidate, status: 'generating' }
        } catch {
          if (token === sequence) preview.value = { ...preview.value!, status: 'waiting' }
        }
      }, 250)
    },
    { deep: true, flush: 'sync' },
  )
  function seedBaseline(content: Content) {
    baseline = { ...content }
  }
  function restartAttempt() {
    cancelValidation()
    // Keep only the server-validated content from completed tools; ignore truncated tool input.
    baseline = accepted.value || baseline
    preview.value = undefined
    key = ''
    ignoredToolIds = new Set(
      (options.messages.value || []).flatMap((message) =>
        message.parts.flatMap((part) => ('toolCallId' in part ? [part.toolCallId] : [])),
      ),
    )
  }
  onScopeDispose(clear)
  return { preview, accept, discard, seedBaseline, restartAttempt }
}
