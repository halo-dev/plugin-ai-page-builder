import { describe, expect, it } from 'vitest'
import type { PageDocument } from '../../api/generated'
import {
  cloneDocument,
  codeDocument,
  createDocumentHistory,
  editCanvasDocument,
  updateDocument,
} from '../document'

const document: PageDocument = {
  schemaVersion: 1,
  editorVersion: 'legacy',
  mode: 'visual',
  projectData: { pages: [{ component: { script: 'ignored legacy data' } }] },
  html: '<section id="intro"><h2>Hello</h2><p>World <em>together</em></p></section>',
  css: '.card { --space: 2rem; padding: var(--space); }\n@media (max-width: 600px) { @supports (display: grid) { .card { display: grid } } }',
}

describe('single source document', () => {
  it('loads legacy visual exports without interpreting or modifying projectData', () => {
    const loaded = cloneDocument(document)
    expect(loaded).toEqual(document)
    expect(loaded).not.toBe(document)
    expect(updateDocument(loaded, { html: loaded.html })).toBe(loaded)
    expect(codeDocument(loaded)).toMatchObject({
      mode: 'code',
      projectData: null,
      editorVersion: null,
      html: document.html,
      css: document.css,
    })
    expect(document.mode).toBe('visual')
  })
  it('normalizes only an actual change, when the content changes', () => {
    expect(updateDocument(document, { html: '<p>New</p>' })).toMatchObject({
      html: '<p>New</p>',
      mode: 'code',
      projectData: null,
      editorVersion: null,
    })
    expect(updateDocument(document, {})).toBe(document)
  })
  it('edits leaf text literally, retaining mixed siblings and exact stylesheet', () => {
    const next = editCanvasDocument(document, [0, 0], {
      type: 'text',
      value: '<script>literal</script>',
    })
    expect(next.html).toContain('&lt;script&gt;literal&lt;/script&gt;')
    expect(next.html).toContain('<p>World <em>together</em></p>')
    expect(next.css).toBe(document.css)
    expect(next).toMatchObject({ mode: 'code', projectData: null, editorVersion: null })
    expect(editCanvasDocument(document, [0, 1], { type: 'text', value: 'destroy nested' })).toBe(
      document,
    )
  })
  it('does not serialize or dirty on a no-op, including browser-normalized markup', () => {
    const original = { ...document, html: "<p class='intro'>Hello</p>" }
    expect(editCanvasDocument(original, [0], { type: 'text', value: 'Hello' })).toBe(original)
    expect(editCanvasDocument(original, [0], { type: 'up' })).toBe(original)
    expect(editCanvasDocument(original, [0], { type: 'attribute', name: 'href', value: 'x' })).toBe(
      original,
    )
  })
  it('refuses copying IDs in the element or its descendants', () => {
    expect(editCanvasDocument(document, [0], { type: 'duplicate' })).toBe(document)
    const nested = { ...document, html: '<section><p id="item">One</p></section>' }
    expect(editCanvasDocument(nested, [0], { type: 'duplicate' })).toBe(nested)
    expect(
      editCanvasDocument(document, [0, 0], { type: 'duplicate' }).html.match(/<h2>/g),
    ).toHaveLength(2)
  })
  it('moves and deletes sibling blocks without changing their content', () => {
    const original = { ...document, html: '<p>One</p><p>Two</p>' }
    expect(editCanvasDocument(original, [1], { type: 'up' }).html).toBe('<p>Two</p><p>One</p>')
    expect(editCanvasDocument(original, [0], { type: 'down' }).html).toBe('<p>Two</p><p>One</p>')
    expect(editCanvasDocument(original, [0], { type: 'delete' }).html).toBe('<p>Two</p>')
  })
  it('edits image and link attributes with safe new-window relation', () => {
    const original = {
      ...document,
      html: '<img src="/old.png" alt="old"><a href="/old" rel="author">Link</a>',
    }
    const image = editCanvasDocument(original, [0], {
      type: 'attribute',
      name: 'src',
      value: '/new.png',
    })
    expect(image.html).toContain('src="/new.png"')
    const alt = editCanvasDocument(image, [0], {
      type: 'attribute',
      name: 'alt',
      value: 'New image',
    })
    expect(alt.html).toContain('alt="New image"')
    const link = editCanvasDocument(alt, [1], { type: 'attribute', name: 'href', value: '/new' })
    const target = editCanvasDocument(link, [1], {
      type: 'attribute',
      name: 'target',
      value: '_blank',
    })
    expect(target.html).toContain('href="/new"')
    expect(target.html).toContain('rel="author noopener noreferrer"')
  })
})

describe('shared bounded history', () => {
  it('undoes a canvas change to the exact legacy snapshot and redoes it', () => {
    const history = createDocumentHistory(document)
    const changed = editCanvasDocument(document, [0, 0], { type: 'text', value: 'Changed' })
    expect(history.push(changed)).toBe(true)
    expect(history.push(changed)).toBe(false)
    expect(history.undo()).toEqual(document)
    expect(history.redo()).toEqual(changed)
    history.current.html = 'outside mutation'
    expect(history.current).toEqual(changed)
    expect(document.html).toContain('Hello')
  })
  it('shares source and AI snapshots and discards redo after a new edit', () => {
    const history = createDocumentHistory(document, 3)
    history.push(updateDocument(document, { html: 'source' }))
    history.push(updateDocument(document, { html: 'AI' }))
    history.undo()
    history.push(updateDocument(document, { html: 'manual' }))
    expect(history.canRedo).toBe(false)
    history.push(updateDocument(document, { html: 'last' }))
    expect(history.undo().html).toBe('manual')
    expect(history.undo().html).toBe('source')
    expect(history.canUndo).toBe(false)
    history.reset(document)
    expect(history.current).toEqual(document)
    expect(history.canUndo).toBe(false)
  })
})
