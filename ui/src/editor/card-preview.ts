import { aiPageBuilderApi } from '../api/clients'

const cache = new Map<string, { html: string; css: string }>()
const pending = new Map<string, Promise<{ html: string; css: string }>>()
const queue: Array<() => void> = []
let active = 0

function drain() {
  while (active < 3 && queue.length) queue.shift()!()
}

export function loadCardPreview(name: string, revision: string) {
  const key = `${name}:${revision}`
  const cached = cache.get(key)
  if (cached) return Promise.resolve(cached)
  const existing = pending.get(key)
  if (existing) return existing
  const promise = new Promise<{ html: string; css: string }>((resolve, reject) => {
    queue.push(() => {
      active++
      aiPageBuilderApi
        .getPage({ name })
        .then(({ data: detail }) => {
          const content = { html: detail.document.html, css: detail.document.css }
          const actualKey = `${name}:${detail.page.draftRevision}`
          cache.set(actualKey, content)
          if (cache.size > 40) cache.delete(cache.keys().next().value!)
          resolve(content)
        }, reject)
        .finally(() => {
          active--
          pending.delete(key)
          drain()
        })
    })
  })
  pending.set(key, promise)
  drain()
  return promise
}
