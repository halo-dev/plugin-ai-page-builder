import { describe, expect, it } from 'vitest'
import { randomSlug } from '../slug'

const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*$/

describe('randomSlug', () => {
  it('always matches the slug pattern and length bound', () => {
    for (let i = 0; i < 1000; i++) {
      const slug = randomSlug()
      expect(slug).toMatch(SLUG_PATTERN)
      expect(slug.length).toBeLessThanOrEqual(200)
    }
  })
  it('does not collide across many samples', () => {
    const samples = new Set(Array.from({ length: 1000 }, () => randomSlug()))
    expect(samples.size).toBe(1000)
  })
})
