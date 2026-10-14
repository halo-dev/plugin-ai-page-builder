import { describe, expect, it } from 'vitest'
import { runtimePreview } from '../runtime-preview'

describe('isolated runtime document', () => {
  it('keeps closing script tags inside JS data and authorizes exactly the two local scripts', async () => {
    const js =
      'document.body.dataset.value = "</script><script src="https://evil.test/a"></script>";'
    const source = await runtimePreview({ html: '<button>Run</button>', css: '', js })
    const document = new DOMParser().parseFromString(source, 'text/html')
    const scripts = [...document.querySelectorAll('script')]
    expect(scripts).toHaveLength(1)
    expect(scripts[0]!.textContent).toContain('\\u003c/script>')
    expect(document.querySelector('script[src]')).toBeNull()
    const policies = [
      ...document.querySelectorAll('meta[http-equiv="Content-Security-Policy"]'),
    ].map((item) => item.getAttribute('content')!)
    expect(policies[0]!.match(/sha256-/g)).toHaveLength(2)
    expect(policies[0]).toContain("connect-src 'none'")
    expect(policies[0]).toContain("base-uri 'none'")
    expect(policies[0]).toContain("form-action 'none'")
    expect(policies[0]).toContain("script-src-attr 'none'")
    expect(policies).toHaveLength(1)
    expect(policies[0]).not.toContain('unsafe-eval')
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(js))
    expect(policies[0]).toContain(btoa(String.fromCharCode(...new Uint8Array(digest))))
  })
  it('includes filtered theme root state and keeps CSS outside the HTML parser', async () => {
    const source = await runtimePreview(
      { html: '<p>Hello</p>', css: 'p:before{content:"</style><script>bad()</script>"}' },
      'body{color:navy}',
      { mode: 'dark', selector: '.dark', classes: ['dark'], attributes: { 'data-theme': 'dark' } },
    )
    const document = new DOMParser().parseFromString(source, 'text/html')
    expect(document.documentElement.className).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(document.querySelectorAll('script')).toHaveLength(1)
    expect(document.querySelector('style')?.textContent).toContain('body{color:navy}')
  })
})
