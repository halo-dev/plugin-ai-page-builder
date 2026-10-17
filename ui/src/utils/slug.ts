export const slugValidation = [
  ['required'],
  ['matches', /^[a-z0-9]+(?:-[a-z0-9]+)*(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*$/],
  ['length', 1, 200],
]
