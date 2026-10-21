<script setup>
import { computed, ref, watch } from "vue";
import { Clock, Refresh } from "@element-plus/icons-vue";
import { useChatStore } from "../store/chat";
import { formatMessageTime } from "../utils/messageTime";
import CustomerProfile from './CustomerProfile.vue';
import CommitmentList from './CommitmentList.vue';
import { emotionBand } from '../utils/receptionDisplay';
import { updateProfileNotice } from '../utils/profileNotifications';
import { emotionDisplay } from '../utils/emotionDisplay';

const store = useChatStore();
const activeTab = ref('focus');
const profileNotices = ref({});
const profileUnread = computed(() => Boolean(profileNotices.value[store.activeId]?.unread));
function profileUpdated({ conversationId, facts }) {
  profileNotices.value[conversationId] = updateProfileNotice(profileNotices.value[conversationId], facts,
    conversationId === store.activeId && activeTab.value === 'profile');
}
watch(activeTab, tab => {
  if (tab === 'profile' && profileNotices.value[store.activeId]) profileNotices.value[store.activeId].unread = false;
});
const assistantTabs = [
  { id: 'focus', label: '接待重点' },
  { id: 'profile', label: '用户画像' },
  { id: 'records', label: '服务记录' },
];
watch(() => store.activeId, () => { activeTab.value = 'focus'; });
function navigateTabs(event, index) {
  const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
  if (!keys.includes(event.key)) return;
  event.preventDefault();
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? assistantTabs.length - 1
    : (index + (event.key === 'ArrowRight' ? 1 : -1) + assistantTabs.length) % assistantTabs.length;
  activeTab.value = assistantTabs[next].id;
  event.currentTarget.parentElement.querySelectorAll('[role="tab"]')[next]?.focus();
}
const completedAtTimestamp = (value) => {
  if (!value) return Number.MAX_SAFE_INTEGER;
  const parsed = new Date(value).getTime();
  if (!Number.isNaN(parsed)) return parsed;
  const match = String(value).match(/^(\d{1,2}):(\d{2})$/);
  if (!match) return Number.MAX_SAFE_INTEGER;
  const date = new Date();
  date.setHours(Number(match[1]), Number(match[2]), 0, 0);
  return date.getTime();
};
const resolvedEvents = computed(() =>
  (store.active?.resolvedEvents || [])
    .map((item) =>
      typeof item === "string"
        ? {
            userRequest: item,
            serviceResolution: "历史记录待重新分析",
            completedAt: "",
          }
        : item,
    )
    .sort(
      (left, right) =>
        completedAtTimestamp(left.completedAt) -
        completedAtTimestamp(right.completedAt),
    ),
);
const unresolvedEvents = computed(() => {
  const userMessages = (store.active?.messages || []).filter(
    (message) => message.from === "user",
  );
  const userMessageIds = new Set(userMessages.map((message) => message.id));
  const latestUserMessageId = [...userMessages]
    .reverse()
    .find((message) => message.id)?.id;
  return (store.active?.unresolvedEvents || []).map((item) =>
    typeof item === "string"
      ? { description: item, sourceMessageId: latestUserMessageId }
      : {
          description: item.description || "",
          sourceMessageId: userMessageIds.has(item.sourceMessageId)
            ? item.sourceMessageId
            : latestUserMessageId,
        },
  );
});
const formatCompletedAt = (value) =>
  formatMessageTime({ sentAt: value, time: value });
const resolvedEventsUpdatedAt = computed(() =>
  store.active?.resolvedEventsUpdatedAt
    ? formatCompletedAt(store.active.resolvedEventsUpdatedAt)
    : "尚未更新",
);
const unresolvedEventsUpdatedAt = computed(() =>
  store.active?.unresolvedEventsUpdatedAt
    ? formatCompletedAt(store.active.unresolvedEventsUpdatedAt)
    : "尚未更新",
);
const summaryThinking = computed(() =>
  store.contextRefreshIds.includes(store.activeId),
);
const resolvedThinking = computed(() =>
  store.resolvedEventsPendingIds.includes(store.activeId),
);
const summaryEmptyText = computed(() => {
  if (summaryThinking.value) return "正在思考中";
  if (store.contextRefreshErrors[store.activeId]) return `生成失败：${store.contextRefreshErrors[store.activeId]}`;
  return "摘要尚未生成";
});
const refreshResolvedEvents = () =>
  store.refreshResolvedEvents(store.activeId, true);
