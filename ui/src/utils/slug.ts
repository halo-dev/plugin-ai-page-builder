export const slugOptionalValidation = [
  ['matches', /^[a-z0-9]+(?:-[a-z0-9]+)*(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*$/],
  ['length', 1, 200],
]

export const slugValidation = [['required'], ...slugOptionalValidation]
