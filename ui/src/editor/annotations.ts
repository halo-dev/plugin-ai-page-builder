import type { PageAnnotation, PageDocument } from '../api/generated'

export type AnnotationTarget = Omit<PageAnnotation, 'id' | 'comment'> & {
  content: Pick<PageDocument, 'html' | 'css' | 'js'>
}
export type AnnotationDraft = PageAnnotation & { baseHash: string; stale: boolean }
export type PreparedPage = { version: number; baseHash: string; annotations: PageAnnotation[] }

export async function contentDigest(
  content: Pick<PageDocument, 'html' | 'css' | 'js'>,
): Promise<string> {
  const encoder = new TextEncoder()
  const html = encoder.encode(content.html)
  const css = encoder.encode(content.css)
  const js = encoder.encode(content.js ?? '')
  const bytes = new Uint8Array(8 + html.length + css.length + (js.length ? 4 + js.length : 0))
  const lengths = new DataView(bytes.buffer)
  lengths.setUint32(0, html.length)
  bytes.set(html, 4)
  lengths.setUint32(4 + html.length, css.length)
  bytes.set(css, 8 + html.length)
  if (js.length) {
    lengths.setUint32(8 + html.length + css.length, js.length)
    bytes.set(js, 12 + html.length + css.length)
  }
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}
