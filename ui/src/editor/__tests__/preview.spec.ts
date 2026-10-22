import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { candidatePreview } from '../preview'

// Vitest returns an empty module for CSS imports, including ?raw.
vi.mock('../../../../src/main/resources/static/modern-normalize-3.0.1.css?raw', () => {
  const uiRoot = process.cwd().endsWith('/ui') ? '..' : '.'
  return {
    default: readFileSync(
      resolve(process.cwd(), uiRoot, 'src/main/resources/static/modern-normalize-3.0.1.css'),
      'utf8',
    ),
  }
})

describe('AI candidate preview', () => {
  it('keeps style-closing injection inside CSS text', () => {
    const source = candidatePreview({
      html: '<p>正文</p>',
      css: 'p:before{content:"</StYlE><script>alert(1)</script>"}',
    })
    const doc = new DOMParser().parseFromString(source, 'text/html')
    expect(doc.querySelectorAll('style')).toHaveLength(1)
    expect(doc.querySelector('script')).toBeNull()
    expect(doc.body.innerHTML).toBe('<p>正文</p>')
    expect(doc.querySelector('style')?.textContent).toContain('\\3c /StYlE>')
  })
  it('prepends escaped theme CSS without changing page content or CSS', () => {
    const page = { html: '<p>正文</p>', css: 'p { color: red }' }
    const themeCss = 'p { color: navy } p:before { content: "</style><script>alert(1)</script>" }'
    const source = candidatePreview(page, themeCss)
    const doc = new DOMParser().parseFromString(source, 'text/html')
    const styles = doc.querySelectorAll('style')
    expect(styles).toHaveLength(2)
    expect(styles[0]?.textContent).toContain('color: navy')
    expect(styles[0]?.textContent).toContain('\\3c /style>')
    expect(styles[1]?.textContent).toBe(page.css)
    expect(doc.querySelector('script')).toBeNull()
    expect(doc.body.innerHTML).toBe(page.html)
    expect(page).toEqual({ html: '<p>正文</p>', css: 'p { color: red }' })
  })
  it('simulates only declared safe root state and never reads Console mode', () => {
    document.documentElement.className = 'dark console'
    const page = { html: '<p>Body</p>', css: '' }
    const light = candidatePreview(page, '', {
      mode: 'light',
      selector: 'html.light',
      classes: ['light'],
      attributes: {},
    })
    expect(new DOMParser().parseFromString(light, 'text/html').documentElement.className).toBe(
      'light',
    )
    const malicious = candidatePreview(page, '', {
      mode: 'dark',
      selector: '',
      classes: ['dark', 'bad" onclick="x'],
      attributes: { onclick: 'evil', 'data-theme': 'dark' },
    })
    const root = new DOMParser().parseFromString(malicious, 'text/html').documentElement
    expect(root.className).toBe('dark')
    expect(root.hasAttribute('onclick')).toBe(false)
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(
      new DOMParser().parseFromString(candidatePreview(page), 'text/html').documentElement
        .className,
    ).toBe('')
    document.documentElement.className = ''
  })
  it('mirrors the standalone normalize baseline before the page CSS', () => {
    const page = { html: '<p>正文</p>', css: 'body { margin: 0 auto }' }
    const doc = new DOMParser().parseFromString(
      candidatePreview(page, '', undefined, true),
      'text/html',
    )
    const styles = [...doc.querySelectorAll('style')]
    expect(styles).toHaveLength(2)
    expect(styles[0]?.textContent).toContain('box-sizing: border-box')
    expect(styles[0]?.textContent).toContain('font-family:')
    expect(styles[0]?.textContent).toContain('system-ui')
    expect(styles[0]?.textContent).toContain('margin: 0')
    expect(styles[1]?.textContent).toBe(page.css)
    expect(doc.body.hasAttribute('style')).toBe(false)
  })
  it('leaves theme-layout previews without the standalone baseline', () => {
    const page = { html: '<p>正文</p>', css: 'p { color: red }' }
    const doc = new DOMParser().parseFromString(candidatePreview(page), 'text/html')
    const styles = [...doc.querySelectorAll('style')]
    expect(styles).toHaveLength(1)
    expect(styles[0]?.textContent).toBe(page.css)
  })
  it('places restrictive CSP before content and preserves candidate markup', () => {
    const source = candidatePreview({ html: '<h2>候选</h2>', css: 'h2 {color:red}' })
    const doc = new DOMParser().parseFromString(source, 'text/html')
    const policy = doc
      .querySelector('meta[http-equiv="Content-Security-Policy"]')
      ?.getAttribute('content')
    expect(policy).toContain("default-src 'none'")
    expect(policy).toContain("script-src 'none'")
    expect(policy).toContain("base-uri 'none'")
    expect(policy).toContain("form-action 'none'")
    expect(source.indexOf('Content-Security-Policy')).toBeLessThan(source.indexOf('<style>'))
    expect(doc.body.innerHTML).toBe('<h2>候选</h2>')
  })
})
