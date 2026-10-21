import { defineStore } from "pinia";
import { assistanceWorkflowApi, chatApi, workspaceStateApi } from "../api/http";
import { useReceptionStore } from "./reception";
import dingDemo from '../data/dingDemo.json' with { type: 'json' };
import { acceptsHistoryEvent } from '../utils/historyRevision.js';

let syncChannel;
let persistenceTimer;
let storageListener;
const analysisRevision = (conversation) => JSON.stringify(conversation.messages.map(
  ({ id, from, text, image }) => [id, from, text, image],
));
const resolvedEventsRefreshTimers = new Map();
const RESOLVED_EVENTS_REFRESH_DELAY = 3 * 60 * 1000;
const STORAGE_KEY = "beautycare-service-os-conversations-v1";
const ACTIVE_CONVERSATION_KEY = "beautycare-service-os-active-conversation";
const isReceptionNotice = (message) => (message.automatic === true && message.kind !== 'bot-reply') || (
  message.from !== "user" && /^(我是客服\d+，很高兴为您服务[！!]?|现在由客服\d+为您服务[。.]?)$/.test(message.text?.trim() || "")
);

const getInitialActiveId = () => {
  if (typeof sessionStorage === "undefined") return 1;
  const savedId = Number(sessionStorage.getItem(ACTIVE_CONVERSATION_KEY));
  return Number.isFinite(savedId) && savedId > 0 ? savedId : 1;
};

const seed = [
  {
    id: 1,
    name: "陈女士",
    avatar: "陈",
    status: "manual",
    time: "10:42",
    unread: 2,
    risk: 82,
    emotion: "critical",
    handoffRequired: true,
    riskEvidence: ["出现明确投诉关键词", "7天内第三次进线"],
    preview: "已经第三次了，还是没人解决…",
    tag: "投诉风险升级 · 补发工单待确认",
    traits: ["敏感肌", "偏好自然妆效", "文字沟通", "高价值会员"],
    order: "#88271",
    sku: "01",
    received: "05",
    messages: [
      {
        from: "user",
        text: "我上周收到的粉底液色号不对，前面客服说会补发。",
        time: "10:40",
      },
      {
        from: "user",
        text: "已经第三次了，还是没人解决！再这样我要投诉 12315。",
        time: "10:42",
      },
      { from: "system", text: "风险 Agent：情绪升级 · 投诉倾向 · 第 3 次进线" },
      {
        from: "user",
        image: true,
        text: "标签是 #05，我买的是 #01。",
        time: "10:43",
      },
    ],
  },
  {
    id: 2,
    name: "周女士",
    avatar: "周",
    status: "manual",
    time: "09:54",
    unread: 1,
    risk: 74,
    emotion: "critical",
    handoffRequired: true,
    riskEvidence: ["出现不良反应或就医安全事件"],
    preview: "用了之后脸有点肿，刚去医院",
    tag: "安全事件 · 等待人工接管",
    traits: ["敏感肌", "首次购买", "文字沟通"],
    order: "#88093",
    sku: "02",
    received: "02",
    messages: [
      {
        from: "user",
        text: "用了之后脸有点肿，刚从医院回来，请尽快联系我。",
        time: "09:54",
      },
    ],
  },
  {
    id: 3,
    name: "刘女士",
    avatar: "刘",
    status: "ai",
    time: "10:31",
    unread: 0,
    risk: 23,
    emotion: "neutral",
    handoffRequired: false,
    riskEvidence: [],
    preview: "发票大概什么时候可以开好？",
    tag: "跟进发票开具节点",
    traits: ["干性肌", "自然妆效"],
    order: "#88302",
    sku: "03",
    received: "03",
    messages: [
      { from: "user", text: "发票大概什么时候可以开好？", time: "10:31" },
      {
        from: "agent",
        text: "您好，电子发票预计今天下午发送到您的邮箱。",
        time: "10:32",
      },
    ],
  },
  {
    id: 4,
    name: "王先生",
    avatar: "王",
    status: "ai",
    time: "10:18",
    unread: 0,
    risk: 18,
    emotion: "positive",
    handoffRequired: false,
    riskEvidence: [],
    preview: "帮我查一下退款进度",
    tag: "正在查询退款状态",
    traits: ["礼赠购买", "电话优先"],
    order: "#87932",
    sku: "04",
    received: "04",
    messages: [
      { from: "user", text: "帮我查一下退款进度，谢谢。", time: "10:18" },
    ],
  },
  {
    id: 5,
    name: dingDemo.name,
    avatar: dingDemo.avatar,
    status: "waiting",
    time: "14:05",
    unread: 0,
    risk: 0,
    emotion: "neutral",
    handoffRequired: false,
    riskEvidence: [],
    preview: dingDemo.messages.at(-1).text,
    tag: "发货进度待跟进",
    traits: [],
    order: "#88129",
    sku: "08",
    received: "08",
    messages: dingDemo.messages.map(message => ({ ...message, sessionId: dingDemo.sessionId,
      ...(message.from === 'agent' ? { agentId: dingDemo.agentId, agentName: dingDemo.agentName, agentAvatar: '02' } : {}) })),
  },
];

