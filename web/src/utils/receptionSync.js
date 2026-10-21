import { useReceptionStore } from "../store/reception";
import { beginHistorySnapshot, orderedHistory } from './historyRevision.js';
export const mergeReceptionState = (chat, reception = useReceptionStore()) => {
    reception.customers.forEach((remote) => {
      const conversation = chat.conversations.find((item) => item.id === remote.id);
      if (!conversation) return;
      beginHistorySnapshot(conversation, remote);
      if (remote.name) {
        conversation.name = remote.name;
        conversation.avatar = remote.avatar || Array.from(remote.name)[0];
      }
      const previousMode = conversation.reception?.mode;
      if (conversation.reception?.id !== remote.session?.id) {
        conversation.emotion = 'neutral'; conversation.risk = 0;
        conversation.dynamicTag = null; conversation.emotionHistory = [];
      }
      conversation.reception = remote.session;
      conversation.sessions = remote.sessions;
      conversation.commitments = remote.commitments || [];
      if (Number.isFinite(remote.session?.emotionScore)) conversation.emotionScore = remote.session.emotionScore;
      if (remote.session?.emotion) conversation.emotion = remote.session.emotion;
      if (Number.isFinite(remote.session?.riskScore)) conversation.risk = remote.session.riskScore;
      if (Array.isArray(remote.session?.riskEvidence)) conversation.riskEvidence = remote.session.riskEvidence;
      if (remote.session?.emotionAnalyzedMessageId) {
        conversation.handoffRequired = Boolean(remote.session.handoffRequired);
        conversation.dynamicTag = remote.session.emotionScore >= 70 ? '情绪升级 · 持续关注'
          : remote.session.emotionScore >= 40 ? '负面情绪 · 持续关注' : null;
      }
      if (remote.session?.mode === 'BOT' && !remote.session.latestUserMessageId) {
        conversation.dynamicTag = null;
        conversation.workflowStatus = 'AI自动回复中';
      }
      if (Array.isArray(remote.session?.emotionTrend) && remote.session.emotionTrend.length) {
        conversation.emotionHistory = remote.session.emotionTrend.map(point => ({
          score: point.score,
          time: Number.isFinite(Date.parse(point.time)) ? new Date(point.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : point.time,
        }));
      }
      if (remote.session?.emotionAnalysisOwner === 'server') conversation.needsAnalysis = Boolean(remote.session.emotionAnalysisPending);
      if (Number.isFinite(remote.unreadCount) && chat.viewMode === 'agent') conversation.unread = remote.unreadCount;
      let newUserId;
      remote.messages.forEach((message) => {
        const existing = conversation.messages.find((item) => item.id === message.id);
        if (existing) { Object.assign(existing, message); return; }
        conversation.messages.push(message);
        conversation.preview = message.text;
        conversation.time = new Date(message.sentAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
        chat.markResolvedEventsDirty(conversation.id);
        if (message.from === "user") {
          if (!Number.isFinite(remote.unreadCount)) conversation.unread = chat.viewMode === "agent" && chat.activeId === conversation.id ? 0 : (conversation.unread || 0) + 1;
          conversation.needsAnalysis = true;
          conversation.needsSummary = true;
          conversation.aiHidden = false;
          newUserId = message.id;
        }
      });
      // A locally sent reply can arrive before its preceding takeover notice is polled.
      // Keep the server's event order rather than appending that notice after the reply.
      const orderedMessages = orderedHistory(conversation.messages, remote);
      if (orderedMessages.length !== conversation.messages.length || orderedMessages.some((message, index) => message !== conversation.messages[index]))
        conversation.messages = orderedMessages;
      const latest = conversation.messages.at(-1);
      if (latest) {
        conversation.preview = latest.text;
        conversation.time = new Date(latest.sentAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
      }
      if (chat.viewMode === 'agent' && chat.activeId === conversation.id && !document.hidden && conversation.unread > 0) chat.markRead(conversation.id);
      if (!newUserId && previousMode === 'BOT' && remote.session?.mode === 'HUMAN_PENDING')
        newUserId = [...conversation.messages].reverse().find(message => message.from === 'user')?.id;
      if (newUserId && chat.viewMode === "agent") {
        chat.queuedReplyMessageIds[conversation.id] = newUserId;
        void chat.refreshAssistance(conversation.id);
      }
    });
};
export const startReceptionSync = async (chat) => {
  const reception = useReceptionStore();
  await reception.initialize(chat.conversations);
  if (chat.viewMode === 'agent') await reception.poll(true);
  const merge = () => mergeReceptionState(chat, reception);
  merge();
  let running = false;
  const timer = setInterval(async () => {
    if (running) return;
    running = true;
    try { await reception.poll(chat.viewMode === "agent"); merge(); }
    catch { reception.error = "接待服务连接失败，正在重试"; }
    finally { running = false; }
  }, 1000);
  return () => clearInterval(timer);
};
