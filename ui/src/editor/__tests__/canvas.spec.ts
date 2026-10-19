import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PageDocument } from '../../api/generated'
import type { PreviewRootState } from '../preview'
import VisualEditor from '../../components/VisualEditor.vue'

const mocks = vi.hoisted(() => ({
  renderPagePreview:
    vi.fn<(name: string, document: PageDocument, signal: AbortSignal) => Promise<string>>(),
}))
vi.mock('../../api/clients', () => ({
  renderPagePreview: mocks.renderPagePreview,
  pagePreviewUrl: (name: string) =>
    `/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/${name}/preview`,
}))
vi.mock('../../components/ImageReplaceDropdown.vue', () => ({
  default: { template: '<div><slot /></div>' },
}))

const document: PageDocument = {
  schemaVersion: 1,
  mode: 'visual',
  editorVersion: 'legacy',
  projectData: { script: 'ignored' },
  title: 'Legacy',
  html: '<section><h2>Hello</h2><a href="/destination">Link</a></section>',
  css: 'h2 { color: red }',
}
const ROOT_ID = 'ai-page-builder-demo'
const SERVER_CSS = `#${ROOT_ID} h2 { color: navy }`
const wrappers: VueWrapper[] = []

function themePage(snapshot = document, name = 'demo') {
  return (
    '<!DOCTYPE html><html class="theme light" data-theme="auto" lang="zh"><head>' +
    `<style data-theme-style>header { color: green }</style><style data-page-style>${SERVER_CSS}</style>` +
    '</head><body><header>主题头</header>' +
    `<main id="ai-page-builder-${name}">${snapshot.html}</main>` +
    '<footer>主题脚</footer><script data-theme-script type="application/json">{}</script></body></html>'
  )
}
function render(props: Partial<InstanceType<typeof VisualEditor>['$props']> = {}) {
  const wrapper = mount(VisualEditor, {
    props: { name: 'demo', document, themeLayout: true, validated: true, ...props },
    attachTo: window.document.body,
    global: { stubs: { FormKit: true } },
  })
  wrappers.push(wrapper)
  return wrapper
}
// jsdom does not load srcdoc. Populate the iframe with the exact response before
// dispatching its load event, keeping fetch completion separate from DOM loading.
async function load(wrapper: VueWrapper) {
  await flushPromises()
  const frame = wrapper.get('iframe').element as HTMLIFrameElement
  const canvas = frame.contentDocument!
  canvas.open()
  canvas.write(frame.srcdoc)
  canvas.close()
  await wrapper.get('iframe').trigger('load')
  return canvas
}
async function click(canvas: Document, selector: string) {
  const event = new MouseEvent('click', { bubbles: true, cancelable: true })
  canvas.querySelector(selector)!.dispatchEvent(event)
  await nextTick()
  return event
}
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((candidate) => candidate.text() === text)!
}
function deferredPreview() {
  let resolve!: (source: string) => void
  let reject!: (error: Error) => void
  const promise = new Promise<string>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}
function emittedDocument(wrapper: VueWrapper): PageDocument {
  return wrapper.emitted('update:document')![0]![0] as PageDocument
}

