import type { PageDocument } from '../api/generated'
import { rootAttributes, type PreviewRootState } from './preview'

async function scriptHash(source: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(source))
  return `'sha256-${btoa(String.fromCharCode(...new Uint8Array(digest)))}'`
}

// Only pass complete saved/validated documents. Never pass streaming tool input.
export async function runtimePreview(
  document: Pick<PageDocument, 'html' | 'css' | 'js'>,
  previewCss = '',
  rootState?: PreviewRootState,
): Promise<string> {
  const js = document.js ?? ''
  // Keep user source out of the HTML parser, including literal closing script tags.
  const literal = JSON.stringify(js).replace(/</g, '\\u003c')
  const bootstrap = `(()=>{const script=document.createElement('script');script.textContent=${literal};document.body.append(script)})();`
  const hashes = await Promise.all([scriptHash(bootstrap), scriptHash(js)])
  const csp = `default-src 'none'; script-src ${hashes.join(' ')}; script-src-attr 'none'; connect-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; object-src 'none'; frame-src 'none'; worker-src 'none'`
  const css = `${previewCss}\n${document.css}`.replace(/</g, '\\3c ')
  return `<!DOCTYPE html><html${rootAttributes(rootState)}><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${csp}"><meta name="viewport" content="width=device-width, initial-scale=1"><style>${css}</style></head><body>${document.html}<script>${bootstrap}</script></body></html>`
}
