import { describe, expect, it } from 'vitest'
import { effectScope, shallowRef } from 'vue'
import { contentDigest } from '../../editor/annotations'
import { usePageAnnotations } from '../usePageAnnotations'
import type { PageDocument } from '../../api/generated'
const content = { html: '你好🌍', css: '.x{color:red}' }
const target = { path: [0], tag: 'p', summary: '你好', html: '<p>你好</p>', content }
describe('annotation snapshots', () => {
  it('matches server SHA256 vectors using UTF8 byte lengths', async () => {
    expect(await contentDigest({ html: '', css: '' })).toBe(
      'af5570f5a1810b7af78caf4bc70a660f0df51e42baf91d4de5b2328de0e83dfc',
    )
    expect(await contentDigest(content)).toBe(
      '557f0b0e80db941285eebb74d056458f023a21f3afaa2430bc727722727ed668',
    )
  })
  it('keeps legacy empty-JS hashes and includes nonempty UTF8 JS bytes', async () => {
    expect(await contentDigest({ ...content, js: '' })).toBe(await contentDigest(content))
    const js = '你好🌍'
    const encoder = new TextEncoder()
    const parts = [content.html, content.css, js].map((value) => encoder.encode(value))
    const bytes = new Uint8Array(parts.reduce((length, part) => length + 4 + part.length, 0))
    let offset = 0
    for (const part of parts) {
      new DataView(bytes.buffer).setUint32(offset, part.length)
      bytes.set(part, offset + 4)
      offset += 4 + part.length
    }
    const digest = await crypto.subtle.digest('SHA-256', bytes)
    expect(await contentDigest({ ...content, js })).toBe(
      Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join(''),
    )
    expect(await contentDigest({ ...content, js })).not.toBe(await contentDigest(content))
    const scope = effectScope()
    const document = shallowRef<PageDocument>({
      ...content,
      title: 'Title',
      schemaVersion: 2,
      mode: 'code',
    })
    const state = scope.run(() => usePageAnnotations(document))!
    await state.add(target, 'Change')
    document.value = { ...document.value, js }
    expect(state.annotations.value[0]?.stale).toBe(true)
    await expect(state.snapshot(document.value)).rejects.toThrow('内容已变化')
    scope.stop()
  })
  it('preserves targets across title changes but never revives paths after undo', async () => {
    const scope = effectScope()
    const document = shallowRef<PageDocument>({
      ...content,
      title: 'Title',
      schemaVersion: 1,
      mode: 'code',
    })
    const state = scope.run(() => usePageAnnotations(document))!
    await state.add(target, ' Change this ')
    document.value = { ...document.value, title: 'Other' }
    expect((await state.snapshot(content)).annotations[0]?.comment).toBe('Change this')
    document.value = { ...document.value, html: 'changed' }
    document.value = { ...document.value, html: content.html }
    expect(state.annotations.value[0]?.stale).toBe(true)
    await expect(state.snapshot(content)).rejects.toThrow('内容已变化')
    scope.stop()
  })
  it('rejects stale captures and limits attachments to five', async () => {
    const scope = effectScope()
    const document = shallowRef<PageDocument>({
      ...content,
      title: 'Title',
      schemaVersion: 1,
      mode: 'code',
    })
    const state = scope.run(() => usePageAnnotations(document))!
    await expect(
      state.add({ ...target, content: { ...content, css: 'different' } }, 'Change'),
    ).rejects.toThrow('重新选择')
    for (let i = 0; i < 5; i++) await state.add(target, 'Change')
    await expect(state.add(target, 'Six')).rejects.toThrow('5')
    expect(state.annotations.value).toHaveLength(5)
    scope.stop()
  })
})
