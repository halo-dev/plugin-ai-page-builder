// Runs started in this tab that the page list has not seen leave the active set yet.
// A short run can finish before the list mounts, so watching active-run polls alone
// would never refresh the cards it changed.
const pending = new Set<string>()

export function markRunStarted(pageName: string) {
  pending.add(pageName)
}

/** Removes and returns locally started pages that are no longer active. */
export function takeFinishedStarts(active: Iterable<string>) {
  const names = new Set(active)
  const finished = [...pending].filter((name) => !names.has(name))
  for (const name of finished) pending.delete(name)
  return finished
}