const removeRawMessageEvidence = (conversation) => {
  const normalize = (value) =>
    String(value || "").replace(/[\s，。！？,.!?、"“”]/g, "");
  const userMessages = (conversation.messages || [])
    .filter((message) => message.from === "user")
    .map((message) => normalize(message.text));

  return (conversation.riskEvidence || []).filter((evidence) => {
    const normalizedEvidence = normalize(evidence);
    if (normalizedEvidence.length < 4) return true;
    return !userMessages.some((message) =>
      message.includes(normalizedEvidence),
    );
  });
};

const inferLegacyMessageDates = (messages = []) => {
  const normalized = messages.map((message) => ({ ...message }));
  let cursor = new Date();

  for (let index = normalized.length - 1; index >= 0; index -= 1) {
    const message = normalized[index];
    if (message.sentAt) {
      const savedDate = new Date(message.sentAt);
      if (!Number.isNaN(savedDate.getTime())) cursor = savedDate;
      continue;
    }

    const match = String(message.time || "").match(/^(\d{1,2}):(\d{2})$/);
    if (!match) continue;

    const candidate = new Date(cursor);
    candidate.setHours(Number(match[1]), Number(match[2]), 0, 0);
    if (candidate.getTime() > cursor.getTime()) {
      candidate.setDate(candidate.getDate() - 1);
    }
    message.sentAt = candidate.toISOString();
    cursor = candidate;
  }

  return normalized;
};

const withInferredMessageDates = (conversation) => ({
  ...conversation,
  messages: inferLegacyMessageDates(conversation.messages || []),
});

const createInitialConversations = () => {
  if (typeof localStorage !== "undefined") {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
      if (Array.isArray(saved) && saved.length) {
        return saved.map((savedConversation) => {
          const conversation = withInferredMessageDates(savedConversation);
          return {
            ...conversation,
            emotionHistory:
              conversation.analysisVersion === 4
                ? conversation.emotionHistory || []
                : [],
            needsAnalysis:
              conversation.analysisVersion === 4
                ? Boolean(conversation.needsAnalysis)
                : true,
            needsSummary:
              conversation.summaryVersion !== 5 ||
              conversation.trajectoryVersion !== 5 ||
              conversation.statusVersion !== 3 ||
              !Array.isArray(conversation.resolvedEvents) ||
              !Array.isArray(conversation.unresolvedEvents),
            riskEvidence: removeRawMessageEvidence(conversation),
          };
        });
      }
    } catch {
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  return seed.map((seedConversation) => {
    const conversation = withInferredMessageDates(seedConversation);
    return {
      ...conversation,
      baseTag: conversation.tag,
      needsAnalysis: true,
      needsSummary: true,
    };
  });
};

export const useChatStore = defineStore("chat", {
  state: () => ({
    conversations: createInitialConversations(),
    activeId: getInitialActiveId(),
    filter: "all",
    query: "",
    suggestion: "",
    suggestionMessageId: null,
    loading: false,
    analysisLoading: false,
    analysisPendingIds: [],
    contextRefreshIds: [],
    contextRefreshVersions: {},
    contextRefreshErrors: {},
    resolvedEventsPendingIds: [],
    resolvedEventsInitializedIds: [],
    resolvedEventsErrors: {},
    resolvedEventsDirtyVersions: {},
    replyGenerating: false,
    replyPendingIds: [],
    replyRequestedMessageIds: {},
    queuedReplyMessageIds: {},
    analysisError: null,
    messageFocusRequest: null,
    syncId: crypto.randomUUID(),
    viewMode: null,
  }),
  getters: {
    resolvedEventsError: (s) => s.resolvedEventsErrors[s.activeId] || null,
    active: (s) => s.conversations.find((c) => c.id === s.activeId),
    filtered: (s) =>
      s.conversations.filter(
        (c) =>
          (s.filter === "all" || c.status === s.filter) &&
          (!s.query ||
            `${c.name}${c.order}${c.preview}`
              .toLowerCase()
              .includes(s.query.toLowerCase())),
      ),
  },
  actions: {
    persistConversations() {
      if (typeof localStorage === "undefined") return;
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.conversations));
      clearTimeout(persistenceTimer);
      persistenceTimer = setTimeout(() => {
        workspaceStateApi
          .save({ conversations: this.conversations })
          .catch(() => {});
      }, 250);
    },
    initSync(viewMode) {
      this.viewMode = viewMode;
      const receivePayload = (data) => {
        if (!data || data.source === this.syncId) return;

        const conversation = this.conversations.find(
          (item) => item.id === data.conversationId,
        );
        if (!conversation) return;
        if (!acceptsHistoryEvent(conversation, data)) return;

        if (data.type === "message") {
          if (conversation.messages.some((item) => item.id === data.message.id))
            return;
          conversation.messages.push(data.message);
          this.markResolvedEventsDirty(conversation.id);
          conversation.preview = data.message.text;
          conversation.time = data.message.time;
          if (data.message.from === "user") {
            const isOpenInDesk =
              this.viewMode === "agent" && this.activeId === conversation.id && (typeof document === 'undefined' || !document.hidden);
            conversation.unread = isOpenInDesk
              ? 0
              : (conversation.unread || 0) + 1;
            conversation.aiHidden = false;
            conversation.needsAnalysis = true;
            conversation.needsSummary = true;
            this.queuedReplyMessageIds[conversation.id] = data.message.id;
            if (isOpenInDesk) {
              this.suggestion = "";
              this.suggestionMessageId = null;
            }
            if (this.viewMode === "agent") {
              void this.refreshAssistance(conversation.id);
            }
          }
        }

        if (data.type === "status") conversation.status = data.status;
        if (data.type === "read" && this.viewMode === 'agent' && data.agentId === useReceptionStore().agent?.id) conversation.unread = 0;
        this.persistConversations();
      };

      if (!syncChannel && typeof BroadcastChannel !== "undefined") {
        syncChannel = new BroadcastChannel("beautycare-service-os-demo");
      }
      if (syncChannel) syncChannel.onmessage = ({ data }) => receivePayload(data);

      if (!storageListener && typeof window !== "undefined") {
        storageListener = (event) => {
          if (event.key !== STORAGE_KEY || !event.newValue) return;
          try {
            const remoteConversations = JSON.parse(event.newValue);
            if (!Array.isArray(remoteConversations)) return;
            remoteConversations.forEach((remoteConversation) => {
              (remoteConversation.messages || []).forEach((message) => {
                if (!message.id) return;
                receivePayload({
                  type: "message",
                  conversationId: remoteConversation.id,
                  historyRevision: remoteConversation.historyRevision,
                  message,
                });
              });
            });
          } catch {}
        };
        window.addEventListener("storage", storageListener);
      }
    },
    broadcast(payload) {
      const conversation = this.conversations.find(item => item.id === payload.conversationId);
      syncChannel?.postMessage(JSON.parse(JSON.stringify({ ...payload, historyRevision: conversation?.historyRevision, source: this.syncId })));
    },
    select(id) {
      this.activeId = id;
      if (typeof sessionStorage !== "undefined") {
        sessionStorage.setItem(ACTIVE_CONVERSATION_KEY, String(id));
      }
      this.markRead(id);
      this.suggestion = "";
      this.suggestionMessageId = null;
      if (this.active.needsAnalysis && this.viewMode === "agent") {
        const latestUserMessageId = [...this.active.messages]
          .reverse()
          .find((message) => message.from === "user")?.id;
        if (latestUserMessageId) {
          this.queuedReplyMessageIds[id] = latestUserMessageId;
        }
        this.refreshAssistance(id);
      } else if (this.active.needsSummary && this.viewMode === "agent") {
        this.refreshSummaries(id);
      }
      if (this.viewMode === "agent") {
        const latestUserMessageId = [...this.active.messages]
          .reverse().find((message) => message.from === "user")?.id;
        if (latestUserMessageId) void this.refreshReplyDraft(id, latestUserMessageId);
        void this.refreshResolvedEvents(id);
      }
      this.persistConversations();
    },
    markRead(id = this.activeId) {
      const conversation = this.conversations.find((item) => item.id === id);
      if (conversation) {
        conversation.unread = 0;
        this.persistConversations();
        const reception = useReceptionStore();
        if (this.viewMode === 'agent' && reception.agent?.id) {
          const messageId = [...conversation.messages].reverse().find(message => message.from === 'user' && (!conversation.reception?.id || message.sessionId === conversation.reception.id))?.id;
          void reception.markRead(id, messageId).catch(() => {});
          this.broadcast({ type: "read", conversationId: id, agentId: reception.agent.id });
        }
      }
    },
    async hydrate() {
      try {
        const { data } = await workspaceStateApi.load();
        if (Array.isArray(data?.conversations) && data.conversations.length) {
          this.conversations = data.conversations.map((savedConversation) => {
            const conversation = withInferredMessageDates(savedConversation);
            return {
              ...conversation,
              baseTag: conversation.baseTag || conversation.tag,
              riskEvidence: removeRawMessageEvidence(conversation),
            };
          });
          localStorage.setItem(STORAGE_KEY, JSON.stringify(this.conversations));
          return;
        }
      } catch {}
      try {
        const { data } = await chatApi.list();
        if (data?.length) {
          this.conversations = data.map((savedConversation) => {
            const conversation = withInferredMessageDates(savedConversation);
            return {
              ...conversation,
              baseTag: conversation.baseTag || conversation.tag,
            };
          });
          this.persistConversations();
        }
      } catch {}
    },
    async initializeWorkspace() {
      const activeConversation = this.conversations.find(
        (item) => item.id === this.activeId,
      );
      const queue = [
        activeConversation,
        ...this.conversations.filter((item) => item.id !== this.activeId),
      ].filter(Boolean);

      await Promise.allSettled(
        queue.map((conversation) => {
          if (conversation.needsAnalysis) {
            return this.refreshAssistance(conversation.id);
          }
          if (conversation.needsSummary) {
            return this.refreshSummaries(conversation.id);
          }
          return Promise.resolve();
        }),
      );
    },
    async initializeResolvedEvents() {
      const activeConversation = this.conversations.find(
        (item) => item.id === this.activeId,
      );
      const queue = [
        activeConversation,
        ...this.conversations.filter((item) => item.id !== this.activeId),
      ].filter(Boolean);

      await Promise.allSettled(
        queue.map((conversation) =>
          this.refreshResolvedEvents(conversation.id),
        ),
      );
    },
    markResolvedEventsDirty(id) {
      this.resolvedEventsDirtyVersions[id] =
        (this.resolvedEventsDirtyVersions[id] || 0) + 1;
      if (this.viewMode === "agent") {
        this.scheduleResolvedEventsRefresh(id);
      }
    },
    scheduleResolvedEventsRefresh(id) {
      if (resolvedEventsRefreshTimers.has(id)) return;
      const timer = setTimeout(async () => {
        resolvedEventsRefreshTimers.delete(id);
        if (
          this.viewMode !== "agent" ||
          !this.resolvedEventsDirtyVersions[id]
        ) {
          return;
        }
        await this.refreshResolvedEvents(id, true);
        if (this.resolvedEventsDirtyVersions[id]) {
          this.scheduleResolvedEventsRefresh(id);
        }
      }, RESOLVED_EVENTS_REFRESH_DELAY);
      resolvedEventsRefreshTimers.set(id, timer);
    },
    startResolvedEventsAutoRefresh() {
      Object.keys(this.resolvedEventsDirtyVersions).forEach((id) =>
        this.scheduleResolvedEventsRefresh(Number(id)),
      );
    },
    stopResolvedEventsAutoRefresh() {
      resolvedEventsRefreshTimers.forEach((timer) => clearTimeout(timer));
      resolvedEventsRefreshTimers.clear();
    },
    async send(text, from = "agent", attachment = null, product = null) {
      if (attachment && text?.trim()) {
        await Promise.all([this.send("", from, attachment), this.send(text, from)]);
        return;
      }
      if (!text?.trim() && !attachment) return;
      const conversationId = this.activeId;
      const msg = {
        id: crypto.randomUUID(),
        from,
          ...(product && from === 'agent' ? { product } : {}),
        text: text?.trim() || `[附件] ${attachment.name}`,
        ...(attachment ? { attachment, image: /^image\/(png|jpeg|gif|webp|bmp)$/.test(attachment.type) } : {}),
        sentAt: new Date().toISOString(),
        time: new Date().toLocaleTimeString("zh-CN", {
          hour: "2-digit",
          minute: "2-digit",
        }),
      };
      const reception = useReceptionStore();
      if (!reception.ready) throw new Error("接待服务尚未连接，请等待或刷新页面");
      const savedMessage = await reception.send(conversationId, msg);
      Object.assign(msg, savedMessage);
      const target = this.conversations.find((item) => item.id === conversationId);
      if (!target.messages.some((item) => item.id === msg.id)) target.messages.push(msg);
      this.markResolvedEventsDirty(conversationId);
      target.preview = msg.text;
      if (from === "user") {
        target.unread = (target.unread || 0) + 1;
        target.aiHidden = false;
        target.needsAnalysis = true;
      }
      target.needsSummary = true;
      this.broadcast({
        type: "message",
        conversationId,
        message: msg,
      });
      this.persistConversations();
      if (from === "agent" && this.viewMode === "agent") {
        void this.refreshSummaries(conversationId);
      }
    },
    buildAssistanceContext(conversation) {
      const latestUserMessage = [...conversation.messages]
        .reverse()
        .find((message) => message.from === "user");
      const recentRoleMessages = [
        ...conversation.messages.filter((message) => message.from === "user").slice(-6),
        ...conversation.messages.filter((message) => message.from === "agent" && !isReceptionNotice(message)).slice(-6),
      ].sort(
        (left, right) =>
          conversation.messages.indexOf(left) - conversation.messages.indexOf(right),
      );
      return {
        conversationId: conversation.id,
        latestMessage: latestUserMessage?.text || conversation.preview,
        previousSummary: "",
        messages: recentRoleMessages.map((message) => ({
          id: message.id,
          role: message.from,
          text: message.text,
          time: message.time,
          sentAt: message.sentAt,
          image: Boolean(message.image),
        })),
        order: {
          orderNo: conversation.order,
          orderedSku: conversation.sku,
          receivedSku: conversation.received,
        },
        customer: {
          name: conversation.name,
          traits: conversation.traits,
        },
        workOrders: [],
        commitments: (conversation.commitments || []).filter(task => task.status === 'OPEN'),
      };
    },
    buildResolvedEventsContext(conversation) {
      const context = this.buildAssistanceContext(conversation);
      const cutoff = Date.now() - 5 * 24 * 60 * 60 * 1000;
      context.messages = conversation.messages
        .filter((message) => !isReceptionNotice(message))
        .filter((message) => {
          if (!message.sentAt) return true;
          const timestamp = new Date(message.sentAt).getTime();
          return Number.isFinite(timestamp) && timestamp >= cutoff;
        })
        .map((message) => ({
          role: message.from,
          text: message.text,
          time: message.time,
          sentAt: message.sentAt,
          image: Boolean(message.image),
        }));
      return context;
    },
    applyContextResult(conversation, contextResult) {
      if (contextResult.customerTags?.length) {
        conversation.traits = contextResult.customerTags;
      }
      conversation.contextSummary = contextResult.contextSummary;
      conversation.unresolvedEvents = Array.isArray(contextResult.unresolvedEvents) ? contextResult.unresolvedEvents : [];
      conversation.unresolvedEventsUpdatedAt = new Date().toISOString();
      const reception = useReceptionStore();
      if (reception.agent?.id && conversation.reception?.agentId === reception.agent.id && conversation.reception?.status !== 'closed') {
        const messageId = [...conversation.messages].reverse().find(message => message.from === 'user')?.id;
        const immediateTodo = typeof contextResult.immediateHandlingRequired === 'boolean' ? contextResult.immediateHandlingRequired
          : conversation.unresolvedEvents.some(item => !/等待用户|等用户|待用户/.test(typeof item === 'string' ? item : item.description || ''));
        void reception.reportAnalysis(conversation.id, { messageId, immediateTodo }).catch(() => {});
      }
      const hasWorkflowStatus = Boolean(contextResult.workflowStatus?.trim());
      if (hasWorkflowStatus) conversation.workflowStatus = contextResult.workflowStatus.trim();
      const hasGeneratedSummary = true;
      conversation.summaryVersion = 5;
      conversation.needsSummary = !hasWorkflowStatus;
      conversation.timeline = contextResult.timeline;
      conversation.trajectoryVersion = 5;
      conversation.statusVersion = hasWorkflowStatus ? 3 : 0;
      return hasGeneratedSummary;
    },
    focusMessage(messageId) {
      if (!messageId) return;
      this.messageFocusRequest = {
        conversationId: this.activeId,
        messageId,
        nonce: Date.now(),
      };
    },
    async refreshResolvedEvents(id = this.activeId, force = false) {
      const conversation = this.conversations.find((item) => item.id === id);
      if (!conversation) return;
      if (!force && this.resolvedEventsInitializedIds.includes(id)) return;
      if (this.resolvedEventsPendingIds.includes(id)) return;

      this.resolvedEventsPendingIds.push(id);
      const dirtyVersion = this.resolvedEventsDirtyVersions[id] || 0;
      delete this.resolvedEventsErrors[id];
      try {
        const context = this.buildResolvedEventsContext(conversation);
        const messagesByDay = new Map();
        context.messages.forEach((message) => {
          const date = message.sentAt ? new Date(message.sentAt) : null;
          const dayKey =
            date && !Number.isNaN(date.getTime())
              ? `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`
              : "日期待确认";
          if (!messagesByDay.has(dayKey)) messagesByDay.set(dayKey, []);
          messagesByDay.get(dayKey).push(message);
        });

        const previousResolvedEvents = conversation.resolvedEvents || [];
        const eventDayKey = (event) => {
          if (!event?.completedAt) return "日期待确认";
          const date = new Date(event.completedAt);
          return Number.isNaN(date.getTime())
            ? "日期待确认"
            : `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
        };
        const outcomes = await Promise.allSettled(
          [...messagesByDay.entries()].map(([dayKey, messages]) => {
            const previousForDay = previousResolvedEvents.filter(
              (event) => eventDayKey(event) === dayKey,
            );
            return assistanceWorkflowApi.buildResolvedEvents({
              ...context,
              messages,
              previousSummary: JSON.stringify({
                resolvedEvents: previousForDay,
              }),
            });
          }),
        );
        const completedEvents = outcomes
          .filter((outcome) => outcome.status === "fulfilled")
          .flatMap((outcome) => outcome.value.data.resolvedEvents || []);
        if (outcomes.length && !outcomes.some((outcome) => outcome.status === "fulfilled")) {
          throw outcomes[0]?.reason || new Error("已处理事件总结失败");
        }

        const uniqueEvents = new Map();
        completedEvents.forEach((event) => {
          const key = `${event.userRequest}|${event.serviceResolution}|${event.completedAt}`;
          uniqueEvents.set(key, event);
        });
        conversation.resolvedEvents = [...uniqueEvents.values()].sort(
          (left, right) =>
            new Date(left.completedAt || 0).getTime() -
            new Date(right.completedAt || 0).getTime(),
        );
        conversation.resolvedEventsUpdatedAt = new Date().toISOString();
        if (!this.resolvedEventsInitializedIds.includes(id)) {
          this.resolvedEventsInitializedIds.push(id);
        }
        if (this.resolvedEventsDirtyVersions[id] === dirtyVersion) {
          delete this.resolvedEventsDirtyVersions[id];
        }
        this.persistConversations();
      } catch (error) {
        this.resolvedEventsErrors[id] =
          error.code === "ECONNABORTED"
            ? "已处理事件总结超时，请手动刷新"
            : error.response?.data?.message ||
              error.message ||
              "已处理事件总结失败";
      } finally {
        this.resolvedEventsPendingIds = this.resolvedEventsPendingIds.filter(
          (pendingId) => pendingId !== id,
        );
      }
    },
    beginConversationAnalysis(id) {
      if (this.analysisPendingIds.includes(id)) return false;
      this.analysisPendingIds.push(id);
      this.analysisLoading = true;
      return true;
    },
    finishConversationAnalysis(id) {
      this.analysisPendingIds = this.analysisPendingIds.filter(
        (pendingId) => pendingId !== id,
      );
      this.analysisLoading = this.analysisPendingIds.length > 0;
    },
    async refreshSummaries(id = this.activeId) {
      return this.refreshContextEvents(id);
    },
    async refreshContextEvents(id = this.activeId) {
      const conversation = this.conversations.find((item) => item.id === id);
      if (!conversation) return;
      const revision = analysisRevision(conversation);
      if (this.contextRefreshVersions[id] === revision) return;
      this.contextRefreshVersions[id] = revision;
      if (!this.contextRefreshIds.includes(id)) this.contextRefreshIds.push(id);
      delete this.contextRefreshErrors[id];
      try {
        const { data } = await assistanceWorkflowApi.buildContext(
          this.buildAssistanceContext(conversation),
        );
        if (this.contextRefreshVersions[id] !== revision || analysisRevision(conversation) !== revision) return;
        if (!Array.isArray(data.unresolvedEvents)) throw new Error('摘要结果格式无效，请重试');
        this.applyContextResult(conversation, data);
        this.persistConversations();
      } catch (error) {
        if (this.contextRefreshVersions[id] !== revision || analysisRevision(conversation) !== revision) return;
        this.contextRefreshErrors[id] =
          error.code === "ECONNABORTED"
            ? "AI 摘要生成超时，请重试"
            : error.response?.data?.message || error.message || "摘要生成失败，请稍后重试";
      } finally {
        if (this.contextRefreshVersions[id] === revision) {
          delete this.contextRefreshVersions[id];
          this.contextRefreshIds = this.contextRefreshIds.filter(item => item !== id);
        }
      }
    },
    async refreshAssistance(id = this.activeId) {
      const conversation = this.conversations.find((item) => item.id === id);
      const reception = useReceptionStore();
      if (this.viewMode === 'agent' && !reception.ready) return;
      if (conversation?.reception && (conversation.reception.mode === 'BOT' || conversation.reception.agentId !== reception.agent?.id || conversation.reception.status === 'closed')) return;
      const latestUserMessageId = [...(conversation?.messages || [])]
        .reverse()
        .find((message) => message.from === "user")?.id;
      if (latestUserMessageId && !this.queuedReplyMessageIds[id]) {
        this.queuedReplyMessageIds[id] = latestUserMessageId;
      }
      if (!conversation) return;
      void this.refreshContextEvents(id);
      if (this.activeId === id) void this.refreshReplyDraft(id, latestUserMessageId);
      // Reception now analyzes every incoming turn server-side, including unselected chats.
      // Do not issue a second risk request or overwrite that result from a click-triggered path.
      if (conversation.reception?.emotionAnalysisOwner === 'server') {
        conversation.needsAnalysis = Boolean(conversation.reception.emotionAnalysisPending);
        if (this.queuedReplyMessageIds[id] === latestUserMessageId) delete this.queuedReplyMessageIds[id];
        return;
      }
      if (!conversation || !this.beginConversationAnalysis(id)) return;
      const analyzedUserMessageId =
        this.queuedReplyMessageIds[id] || latestUserMessageId;
      conversation.baseTag ||= conversation.tag;

      this.analysisError = null;
      const context = this.buildAssistanceContext(conversation);
      const revision = analysisRevision(conversation);
      const isCurrent = () => analysisRevision(conversation) === revision;
      const riskPromise = assistanceWorkflowApi.analyzeRisk(context).then(({ data: riskResult }) => {
        if (!isCurrent()) return;

        conversation.risk = riskResult.riskScore;
        conversation.emotion = riskResult.emotion;
        void reception.reportAnalysis(id, { messageId: analyzedUserMessageId, emotionScore: riskResult.emotionScore }).catch(() => {});
        if (Number.isFinite(riskResult.emotionScore)) {
          conversation.emotionScore = riskResult.emotionScore;
          conversation.emotionHistory ||= [];
          conversation.emotionHistory.push({
            time: new Date().toLocaleTimeString("zh-CN", {
              hour: "2-digit",
              minute: "2-digit",
            }),
            score: riskResult.emotionScore,
          });
          conversation.emotionHistory = conversation.emotionHistory.slice(-6);
          conversation.analysisVersion = 4;
        }
        conversation.queueScore = riskResult.queueScore;
        conversation.handoffRequired = riskResult.handoffRequired;
        conversation.riskEvidence = removeRawMessageEvidence({
          ...conversation,
          riskEvidence: riskResult.evidence,
        });
        const latestUserMessageId = [...conversation.messages]
          .reverse()
          .find((message) => message.from === "user")?.id;
        conversation.needsAnalysis =
          latestUserMessageId !== analyzedUserMessageId;

        const hasSafetyRisk = conversation.riskEvidence.some((evidence) =>
          /安全|过敏|就医|不良反应/.test(evidence),
        );
        const currentNegativeEmotion = ["negative", "critical"].includes(riskResult.emotion);
        conversation.dynamicTag = riskResult.handoffRequired && (currentNegativeEmotion || hasSafetyRisk)
          ? "高风险 · 建议人工接管"
          : riskResult.emotion === "critical"
            ? "情绪升级 · 持续关注"
            : riskResult.emotion === "negative"
              ? "负面情绪 · AI持续分析"
              : null;
        this.persistConversations();
      });
      try {
        const outcomes = await Promise.allSettled([riskPromise]);
        const failed = outcomes.find(item => item.status === 'rejected');
        if (failed && isCurrent()) throw failed.reason;
      } catch (error) {
        this.analysisError =
          error.code === "ECONNABORTED"
            ? "AI 分析超时，请点击重新分析"
            : error.response?.data?.message || error.message || "AI分析失败";
      } finally {
        if (this.queuedReplyMessageIds[id] === analyzedUserMessageId) {
          delete this.queuedReplyMessageIds[id];
        }
        this.finishConversationAnalysis(id);
        if (
          this.queuedReplyMessageIds[id] &&
          this.viewMode === "agent"
        ) {
          queueMicrotask(() => this.refreshAssistance(id));
        }
      }
    },
    async refreshReplyDraft(id, requestedUserMessageId) {
      const conversation = this.conversations.find((item) => item.id === id);
      const reception = useReceptionStore();
      if (conversation?.reception && (conversation.reception.mode === 'BOT' || conversation.reception.agentId !== reception.agent?.id || conversation.reception.status === 'closed')) return;
      if (!conversation || !requestedUserMessageId) return;
      if (this.replyRequestedMessageIds[id] === requestedUserMessageId) return;

      this.replyRequestedMessageIds[id] = requestedUserMessageId;
      if (!this.replyPendingIds.includes(id)) this.replyPendingIds.push(id);
      const context = this.buildAssistanceContext(conversation);

      try {
        const { data } =
          await assistanceWorkflowApi.generateReplyDraft(context);
        const latestUserMessageId = [...conversation.messages]
          .reverse()
          .find((message) => message.from === "user")?.id;
        if (
          this.activeId === id &&
          latestUserMessageId === requestedUserMessageId &&
          this.replyRequestedMessageIds[id] === requestedUserMessageId
        ) {
          this.suggestion = data.draft;
          this.suggestionMessageId = requestedUserMessageId;
        }
      } catch (error) {
        if (this.replyRequestedMessageIds[id] === requestedUserMessageId) {
          this.analysisError =
            error.code === "ECONNABORTED"
              ? "AI 回复生成超时，请点击生成"
              : error.response?.data?.message ||
                error.message ||
                "回复生成失败";
        }
      } finally {
        if (this.replyRequestedMessageIds[id] === requestedUserMessageId) {
          this.replyPendingIds = this.replyPendingIds.filter(
            (pendingId) => pendingId !== id,
          );
        }
        this.persistConversations();
      }
    },
    async regenerate() {
      const conversation = this.active;
      const reception = useReceptionStore();
      if (conversation?.reception && !['bot', 'waiting'].includes(conversation.reception.status) && (conversation.reception.agentId !== reception.agent?.id || conversation.reception.status === 'closed')) return;
      if (!conversation || this.replyGenerating) return;
      const requestedUserMessageId = [...conversation.messages]
        .reverse()
        .find((message) => message.from === "user")?.id;
      this.replyGenerating = true;
      this.analysisError = null;
      try {
        const { data } = await assistanceWorkflowApi.generateReplyDraft(
          this.buildAssistanceContext(conversation),
        );
        const latestUserMessageId = [...conversation.messages]
          .reverse()
          .find((message) => message.from === "user")?.id;
        if (latestUserMessageId === requestedUserMessageId) {
          this.suggestion = data.draft;
          this.suggestionMessageId = requestedUserMessageId;
        }
      } catch (error) {
        this.analysisError =
          error.code === "ECONNABORTED"
            ? "AI 回复生成超时，请重新生成"
            : error.response?.data?.message || error.message || "回复生成失败";
      } finally {
        this.replyGenerating = false;
      }
    },
    setStatus(status) {
      this.active.status = status;
      this.persistConversations();
      this.broadcast({
        type: "status",
        conversationId: this.activeId,
        status,
      });
    },
  },
});