const refreshUnresolvedEvents = () => store.refreshSummaries(store.activeId);
const emotionState = computed(() => emotionDisplay(store.active));
const emotionMeta = computed(() => {
  const score = emotionState.value.score;
  const level = score === null ? (store.active?.emotion || 'neutral') : ({ red: 'critical', yellow: 'negative', green: 'neutral' }[emotionBand(score)]);
  const meta = ({
  positive: { label: "平稳积极", color: "#55b98a" },
  neutral: { label: "情绪平稳", color: "#55b98a" },
  negative: { label: "负面情绪", color: "#e0a13a" },
  critical: { label: "情绪激烈", color: "#e45b62" },
  }[level]);
  return {
    ...meta,
    score,
  };
});
const emotionHistory = computed(() => emotionState.value.history);
const chartDots = computed(() => emotionHistory.value.map((item, index, list) => ({
  ...item,
  x: list.length === 1 ? 130 : 8 + (index * 244) / (list.length - 1),
  y: 96 - item.score * 0.76,
})));
const chartPoints = computed(() => chartDots.value.map((dot) => `${dot.x},${dot.y}`).join(" "));
const recentOrders = computed(() => {
  if (store.active?.recentOrders?.length) return store.active.recentOrders;
  if (!store.active) return [];
  return [{ orderNo: store.active.order, product: `美妆商品 · 规格 #${store.active.sku}`, status: store.active.sku === store.active.received ? "订单已完成" : "售后处理中" }];
});
</script>

<template>
  <aside class="ai-manager case-overview">
    <div class="ai-manager-head">
      <div><h2>服务助手</h2><small>{{ store.active?.name }} · 实时更新</small></div>
      <span><i></i>AI 分析中</span>
    </div>
    <nav class="assistant-tabs" role="tablist" aria-label="服务助手分类">
      <button v-for="(tab, index) in assistantTabs" :id="'assistant-tab-' + tab.id" :key="tab.id"
        type="button" role="tab" :aria-selected="activeTab === tab.id" :aria-controls="'assistant-panel-' + tab.id"
        :tabindex="activeTab === tab.id ? 0 : -1" :class="{ selected: activeTab === tab.id }"
        @click="activeTab = tab.id" @keydown="navigateTabs($event, index)">{{ tab.label }}<span v-if="tab.id === 'profile' && profileUnread" class="profile-update-dot" role="status" aria-label="用户画像有新内容" title="用户画像有新内容"></span></button>
    </nav>
    <div class="overview-sections">
      <div v-show="activeTab === 'focus'" id="assistant-panel-focus" class="assistant-panel" role="tabpanel" aria-labelledby="assistant-tab-focus" tabindex="0">
      <section class="overview-card processing-card unresolved-card">
        <header><span class="section-index">01</span><div><h3>未处理事件</h3></div><span class="section-updated-at">{{ summaryThinking ? '更新中...' : `更新于 ${unresolvedEventsUpdatedAt}` }}</span><el-button class="section-refresh" :icon="Refresh" circle size="small" :loading="summaryThinking" title="重新分析未处理事件" @click="refreshUnresolvedEvents" /></header>
        <p v-if="summaryThinking && !unresolvedEvents.length" class="empty-state">正在思考中</p>
        <div v-else-if="unresolvedEvents.length" class="summary-list">
          <div v-for="(item, index) in unresolvedEvents" :key="`${index}-${item.description}`" class="summary-row unresolved-summary clickable" title="定位到对应的用户消息" @click="store.focusMessage(item.sourceMessageId)">
            <span>!</span>
            <p>{{ item.description }}</p>
          </div>
        </div>
        <p v-else class="empty-state">{{ store.contextRefreshErrors[store.activeId] ? summaryEmptyText : '暂无未处理事件' }}</p>
        <p v-if="unresolvedEvents.length && store.contextRefreshErrors[store.activeId]" class="empty-state">更新失败，保留上次结果：{{ store.contextRefreshErrors[store.activeId] }}</p>
        <CommitmentList />
      </section>
      <section class="overview-card emotion-card">
        <header><span class="section-index">02</span><div><h3>用户情绪波动</h3><p>{{ emotionState.pending ? '正在分析新消息，曲线保留上次结果' : '数值越高表示负面情绪越强' }}</p></div><div class="emotion-score" :style="{ color: emotionMeta.color }"><small v-if="emotionState.pending">评分中…</small><template v-else><strong>{{ emotionMeta.score ?? "--" }}</strong><span v-if="emotionMeta.score !== null">分</span></template></div></header>
        <div class="emotion-chart">
          <span class="axis-label high">100</span><span class="axis-label low">0</span>
          <svg viewBox="0 0 260 104" preserveAspectRatio="none" aria-label="用户情绪波动图">
            <line x1="8" y1="20" x2="252" y2="20" class="grid-line"/><line x1="8" y1="58" x2="252" y2="58" class="grid-line"/><line x1="8" y1="96" x2="252" y2="96" class="grid-line"/>
            <polyline :points="chartPoints" fill="none" stroke="#6f6b78" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>
            <g v-for="(dot, index) in chartDots" :key="`${dot.time}-${dot.score}-${index}`">
              <circle v-if="index === chartDots.length - 1" :cx="dot.x" :cy="dot.y" r="8" fill="#6847f5" opacity="0.16"/>
              <circle :cx="dot.x" :cy="dot.y" :r="index === chartDots.length - 1 ? 4.5 : 3.5" :fill="index === chartDots.length - 1 ? '#6847f5' : '#6f6b78'" stroke="white" stroke-width="1.8"/>
            </g>
          </svg>
          <div v-if="emotionHistory.length" class="chart-times" :class="{ 'single-time': emotionHistory.length === 1 }">
            <span>{{ emotionHistory[0].time }}</span>
            <span v-if="emotionHistory.length > 1">{{ emotionHistory.at(-1).time }}</span>
          </div>
        </div>
      </section>
      </div>
      <div v-show="activeTab === 'profile'" id="assistant-panel-profile" class="assistant-panel" role="tabpanel" aria-labelledby="assistant-tab-profile" tabindex="0">
        <CustomerProfile @profile-update="profileUpdated" />
      </div>
      <div v-show="activeTab === 'records'" id="assistant-panel-records" class="assistant-panel" role="tabpanel" aria-labelledby="assistant-tab-records" tabindex="0">
      <section class="overview-card event-card resolved-card">
        <header><span class="section-index">01</span><div><h3>已处理事件</h3></div><span class="section-updated-at">{{ resolvedThinking ? '更新中...' : `更新于 ${resolvedEventsUpdatedAt}` }}</span><el-button class="section-refresh" :icon="Refresh" circle size="small" :loading="resolvedThinking" title="重新总结近5天已处理事件" @click="refreshResolvedEvents" /></header>
        <div class="summary-list">
          <div v-if="resolvedThinking && !resolvedEvents.length" class="summary-empty"><p class="empty-state">正在思考中</p></div>
          <template v-else>
            <div v-for="(item, index) in resolvedEvents" :key="`${index}-${item.completedAt}-${item.userRequest}`" class="summary-row resolved-summary">
              <span>✓</span>
              <div class="resolved-content">
                <p>{{ item.userRequest }}，{{ item.serviceResolution }}</p>
                <time><el-icon><Clock /></el-icon>{{ item.completedAt ? formatCompletedAt(item.completedAt) : '时间待确认' }}</time>
              </div>
            </div>
            <div v-if="!resolvedEvents.length" class="summary-empty"><p class="empty-state">{{ store.resolvedEventsError || '近5天暂无已处理事件' }}</p></div>
          </template>
        </div>
      </section>
      <section class="overview-card orders-card">
        <header><span class="section-index">02</span><div><h3>最近订单</h3></div></header>
        <div class="order-list">
          <div v-for="order in recentOrders" :key="order.orderNo" class="order-row"><div class="product-thumb">妆</div><div class="order-detail"><b>{{ order.product }}</b><span>订单 {{ order.orderNo }}</span></div><em>{{ order.status }}</em></div>
          <p v-if="!recentOrders.length" class="empty-state">暂无近期订单</p>
        </div>
      </section>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.case-overview { display: flex; flex-direction: column; overflow: hidden; }
