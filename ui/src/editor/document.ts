import type { PageDocument } from '../api/generated'

export const cloneDocument = (document: PageDocument): PageDocument =>
  JSON.parse(JSON.stringify(document))
export const fingerprint = (document: PageDocument) => JSON.stringify(document)

export function codeDocument(
  document: PageDocument,
  candidate?: Pick<PageDocument, 'html' | 'css' | 'js'>,
): PageDocument {
  return {
    ...cloneDocument(document),
    mode: 'code',
    schemaVersion: 2,
    editorVersion: null,
    projectData: null,
    ...(candidate ? { html: candidate.html, css: candidate.css, js: candidate.js ?? '' } : {}),
  }
}

export function updateDocument(
  document: PageDocument,
  patch: Partial<Pick<PageDocument, 'html' | 'css' | 'js' | 'title' | 'description'>>,
): PageDocument {
  if (Object.entries(patch).every(([key, value]) => document[key as keyof PageDocument] === value))
    return document
  return codeDocument({ ...document, ...patch })
}

export function createDocumentHistory(initial: PageDocument, limit = 50) {
  let entries = [cloneDocument(initial)]
  let position = 0
  const capacity = Math.max(2, Math.floor(limit))
  return {
    get current() {
      return cloneDocument(entries[position]!)
    },
    get canUndo() {
      return position > 0
    },
    get canRedo() {
      return position < entries.length - 1
    },
    push(document: PageDocument): boolean {
      if (fingerprint(entries[position]!) === fingerprint(document)) return false
      entries = [...entries.slice(0, position + 1), cloneDocument(document)].slice(-capacity)
      position = entries.length - 1
      return true
    },
    undo(): PageDocument {
      position = Math.max(0, position - 1)
      return cloneDocument(entries[position]!)
    },
    redo(): PageDocument {
      position = Math.min(entries.length - 1, position + 1)
      return cloneDocument(entries[position]!)
    },
    reset(document: PageDocument) {
      entries = [cloneDocument(document)]
      position = 0
    },
  }
}

export type ElementPath = number[]
export type CanvasEdit =
  | { type: 'text'; value: string }
  | { type: 'attribute'; name: 'src' | 'alt' | 'href' | 'target'; value: string }
  | { type: 'duplicate' | 'delete' | 'up' | 'down' }

export function elementAtPath(root: ParentNode, path: ElementPath): HTMLElement | undefined {
  let node: ParentNode = root
  for (const index of path) {
    const child = node.children[index]
    if (!child) return
    node = child
  }
  return path.length ? (node as HTMLElement) : undefined
}

// Source paths are children-index chains, so canvas integrity depends only on the
// element tree. Attribute-only rewrites by the render pipeline (for example Halo
// adding srcset/sizes to /upload attachment images) must not fail this comparison.
export function sameElementStructure(a: Element, b: Element): boolean {
  if (a.childElementCount !== b.childElementCount) return false
  for (let index = 0; index < a.children.length; index++) {
    const left = a.children[index]!
    const right = b.children[index]!
    if (left.tagName !== right.tagName || !sameElementStructure(left, right)) return false
  }
  return true
}

export function elementPath(root: HTMLElement, element: HTMLElement): ElementPath | undefined {
  const path: number[] = []
  let node: Element | null = element
  while (node && node !== root) {
    const parent: HTMLElement | null = node.parentElement
    if (!parent) return
    path.unshift(Array.from(parent.children).indexOf(node))
    node = parent
  }
  return node === root && path.length ? path : undefined
}

export function canEditText(element: Element): boolean {
  return (
    element.children.length === 0 &&
    /^(h[1-6]|p|span|a|button|li|blockquote|figcaption|label|strong|em|small|div|section|td|th|pre|code)$/.test(
      element.localName,
    )
  )
}

export function canDuplicate(element: Element): boolean {
  // IDs may be referenced by arbitrary CSS or nodes elsewhere in the document.
  // Refuse these copies rather than guessing at a reference rewrite.
  return !element.hasAttribute('id') && !element.querySelector('[id]')
}

// Work on an inert body fragment, never the live iframe or its temporary shell.
// Parsing/selection alone never writes a normalized copy back to the document.
export function editCanvasDocument(
  document: PageDocument,
  path: ElementPath | undefined,
  edit: CanvasEdit,
): PageDocument {
  const root = window.document.implementation.createHTMLDocument('').body
  root.innerHTML = document.html
  const element = path ? elementAtPath(root, path) : undefined
  if (!element) return document
  const before = root.innerHTML
  switch (edit.type) {
    case 'text':
      if (!canEditText(element)) return document
      if (element.textContent !== edit.value) element.textContent = edit.value
      break
    case 'attribute':
      if (!(
        (element.localName === 'img' && ['src', 'alt'].includes(edit.name)) ||
        (element.localName === 'a' && ['href', 'target'].includes(edit.name))
      ))
        return document
      if (edit.name === 'target' && !['', '_self', '_blank'].includes(edit.value)) return document
      if (
        element.getAttribute(edit.name) === edit.value ||
        (!element.hasAttribute(edit.name) && !edit.value)
      )
        return document
      if (edit.value) element.setAttribute(edit.name, edit.value)
      else element.removeAttribute(edit.name)
      if (edit.name === 'target' && edit.value === '_blank') {
        const rel = new Set((element.getAttribute('rel') || '').split(/\s+/).filter(Boolean))
        rel.add('noopener')
        rel.add('noreferrer')
        element.setAttribute('rel', [...rel].join(' '))
      }
      break
    case 'duplicate':
      if (!canDuplicate(element)) return document
      element.after(element.cloneNode(true))
      break
    case 'delete':
      element.remove()
      break
    case 'up':
      element.previousElementSibling?.before(element)
      break
    case 'down':
      element.nextElementSibling?.after(element)
      break
  }
  return before === root.innerHTML ? document : updateDocument(document, { html: root.innerHTML })
}
