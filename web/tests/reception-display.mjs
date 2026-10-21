import assert from 'node:assert/strict';
import { emotionBand, waitingSince, meaningfulMessage, durationText, matchesConversation, unrepliedCount, sortConversations } from '../src/utils/receptionDisplay.js';
assert.equal(emotionBand(90), 'red'); assert.equal(emotionBand(70), 'red');
assert.equal(emotionBand(40), 'yellow'); assert.equal(emotionBand(39), 'green');
assert.equal(meaningfulMessage({ text: '好的，谢谢' }), false);
assert.equal(meaningfulMessage({ text: '谢谢！' }), false);
for (const text of ['你好', '您好！', '在吗？']) {
  assert.equal(meaningfulMessage({ text }), true, 'Greeting needs a service response');
  const greetingConversation = { active: true, unread: 0, reception: { id: 'greeting', status: 'active', mode: 'HUMAN' }, messages: [
    { from: 'user', text, sentAt: '2026-10-19T10:00:00+08:00', sessionId: 'greeting' },
    { from: 'agent', kind: 'bot-reply', automatic: true, text: '您好，有什么可以帮您？', sentAt: '2026-10-19T10:00:01+08:00', sessionId: 'greeting' },
  ] };
  assert.equal(waitingSince(greetingConversation), Date.parse('2026-10-19T10:00:00+08:00'));
  assert.equal(unrepliedCount(greetingConversation), 1);
  greetingConversation.unread = 0;
  assert.equal(durationText(Date.parse('2026-10-19T10:00:31+08:00') - waitingSince(greetingConversation)), '31秒');
  assert.equal(waitingSince({ ...greetingConversation, active: false }), waitingSince(greetingConversation));
  greetingConversation.messages.push({ from: 'agent', text: '您好，请问您需要什么帮助？', agentId: 'agent1', sessionId: 'greeting' });
  assert.equal(waitingSince(greetingConversation), null);
  assert.equal(unrepliedCount(greetingConversation), 0);
}
assert.equal(meaningfulMessage({ attachment: { name: '过敏.jpg' } }), true);
const user = { id: 'u', from: 'user', text: '三天没发货', sentAt: '2026-10-17T10:00:00Z', sessionId: 's' };
const conversation = { name: '孙女士', order: '88129', reception: { id: 's' }, messages: [user] };
assert.equal(waitingSince(conversation), Date.parse(user.sentAt));
conversation.messages.push({ from: 'agent', kind: 'handoff-request', text: '转人工', sessionId: 's' });
assert.equal(waitingSince(conversation), Date.parse(user.sentAt));
conversation.messages.push({ from: 'agent', kind: 'bot-reply', text: '请提供订单号', sessionId: 's' });
assert.equal(waitingSince(conversation), Date.parse(user.sentAt), 'Bot reply must not stop human waiting');
conversation.unread = 0;
conversation.active = false;
conversation.messages.push({ from: 'user', text: '订单号是88129', sentAt: '2026-10-17T10:00:15Z', sessionId: 's' });
conversation.messages.push({ from: 'user', text: '好的', sentAt: '2026-10-17T10:00:16Z', sessionId: 's' });
assert.equal(waitingSince(conversation), Date.parse(user.sentAt), 'Read state, other active chat and follow-up messages do not reset the start');
assert.equal(unrepliedCount(conversation), 2);
assert.equal(durationText(Date.parse('2026-10-17T10:02:05Z') - waitingSince(conversation)), '2分5秒');
conversation.messages.push({ from: 'system', kind: 'handoff', text: '客服正在为您服务', sessionId: 's' });
conversation.messages.push({ from: 'agent', automatic: true, text: '自动接待通知', sessionId: 's' });
assert.equal(waitingSince(conversation), Date.parse(user.sentAt));
conversation.messages.push({ from: 'agent', text: '正在帮您核实', sentAt: '2026-10-17T10:03:00Z', sessionId: 's', agentId: 'agent1' });
assert.equal(waitingSince(conversation), null, 'Actual human send stops waiting');
assert.equal(unrepliedCount(conversation), 0);
conversation.messages.push({ from: 'user', text: '还有一笔订单需要查询', sentAt: '2026-10-17T10:04:00Z', sessionId: 's' });
assert.equal(waitingSince(conversation), Date.parse('2026-10-17T10:04:00Z'));
assert.equal(waitingSince({ ...conversation, reception: { id: 's', status: 'closed' } }), null);
assert.equal(waitingSince({ ...conversation, reception: { id: 'new-session' } }), null);
assert.equal(matchesConversation(conversation, '孙女士 发货'), true);
assert.equal(matchesConversation(conversation, '不存在'), false);
assert.equal(durationText(125000), '2分5秒');
console.log('PASS reception colors, waiting clock, full-history search');
const messages = [
  { from: 'user', text: '什么时候发货', sessionId: 'current' },
  { from: 'user', text: '请帮我查询', sessionId: 'current' },
  { from: 'agent', kind: 'handoff-request', sessionId: 'current' },
];
assert.equal(unrepliedCount({ reception: { id: 'current' }, messages }), 2);
assert.equal(unrepliedCount({ reception: { id: 'other' }, messages }), 0);
messages.push({ from: 'agent', text: '请您提供订单号', sessionId: 'current' });
assert.equal(unrepliedCount({ reception: { id: 'current' }, messages }), 0);
messages.push({ from: 'user', text: '好的，谢谢', sessionId: 'current' });
assert.equal(unrepliedCount({ reception: { id: 'current' }, messages }), 0);
const older = { id: 1, messages: [{ sentAt: '2026-10-17T10:00:00Z' }] };
const newer = { id: 2, messages: [{ sentAt: '2026-10-17T10:10:00Z' }] };
const newest = { id: 3, messages: [{ sentAt: '2026-10-17T10:20:00Z' }] };
assert.deepEqual(sortConversations([older, newest, newer]).map(item => item.id), [3, 2, 1]);
assert.deepEqual(sortConversations([older, newest, newer], ['1']).map(item => item.id), [1, 3, 2]);
assert.deepEqual(sortConversations([older, newest, newer], [1, 2]).map(item => item.id), [2, 1, 3]);
assert.deepEqual(sortConversations([older, newest, newer], []).map(item => item.id), [3, 2, 1]);
console.log('PASS pending reply counts, latest-first ordering, manual pins outrank recency, unpin restores recency');