beforeEach(() => {
  mocks.renderPagePreview
    .mockReset()
    .mockImplementation(async (name, snapshot) => themePage(snapshot, name))
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers.length = 0
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('theme preview canvas', () => {
  it('requests a detached validated snapshot and uses the full response in a script-disabled iframe', async () => {
    const wrapper = render()
    await flushPromises()
    const [name, snapshot, signal] = mocks.renderPagePreview.mock.calls[0]!
    expect(name).toBe('demo')
    expect(snapshot).toEqual(document)
    expect(snapshot).not.toBe(document)
    expect(snapshot.projectData).not.toBe(document.projectData)
    expect(signal).toBeInstanceOf(AbortSignal)
    const frame = wrapper.get('iframe')
    expect(frame.attributes('sandbox')).toBe('allow-same-origin')
    expect(frame.attributes('src')).toBeUndefined()
    const preview = new DOMParser().parseFromString(frame.attributes('srcdoc')!, 'text/html')
    expect(preview.querySelector('header')?.textContent).toBe('主题头')
    expect(preview.querySelector('footer')?.textContent).toBe('主题脚')
    expect(preview.getElementById(ROOT_ID)?.querySelector('section')?.outerHTML).toBe(document.html)
    expect(preview.querySelector('base')?.href).toBe(
      new URL(
        '/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/demo/preview',
        window.location.href,
      ).href,
    )
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('keeps server CSS, theme scripts and chrome intact without adding client CSS scoping', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    expect(canvas.querySelector('style[data-page-style]')?.textContent).toBe(SERVER_CSS)
    expect(canvas.querySelector('style[data-theme-style]')?.textContent).toBe(
      'header { color: green }',
    )
    expect(canvas.querySelector('style[data-apb-canvas]')).toBeNull()
    expect(canvas.documentElement.outerHTML).not.toContain('@scope')
    expect(canvas.getElementById(ROOT_ID)?.innerHTML).toBe(document.html)
    expect(canvas.querySelector('script[data-theme-script]')?.textContent).toBe('{}')
    expect(canvas.querySelector('header')?.textContent).toBe('主题头')
    expect(canvas.querySelector('footer')?.textContent).toBe('主题脚')
    await click(canvas, 'h2')
    expect(wrapper.get('textarea').element.value).toBe('Hello')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('selects source elements and parents, clearing selection on theme chrome or the container', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    await click(canvas, 'h2')
    expect(wrapper.get('[role="dialog"] strong').text()).toBe('h2')
    await button(wrapper, '父区块').trigger('click')
    expect(wrapper.get('[role="dialog"] strong').text()).toBe('section')
    expect(button(wrapper, '父区块').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('包含嵌套内容')
    for (const selector of ['header', 'footer', `#${ROOT_ID}`]) {
      await click(canvas, 'h2')
      await click(canvas, selector)
      expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    }
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('intercepts navigation and emits an immutable source edit, freezing repeated edits until the new preview loads', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    expect((await click(canvas, 'a')).defaultPrevented).toBe(true)
    await wrapper.get('textarea').setValue('New link text')
    const changed = emittedDocument(wrapper)
    expect(changed).toMatchObject({
      mode: 'code',
      schemaVersion: 2,
      editorVersion: null,
      projectData: null,
      css: document.css,
      html: '<section><h2>Hello</h2><a href="/destination">New link text</a></section>',
    })
    expect(document.html).toContain('>Link<')
    expect(canvas.querySelector('a')!.textContent).toBe('Link')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea').setValue('Stale overwrite')
    await button(wrapper, '删除').trigger('click')
    expect(wrapper.emitted('update:document')).toHaveLength(1)

    await wrapper.setProps({ document: changed, validated: false })
    expect(mocks.renderPagePreview).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ validated: true })
    const current = await load(wrapper)
    await click(current, 'a')
    expect(wrapper.get('textarea').element.value).toBe('New link text')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    await wrapper.get('textarea').setValue('Next edit')
    expect(wrapper.emitted('update:document')).toHaveLength(2)
  })

  it('edits link attributes and applies block operations using source-relative paths', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    await click(canvas, 'a')
    await wrapper.get('select').setValue('_blank')
    expect(emittedDocument(wrapper).html).toBe(
      '<section><h2>Hello</h2><a href="/destination" target="_blank" rel="noopener noreferrer">Link</a></section>',
    )
    const current = emittedDocument(wrapper)
    await wrapper.setProps({ document: current })
    const reloaded = await load(wrapper)
    await click(reloaded, 'h2')
    await button(wrapper, '下移').trigger('click')
    const moved = wrapper.emitted('update:document')![1]![0] as PageDocument
    expect(moved.html).toBe(
      '<section><a href="/destination" target="_blank" rel="noopener noreferrer">Link</a><h2>Hello</h2></section>',
    )
    expect(moved.html).not.toMatch(/<header|<footer|data-bootstrap/)
  })

  it('focuses annotation input and submits the selected source snapshot with a trimmed comment', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    await click(canvas, 'h2')
    await button(wrapper, '让 AI 修改').trigger('click')
    const input = wrapper.get('textarea[aria-label="修改说明"]')
    expect(window.document.activeElement).toBe(input.element)
    expect(button(wrapper, '添加到对话').attributes('disabled')).toBeDefined()
    await input.setValue('  Make this heading larger  ')
    await button(wrapper, '添加到对话').trigger('click')
    expect(wrapper.emitted('annotate')).toEqual([
      [
        {
          path: [0, 0],
          tag: 'h2',
          summary: 'Hello',
          html: '<h2>Hello</h2>',
          content: { html: document.html, css: document.css, js: '' },
        },
        'Make this heading larger',
      ],
    ])
    expect(wrapper.find('textarea[aria-label="修改说明"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.find('[aria-hidden="true"] > [style]').exists()).toBe(false)
    await click(canvas, 'h2')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('blocks annotations when the source changes or the annotation limit is reached', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    await click(canvas, 'h2')
    await wrapper.setProps({ annotationCount: 5 })
    expect(button(wrapper, '让 AI 修改').attributes('disabled')).toBeDefined()
    await button(wrapper, '让 AI 修改').trigger('click')
    expect(wrapper.find('textarea[aria-label="修改说明"]').exists()).toBe(false)
    await wrapper.setProps({ annotationCount: 0 })
    await button(wrapper, '让 AI 修改').trigger('click')
    await wrapper.get('textarea[aria-label="修改说明"]').setValue('Update heading')
    await wrapper.setProps({
      document: { ...document, css: 'h2 { color: blue }' },
      validated: false,
    })
    expect(wrapper.text()).toContain('内容已变化，请重新选择元素')
    expect(button(wrapper, '添加到对话').attributes('disabled')).toBeDefined()
    await button(wrapper, '添加到对话').trigger('click')
    expect(wrapper.emitted('annotate')).toBeUndefined()
  })

  it('does not request or render an initially unvalidated document', async () => {
    const wrapper = render({ validated: false })
    await flushPromises()
    expect(mocks.renderPagePreview).not.toHaveBeenCalled()
    expect(wrapper.find('iframe').exists()).toBe(false)
    await wrapper.setProps({ validated: true })
    const canvas = await load(wrapper)
    expect(mocks.renderPagePreview).toHaveBeenCalledOnce()
    await click(canvas, 'h2')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
  })

  it('keeps an old preview frozen when it loads before validation of changed content', async () => {
    const wrapper = render()
    await flushPromises()
    const newer = { ...document, html: '<p>Source edit</p>' }
    await wrapper.setProps({ document: newer, validated: false })
    const oldCanvas = await load(wrapper)
    expect(oldCanvas.querySelector('h2')?.textContent).toBe('Hello')
    await click(oldCanvas, 'h2')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(mocks.renderPagePreview).toHaveBeenCalledOnce()

    const request = deferredPreview()
    mocks.renderPagePreview.mockReturnValueOnce(request.promise)
    await wrapper.setProps({ validated: true })
    expect(mocks.renderPagePreview).toHaveBeenLastCalledWith('demo', newer, expect.any(AbortSignal))
    await click(oldCanvas, 'h2')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    request.resolve(themePage(newer))
    const newCanvas = await load(wrapper)
    await click(newCanvas, 'p')
    expect(wrapper.get('textarea').element.value).toBe('Source edit')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('refetches CSS-only changes while keeping the last validated theme render frozen', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    await click(canvas, 'h2')
    const newer = { ...document, css: 'h2 { color: blue }' }
    await wrapper.setProps({ document: newer, validated: false })
    expect(canvas.querySelector('style[data-page-style]')?.textContent).toBe(SERVER_CSS)
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea').setValue('Stale overwrite')
    expect(wrapper.emitted('update:document')).toBeUndefined()
    mocks.renderPagePreview.mockResolvedValueOnce(
      themePage(newer).replace(SERVER_CSS, `#${ROOT_ID} h2 { color: blue }`),
    )
    await wrapper.setProps({ validated: true })
    const current = await load(wrapper)
    expect(mocks.renderPagePreview).toHaveBeenLastCalledWith('demo', newer, expect.any(AbortSignal))
    expect(current.querySelector('style[data-page-style]')?.textContent).toBe(
      `#${ROOT_ID} h2 { color: blue }`,
    )
    expect(current.querySelector('header')?.textContent).toBe('主题头')
    expect(current.querySelector('footer')?.textContent).toBe('主题脚')
    expect(current.documentElement.outerHTML).not.toContain('@scope')
  })

  it('refetches when only the theme layout changes', async () => {
    const wrapper = render()
    await load(wrapper)
    expect(mocks.renderPagePreview).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ themeLayout: false })
    await flushPromises()
    expect(mocks.renderPagePreview).toHaveBeenCalledTimes(2)
    expect(mocks.renderPagePreview).toHaveBeenLastCalledWith(
      'demo',
      document,
      expect.any(AbortSignal),
    )
  })

  it('ignores an older async response arriving after the current preview', async () => {
    const stale = deferredPreview()
    mocks.renderPagePreview.mockReturnValueOnce(stale.promise)
    const wrapper = render()
    const signal = mocks.renderPagePreview.mock.calls[0]![2]
    const newer = { ...document, html: '<p>Current</p>' }
    await wrapper.setProps({ document: newer })
    const canvas = await load(wrapper)
    const currentFrame = wrapper.get('iframe').element
    const currentSource = wrapper.get('iframe').attributes('srcdoc')
    expect(signal.aborted).toBe(true)
    stale.resolve(themePage())
    await flushPromises()
    expect(wrapper.get('iframe').element).toBe(currentFrame)
    expect(wrapper.get('iframe').attributes('srcdoc')).toBe(currentSource)
    await click(canvas, 'p')
    expect(wrapper.get('textarea').element.value).toBe('Current')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
  })

  it('ignores a response invalidated before it resolves', async () => {
    const request = deferredPreview()
    mocks.renderPagePreview.mockReturnValueOnce(request.promise)
    const wrapper = render()
    await wrapper.setProps({ validated: false })
    request.resolve(themePage())
    await flushPromises()
    expect(wrapper.find('iframe').exists()).toBe(false)
    await wrapper.setProps({ validated: true })
    const canvas = await load(wrapper)
    expect(mocks.renderPagePreview).toHaveBeenCalledTimes(2)
    await click(canvas, 'h2')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
  })

  it('does not unlock stale DOM when content changes during iframe loading', async () => {
    const wrapper = render()
    await flushPromises()
    const request = deferredPreview()
    const newer = { ...document, html: '<p>New content</p>' }
    mocks.renderPagePreview.mockReturnValueOnce(request.promise)
    await wrapper.setProps({ document: newer })
    const staleCanvas = await load(wrapper)
    await click(staleCanvas, 'h2')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('当前主题不支持画布内预览')
    request.resolve(themePage(newer))
    const current = await load(wrapper)
    await click(current, 'p')
    expect(wrapper.get('textarea').element.value).toBe('New content')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
  })

  it('refetches the original snapshot when undo happens after a new response but before its iframe loads', async () => {
    const wrapper = render()
    await load(wrapper)
    const originalFrame = wrapper.get('iframe').element
    const newer = { ...document, html: '<p>New content</p>' }
    await wrapper.setProps({ document: newer })
    await flushPromises()
    const unloadedFrame = wrapper.get('iframe').element
    expect(unloadedFrame).not.toBe(originalFrame)
    expect(wrapper.get('iframe').attributes('srcdoc')).toContain(newer.html)

    const restore = deferredPreview()
    mocks.renderPagePreview.mockReturnValueOnce(restore.promise)
    await wrapper.setProps({ document })
    expect(mocks.renderPagePreview).toHaveBeenCalledTimes(3)
    expect(mocks.renderPagePreview).toHaveBeenLastCalledWith(
      'demo',
      document,
      expect.any(AbortSignal),
    )
    const staleCanvas = await load(wrapper)
    await click(staleCanvas, 'p')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

    restore.resolve(themePage())
    const restored = await load(wrapper)
    expect(wrapper.get('iframe').element).not.toBe(unloadedFrame)
    await click(restored, 'h2')
    expect(wrapper.get('textarea').element.value).toBe('Hello')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('clears a failed preview request and unlocks the loaded original snapshot on undo', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    const originalFrame = wrapper.get('iframe').element
    await click(canvas, 'h2')
    mocks.renderPagePreview.mockRejectedValueOnce(new Error('Preview unavailable'))
    await wrapper.setProps({ document: { ...document, html: '<p>New content</p>' } })
    await flushPromises()
    expect(wrapper.text()).toContain('Preview unavailable')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ document })
    await flushPromises()
    expect(wrapper.text()).not.toContain('Preview unavailable')
    expect(wrapper.get('iframe').element).toBe(originalFrame)
    expect(mocks.renderPagePreview).toHaveBeenCalledTimes(2)
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    await wrapper.get('textarea').setValue('Restored and editable')
    expect(emittedDocument(wrapper).html).toBe(
      '<section><h2>Restored and editable</h2><a href="/destination">Link</a></section>',
    )
  })

  it('preserves preformatted leading newlines without parsing and serializing the response twice', async () => {
    const source = { ...document, html: '<pre>\n\ncode</pre>' }
    const wrapper = render({ document: source })
    const canvas = await load(wrapper)
    expect(wrapper.get('iframe').attributes('srcdoc')).toContain(source.html)
    // HTML parsing consumes exactly the first newline immediately after <pre>.
    expect(canvas.querySelector('pre')?.textContent).toBe('\ncode')
    expect(wrapper.text()).not.toContain('当前主题不支持画布内预览')
    await click(canvas, 'pre')
    expect(wrapper.get('textarea').element.value).toBe('\ncode')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('reports current request errors, retries and ignores failures from obsolete requests', async () => {
    const stale = deferredPreview()
    mocks.renderPagePreview.mockReturnValueOnce(stale.promise)
    const wrapper = render()
    const newer = { ...document, html: '<p>New content</p>' }
    mocks.renderPagePreview.mockRejectedValueOnce(new Error('Preview unavailable'))
    await wrapper.setProps({ document: newer })
    await flushPromises()
    expect(wrapper.text()).toContain('Preview unavailable')
    await button(wrapper, '重新加载画布').trigger('click')
    const canvas = await load(wrapper)
    stale.reject(new Error('Obsolete failure'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('Preview unavailable')
    expect(wrapper.text()).not.toContain('Obsolete failure')
    await click(canvas, 'p')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
  })

  it.each([
    [
      'missing content container',
      '<main>主题错误页</main>',
      'main',
      '当前主题不支持画布内预览，编辑请使用源码模式。',
    ],
    [
      'changed content DOM',
      themePage({ ...document, html: '<p>Unexpected rewrite</p>' }),
      'p',
      '页面正文与预览结构不一致，请检查 HTML 标签闭合或主题模板后重试。',
    ],
    [
      'a theme-inserted script shifting source paths',
      themePage({
        ...document,
        html: `<script type="application/json">{}</script>${document.html}`,
      }),
      'h2',
      '页面正文与预览结构不一致，请检查 HTML 标签闭合或主题模板后重试。',
    ],
  ])('rejects a theme with %s', async (_, source, selector, message) => {
    mocks.renderPagePreview.mockResolvedValueOnce(source)
    const wrapper = render()
    const canvas = await load(wrapper)
    expect(wrapper.text()).toContain(message)
    expect(wrapper.get('a[target="_blank"]').attributes('href')).toBe(
      '/apis/console.api.aipagebuilder.halo.run/v1alpha1/pages/demo/preview',
    )
    await click(canvas, selector)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('accepts attribute-only image rewrites from the render pipeline', async () => {
    const withImage: PageDocument = {
      ...document,
      html: '<section><h2>Hello</h2><img src="/upload/photo.png" alt="photo"></section>',
    }
    mocks.renderPagePreview.mockResolvedValueOnce(
      themePage({
        ...withImage,
        html: '<section><h2>Hello</h2><img src="/upload/photo.png" alt="photo" sizes="100vw" srcset="/upload/photo.png?width=400 400w, /upload/photo.png?width=800 800w"></section>',
      }),
    )
    const wrapper = render({ document: withImage })
    const canvas = await load(wrapper)
    expect(wrapper.text()).not.toContain('页面正文与预览结构不一致')
    await click(canvas, 'img')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    await click(canvas, 'h2')
    expect(wrapper.get('textarea').element.value).toBe('Hello')
  })

  it('disables retry and asks for validation while the source is unvalidated', async () => {
    mocks.renderPagePreview.mockRejectedValueOnce(new Error('Preview unavailable'))
    const wrapper = render()
    await flushPromises()
    expect(button(wrapper, '重新加载画布').attributes('disabled')).toBeUndefined()
    await wrapper.setProps({ validated: false })
    expect(wrapper.text()).toContain('请先修正源码并等待校验通过。')
    expect(button(wrapper, '重新加载画布').attributes('disabled')).toBeDefined()
    await button(wrapper, '重新加载画布').trigger('click')
    expect(mocks.renderPagePreview).toHaveBeenCalledOnce()
    await wrapper.setProps({ validated: true })
    const canvas = await load(wrapper)
    expect(wrapper.text()).not.toContain('Preview unavailable')
    expect(wrapper.text()).not.toContain('请先修正源码并等待校验通过。')
    await click(canvas, 'h2')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
  })

  it('does not overwrite original root state when the chosen state changes before a replacement iframe loads', async () => {
    const light: PreviewRootState = {
      mode: 'light',
      selector: 'html.light',
      classes: ['light'],
      attributes: { 'data-theme': 'light' },
    }
    const dark: PreviewRootState = {
      mode: 'dark',
      selector: 'html.dark',
      classes: ['dark'],
      attributes: { 'data-theme': 'dark' },
    }
    const wrapper = render({ rootStates: [light, dark] })
    await load(wrapper)
    await wrapper.setProps({ document: { ...document, html: '<p>Replacement</p>' } })
    await flushPromises()
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    const canvas = frame.contentDocument!
    // Control the single load event explicitly: doc.close() also schedules a
    // native jsdom load, which would otherwise capture simulated dark as default.
    const deferLoad = (event: Event) => event.stopImmediatePropagation()
    frame.addEventListener('load', deferLoad, true)
    canvas.open()
    canvas.write(frame.srcdoc)
    const root = canvas.documentElement
    expect([...root.classList]).toEqual(['theme', 'light'])
    expect(root.getAttribute('data-theme')).toBe('auto')
    await wrapper.setProps({ rootState: dark })
    expect([...root.classList]).toEqual(['theme', 'light'])
    expect(root.getAttribute('data-theme')).toBe('auto')
    canvas.close()
    await flushPromises()
    frame.removeEventListener('load', deferLoad, true)
    await wrapper.get('iframe').trigger('load')
    expect([...root.classList]).toEqual(['theme', 'dark'])
    expect(root.getAttribute('data-theme')).toBe('dark')
    await wrapper.setProps({ rootState: undefined })
    expect([...root.classList]).toEqual(['theme', 'light'])
    expect(root.getAttribute('data-theme')).toBe('auto')
    await click(canvas, 'p')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    expect(mocks.renderPagePreview).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('restores original theme root classes and attributes after switching declared states to default', async () => {
    const light: PreviewRootState = {
      mode: 'light',
      selector: 'html.light',
      classes: ['light'],
      attributes: { 'data-theme': 'light' },
    }
    const dark: PreviewRootState = {
      mode: 'dark',
      selector: 'html.dark',
      classes: ['dark'],
      attributes: { 'data-theme': 'dark', 'data-color-mode': 'dark' },
    }
    const wrapper = render({ rootStates: [light, dark] })
    const canvas = await load(wrapper)
    const root = canvas.documentElement
    await wrapper.setProps({ rootState: dark })
    expect([...root.classList]).toEqual(['theme', 'dark'])
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(root.getAttribute('data-color-mode')).toBe('dark')
    await wrapper.setProps({ rootState: light })
    expect([...root.classList]).toEqual(['theme', 'light'])
    expect(root.getAttribute('data-theme')).toBe('light')
    expect(root.hasAttribute('data-color-mode')).toBe(false)
    await wrapper.setProps({ rootState: undefined })
    expect([...root.classList]).toEqual(['theme', 'light'])
    expect(root.getAttribute('data-theme')).toBe('auto')
    expect(root.hasAttribute('data-color-mode')).toBe(false)
    expect(root.getAttribute('lang')).toBe('zh')
    expect(mocks.renderPagePreview).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it('refreshes selection and popper positions when root state moves an element without resizing it', async () => {
    const dark: PreviewRootState = {
      mode: 'dark',
      selector: 'html.dark',
      classes: ['dark'],
      attributes: {},
    }
    const wrapper = render({ rootStates: [dark] })
    const canvas = await load(wrapper)
    const heading = canvas.querySelector('h2')!
    vi.spyOn(heading, 'getBoundingClientRect').mockImplementation(
      () => new DOMRect(40, canvas.documentElement.classList.contains('dark') ? 160 : 60, 100, 20),
    )
    await click(canvas, 'h2')
    const outline = () => wrapper.get('[aria-hidden="true"] > [style]').element as HTMLElement
    const popper = () => wrapper.get('[role="dialog"]').element as HTMLElement
    expect(outline().style.top).toBe('60px')
    expect(popper().style.top).toBe('88px')
    await wrapper.setProps({ rootState: dark })
    expect(outline().style.top).toBe('160px')
    expect(popper().style.top).toBe('188px')
    await wrapper.setProps({ rootState: undefined })
    expect(outline().style.top).toBe('60px')
    expect(popper().style.top).toBe('88px')
    expect(mocks.renderPagePreview).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })

  it.each(['transitionend', 'transitioncancel'])(
    'refreshes selection after an ancestor position transition emits %s',
    async (type) => {
      const wrapper = render()
      const canvas = await load(wrapper)
      const heading = canvas.querySelector('h2')!
      let top = 60
      vi.spyOn(heading, 'getBoundingClientRect').mockImplementation(
        () => new DOMRect(40, top, 100, 20),
      )
      await click(canvas, 'h2')
      const outline = () => wrapper.get('[aria-hidden="true"] > [style]').element as HTMLElement
      expect(outline().style.top).toBe('60px')
      top = 160
      heading.parentElement!.dispatchEvent(new canvas.defaultView!.Event(type, { bubbles: true }))
      await nextTick()
      expect(outline().style.top).toBe('160px')
      expect((wrapper.get('[role="dialog"]').element as HTMLElement).style.top).toBe('188px')
      expect(mocks.renderPagePreview).toHaveBeenCalledOnce()
      expect(wrapper.emitted('update:document')).toBeUndefined()
    },
  )

  it('switches device size and disabled state without refetching or changing the theme DOM', async () => {
    const wrapper = render()
    const canvas = await load(wrapper)
    const html = canvas.documentElement.outerHTML
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    expect([frame.style.width, frame.style.height]).toEqual(['1280px', '900px'])
    await wrapper.get('button[aria-label="放大"]').trigger('click')
    expect([frame.style.width, frame.style.height]).toEqual(['1280px', '900px'])
    await wrapper.setProps({ device: 'tablet' })
    expect([frame.style.width, frame.style.height]).toEqual(['768px', '1024px'])
    expect(frame.style.transform).toBe('scale(1)')
    await wrapper.setProps({ device: 'mobile', disabled: true })
    expect([frame.style.width, frame.style.height]).toEqual(['390px', '844px'])
    expect(wrapper.get('iframe').element.parentElement?.style.width).toBe('390px')
    expect(canvas.documentElement.outerHTML).toBe(html)
    await click(canvas, 'h2')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper.setProps({ disabled: false })
    await click(canvas, 'h2')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()
    expect(mocks.renderPagePreview).toHaveBeenCalledOnce()
  })

  it('maps selection coordinates through zoom and stage resizing, and restores fit scale', async () => {
    const observers: Array<{ targets: Set<Element>; notify: () => void }> = []
    vi.stubGlobal(
      'ResizeObserver',
      class {
        targets = new Set<Element>()
        constructor(callback: () => void) {
          observers.push({ targets: this.targets, notify: callback })
        }
        observe(target: Element) {
          this.targets.add(target)
        }
        unobserve(target: Element) {
          this.targets.delete(target)
        }
        disconnect() {
          this.targets.clear()
        }
      },
    )
    const wrapper = render({ device: 'mobile' })
    const canvas = await load(wrapper)
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    const wrap = frame.parentElement!
    const stage = wrap.parentElement!
    let stageWidth = 600
    let stageHeight = 1000
    Object.defineProperties(stage, {
      clientWidth: { get: () => stageWidth },
      clientHeight: { get: () => stageHeight },
    })
    Object.defineProperties(wrap, {
      offsetLeft: { get: () => 20 },
      offsetTop: { get: () => 20 },
    })
    const resize = observers.find((observer) => observer.targets.has(stage))!
    resize.notify()
    const heading = canvas.querySelector('h2')!
    vi.spyOn(heading, 'getBoundingClientRect').mockReturnValue(new DOMRect(40, 60, 100, 20))
    await click(canvas, 'h2')
    const outline = () => wrapper.get('[aria-hidden="true"] > [style]').element as HTMLElement
    expect(outline().style.left).toBe('40px')
    expect(outline().style.top).toBe('60px')
    await wrapper.get('button[aria-label="放大"]').trigger('click')
    expect(frame.style.transform).toBe('scale(1.1)')
    expect(frame.style.width).toBe('390px')
    expect(parseFloat(frame.style.height)).toBeCloseTo(1000 / 1.1, 2)
    expect(wrap.style.height).toBe('1000px')
    expect(outline().style.left).toBe('44px')
    expect(outline().style.top).toBe('66px')
    expect(parseFloat(outline().style.width)).toBeCloseTo(110)
    expect(outline().style.height).toBe('22px')

    stageWidth = 195
    resize.notify()
    await nextTick()
    expect(frame.style.transform).toBe('scale(1.1)')
    await wrapper.get('button[aria-label="适应画布"]').trigger('click')
    expect(frame.style.transform).toBe('scale(0.5)')
    expect(frame.style.width).toBe('390px')
    expect(wrap.style.width).toBe('195px')
    expect(frame.style.height).toBe('2000px')
    expect(wrap.style.height).toBe('1000px')
    expect(outline().style.left).toBe('20px')
    expect(outline().style.top).toBe('30px')
    expect(outline().style.width).toBe('50px')
    expect(outline().style.height).toBe('10px')

    stageHeight = 700
    resize.notify()
    await nextTick()
    expect(frame.style.height).toBe('1400px')
    expect(wrap.style.height).toBe('700px')
    expect(frame.style.width).toBe('390px')
    expect(mocks.renderPagePreview).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:document')).toBeUndefined()
  })
})