.ai-manager-head { flex-shrink: 0; }
.assistant-tabs { display: flex; flex-shrink: 0; border-bottom: 2px solid #e5e3ec; margin-top: 18px; }
.assistant-tabs button { flex: 1; min-width: 0; padding: 13px 2px; margin-bottom: -2px; border: 0; border-bottom: 3px solid transparent; background: transparent; color: #4b4654; font: inherit; font-size: 14px; font-weight: 650; cursor: pointer; white-space: nowrap; }
.assistant-tabs button.selected { color: var(--purple, #6948f5); border-bottom-color: var(--purple, #6948f5); }
.profile-update-dot { display: inline-block; width: 7px; height: 7px; margin-left: 5px; border-radius: 50%; background: var(--purple, #6948f5); vertical-align: top; box-shadow: 0 0 0 2px #f8f8fb; }
.assistant-tabs button:hover { color: var(--purple, #6948f5); background: #f2effb; }
.assistant-tabs button:focus-visible { outline: 2px solid var(--purple, #6948f5); outline-offset: -4px; border-radius: 5px; }
.overview-sections { flex: 1; min-height: 0; overflow-y: auto; padding-right: 2px; }
.assistant-panel { display: flex; flex-direction: column; gap: 12px; }
.assistant-panel:focus-visible { outline: 2px solid var(--purple, #6948f5); outline-offset: -2px; }
.chart-times.single-time { justify-content: center; }
.emotion-score > small { color: var(--purple, #6948f5); }
</style>


