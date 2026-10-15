export const slugValidation = [
  ['required'],
  ['matches', /^[a-z0-9]+(?:-[a-z0-9]+)*(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*$/],
  ['length', 1, 200],
]

const SLUG_WORDS = [
  'amber',
  'anchor',
  'aurora',
  'breeze',
  'brook',
  'cedar',
  'cliff',
  'coral',
  'creek',
  'dawn',
  'delta',
  'dune',
  'ember',
  'falcon',
  'fern',
  'flint',
  'frost',
  'grove',
  'harbor',
  'haven',
  'island',
  'juniper',
  'lagoon',
  'linden',
  'maple',
  'meadow',
  'north',
  'oasis',
  'orchid',
  'pebble',
  'prairie',
  'quartz',
  'ridge',
  'river',
  'sable',
  'stone',
  'summit',
  'timber',
  'willow',
  'zephyr',
]

/** Random readable slug for the publish dialog, e.g. cedar-harbor-3k9x. */
export function randomSlug() {
  const word = () => SLUG_WORDS[Math.floor(Math.random() * SLUG_WORDS.length)]
  return `${word()}-${word()}-${Math.random().toString(36).slice(2, 6)}`
}
