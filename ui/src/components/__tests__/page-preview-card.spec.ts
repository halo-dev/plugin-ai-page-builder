import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PagePreviewCard from '../PagePreviewCard.vue'

const mocks = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('../../editor/card-preview', () => ({ loadCardPreview: mocks.load }))

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

let intersect: (() => void) | undefined

class Observer {
  constructor(callback: IntersectionObserverCallback) {
    intersect = () =>
      callback([{ isIntersecting: true } as IntersectionObserverEntry], this as never)
  }
  observe() {}
  disconnect() {}
}

beforeEach(() => {
  mocks.load.mockReset()
  intersect = undefined
  vi.stubGlobal('IntersectionObserver', Observer)
  vi.stubGlobal('ResizeObserver', Observer)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('page preview card', () => {
  it('uses the live layout setting without reloading the cached content', async () => {
    mocks.load.mockResolvedValue({ html: '<p>Preview</p>', css: 'body { margin: 0 auto }' })
    const wrapper = mount(PagePreviewCard, {
      props: { name: 'page', revision: 'r1', title: 'Title', themeLayout: false },
      global: { stubs: { VButton: true } },
    })
    await flushPromises()
    intersect?.()
    await flushPromises()
    expect(wrapper.find('iframe').attributes('srcdoc')).toContain('box-sizing: border-box')

    await wrapper.setProps({ themeLayout: true })
    expect(wrapper.find('iframe').attributes('srcdoc')).not.toContain('box-sizing: border-box')
    expect(mocks.load).toHaveBeenCalledTimes(1)
  })
})
