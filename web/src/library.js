// Favorites use the existing phone flag, including records deliberately archived by their owner.
export function inSection(notes, scope, searching = false) {
  return notes.filter((n) => scope === 'favorites' ? !!n.pinned : scope === 'archive' ? !!n.archived : searching || !n.archived)
}

export function newest(notes) {
  return notes.reduce((latest, n) => !latest || n.at > latest.at ? n : latest, null)
}
