import { isRobotMessage } from './serviceIdentity.js';

export const emotionBand = score => Number(score) >= 70 ? 'red' : Number(score) >= 40 ? 'yellow' : 'green';
// 主动问候也在等待接待；只过滤空消息和纯确认/结束语。
export const meaningfulMessage = message => Boolean(message?.attachment) || !/^(谢谢您|谢谢|好的|好|嗯|哦|收到|ok|拜拜|再见)*$/i.test((message?.text || '').replace(/[\s，,。.!！？?~～]/g, ''));
export const isHumanReply = message => message?.from === 'agent' && !isRobotMessage(message)
  && message.automatic !== true && !['handoff', 'closure'].includes(message.kind)
  && (Boolean(message.text?.trim()) || Boolean(message.attachment));
export function waitingSince(conversation) {
  if (conversation.reception?.status === 'closed') return null;
  let since = null;
  for (const message of conversation.messages || []) {
    if (conversation.reception?.id && message.sessionId !== conversation.reception.id) continue;
    if (isHumanReply(message)) since = null;
    else if (since === null && message.from === 'user' && meaningfulMessage(message)) {
      const timestamp = Date.parse(message.sentAt);
      if (Number.isFinite(timestamp)) since = timestamp;
    }
  }
  return since;
}
export const durationText = milliseconds => {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000));
  return seconds < 60 ? `${seconds}秒` : seconds < 3600 ? `${Math.floor(seconds / 60)}分${seconds % 60}秒` : `${Math.floor(seconds / 3600)}小时${Math.floor(seconds % 3600 / 60)}分`;
};
export function matchesConversation(conversation, query) {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  const searchable = [conversation.name, conversation.id, conversation.order, conversation.preview,
    ...(conversation.messages || []).map(message => `${message.text || ''} ${message.attachment?.name || ''}`)].join(' ').toLocaleLowerCase();
  return words.every(word => searchable.includes(word));
}
export function unrepliedCount(conversation) {
  let count = 0;
  for (const message of conversation.messages || []) {
    if (conversation.reception?.id && message.sessionId !== conversation.reception.id) continue;
    if (isHumanReply(message)) count = 0;
    else if (message.from === 'user' && meaningfulMessage(message)) count++;
  }
  return conversation.reception?.status === 'closed' ? 0 : count;
}
export function conversationActivity(conversation) {
  return (conversation.messages || []).reduce((latest, message) => Math.max(latest, Date.parse(message.sentAt) || 0), 0)
    || Date.parse(conversation.reception?.lastActivityAt || conversation.reception?.startedAt) || 0;
}
export function sortConversations(conversations, pinnedIds = []) {
  const pinned = new Set(pinnedIds.map(String));
  return [...conversations].sort((left, right) => Number(pinned.has(String(right.id))) - Number(pinned.has(String(left.id)))
    || conversationActivity(right) - conversationActivity(left) || String(left.id).localeCompare(String(right.id)));
}
