export function profileFactRevisions(facts = []) {
  return facts.filter(fact => fact.status !== 'REJECTED').map(fact => JSON.stringify({
    dimension: fact.dimension, value: fact.value, source: fact.source,
    evidence: [...(fact.evidenceMessageIds || [])].sort(), mentions: fact.mentions || 0,
  })).sort();
}

export function updateProfileNotice(previous, facts, viewed = false) {
  const revisions = profileFactRevisions(facts);
  const known = new Set(previous?.revisions || []);
  const added = revisions.some(revision => !known.has(revision));
  return { revisions, unread: !viewed && (Boolean(previous?.unread) || added) };
}
