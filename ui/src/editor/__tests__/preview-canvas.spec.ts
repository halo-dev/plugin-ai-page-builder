import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  adjustPreviewScale,
  calculateFitScale,
  initialPreviewDevice,
  PREVIEW_DEVICES,
} from '../preview-canvas'

afterEach(() => vi.unstubAllGlobals())

describe('preview canvas zoom', () => {
  it('fits the viewport into the stage without zooming in', () => {
    expect(calculateFitScale({ width: 800, height: 600 }, PREVIEW_DEVICES.desktop)).toBe(0.625)
    expect(calculateFitScale({ width: 1600, height: 450 }, PREVIEW_DEVICES.desktop)).toBe(0.5)
    expect(calculateFitScale({ width: 1600, height: 1200 }, PREVIEW_DEVICES.desktop)).toBe(1)
    expect(calculateFitScale({ width: 800, height: 512 }, PREVIEW_DEVICES.tablet)).toBe(0.5)
    expect(calculateFitScale({ width: 390, height: 422 }, PREVIEW_DEVICES.mobile)).toBe(0.5)
    expect(calculateFitScale({ width: 0, height: 0 }, PREVIEW_DEVICES.mobile)).toBe(1)
  })
  it('steps the manual scale and clamps it to the supported range', () => {
    expect(adjustPreviewScale(1, 1)).toBeCloseTo(1.1)
    expect(adjustPreviewScale(1, -1)).toBeCloseTo(0.9)
    expect(adjustPreviewScale(1.25, 1)).toBe(1.25)
    expect(adjustPreviewScale(0.4, -1)).toBe(0.4)
  })
  it('opens phones at the mobile width and wider screens at the desktop width', () => {
    const matchMedia = vi.fn((query: string) => ({
      matches: query === '(max-width: 767px)',
      media: query,
    }))
    vi.stubGlobal('matchMedia', matchMedia)
    expect(initialPreviewDevice()).toBe('mobile')
    matchMedia.mockReturnValue({ matches: false, media: '(max-width: 767px)' })
    expect(initialPreviewDevice()).toBe('desktop')
  })
})
