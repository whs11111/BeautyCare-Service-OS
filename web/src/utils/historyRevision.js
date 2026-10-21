// A restored history is a new generation, not a set to union with cached messages.
export function acceptsHistoryEvent(conversation, event) {
  return !conversation.historyRevision || conversation.historyRevision === event.historyRevision;
}

export function beginHistorySnapshot(conversation, remote) {
  if (!remote.historyRevision) return;
  if (conversation.historyRevision !== remote.historyRevision) {
    conversation.messages = [];
    conversation.historyRevision = remote.historyRevision;
  }
}

export function orderedHistory(localMessages, remote) {
  const localById = new Map(localMessages.map(message => [message.id, message]));
  const remoteIds = new Set(remote.messages.map(message => message.id));
  return [
    ...(remote.historyRevision ? [] : localMessages.filter(message => !remoteIds.has(message.id))),
    ...remote.messages.map(message => localById.get(message.id) || message),
  ];
}
