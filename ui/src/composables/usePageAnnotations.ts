import { shallowRef, watch, type ShallowRef } from 'vue'
import type { PageAnnotation, PageDocument } from '../api/generated'
import { contentDigest, type AnnotationDraft, type AnnotationTarget } from '../editor/annotations'

export function usePageAnnotations(document: ShallowRef<PageDocument | undefined>) {
  const annotations = shallowRef<AnnotationDraft[]>([])
  let generation = 0
  watch(
    () => [document.value?.html, document.value?.css, document.value?.js ?? ''],
    (next, previous) => {
      if (next[0] === previous[0] && next[1] === previous[1] && next[2] === previous[2]) return
      generation++
      annotations.value = annotations.value.map((item) => ({ ...item, stale: true }))
    },
    { flush: 'sync' },
  )
  async function add(target: AnnotationTarget, comment: string) {
    if (annotations.value.length >= 5) throw new Error('每轮最多添加 5 项批注。')
    const capturedGeneration = generation
    const baseHash = await contentDigest(target.content)
    if (
      capturedGeneration !== generation ||
      target.content.html !== document.value?.html ||
      target.content.css !== document.value?.css ||
      (target.content.js ?? '') !== (document.value?.js ?? '')
    )
      throw new Error('页面内容已变化，请重新选择元素。')
    if (annotations.value.length >= 5) throw new Error('每轮最多添加 5 项批注。')
    const { content: _content, ...fields } = target
    void _content
    annotations.value = [
      ...annotations.value,
      { ...fields, id: crypto.randomUUID(), comment: comment.trim(), baseHash, stale: false },
    ]
  }
  function remove(id: string) {
    annotations.value = annotations.value.filter((item) => item.id !== id)
  }
  async function snapshot(content: Pick<PageDocument, 'html' | 'css' | 'js'>) {
    const captured = annotations.value
    const baseHash = await contentDigest(content)
    if (
      captured !== annotations.value ||
      captured.some((item) => item.stale || item.baseHash !== baseHash)
    )
      throw new Error('批注对应的内容已变化，请移除失效批注并重新选择元素。')
    const items: PageAnnotation[] = captured.map(({ baseHash: _hash, stale: _stale, ...item }) => {
      void _hash
      void _stale
      return { ...item, path: [...item.path] }
    })
    if (new TextEncoder().encode(JSON.stringify(items)).length > 48 * 1024)
      throw new Error('批注内容过多，请缩短说明或减少目标。')
    return { baseHash, annotations: items }
  }
  function clear() {
    generation++
    annotations.value = []
  }
  return { annotations, add, remove, snapshot, clear }
}
