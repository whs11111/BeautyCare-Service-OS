<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { emotionBand, waitingSince, durationText, matchesConversation, unrepliedCount, sortConversations } from '../utils/receptionDisplay';
import { useReceptionStore } from "../store/reception";
import { Search } from "@element-plus/icons-vue";
import { ElMessage } from 'element-plus';
import { useChatStore } from "../store/chat";
const store = useChatStore();
const reception = useReceptionStore();
const isPinned = conversation => reception.pinnedIds.includes(String(conversation.id));
async function togglePin(conversation) {
  try { await reception.togglePin(conversation.id); }
  catch (error) { ElMessage.warning(error.response?.data?.message || error.message || '置顶保存失败，请重试'); }
}
const now = ref(Date.now());
let clock;
onMounted(() => { clock = setInterval(() => { now.value = Date.now(); }, 1000); });
onBeforeUnmount(() => clearInterval(clock));
const isBot = conversation => conversation.reception?.mode === 'BOT' && conversation.reception?.status !== 'closed';
const band = conversation => emotionBand(conversation.reception?.emotionScore ?? conversation.emotionScore ?? 0);
const waitLabel = conversation => {
  const since = waitingSince(conversation);
  return Number.isFinite(since) && conversation.reception?.status !== 'closed' ? `等待人工回复 ${durationText(now.value - since)}` : '';
};
const visibleConversations = computed(() => sortConversations(store.conversations.filter((conversation) => {
  if (!matchesConversation(conversation, store.query || '')) return false;
  const session = reception.customers.find((item) => item.id === conversation.id)?.session;
  return Boolean(reception.agent?.id) && (session?.agentId === reception.agent.id || ['bot', 'waiting'].includes(session?.status));
}), reception.pinnedIds));
const unreadTotal = computed(() =>
  visibleConversations.value.reduce(
    (total, conversation) => total + (conversation.unread || 0),
    0,
  ),
);
const emotionProgress = (conversation) => {
  const score = Number.isFinite(conversation.emotionScore)
    ? conversation.emotionScore
    : Number.isFinite(conversation.risk)
      ? conversation.risk
      : 0;
  return `${Math.max(0, Math.min(100, score)) * 2.7}deg`;
};
const currentRiskTag = (conversation) => {
  if (["positive", "neutral"].includes(conversation.emotion)
      && /高风险/.test(conversation.dynamicTag || "")
      && !(conversation.riskEvidence || []).some((item) => /安全|过敏|就医|不良反应/.test(item))) return "";
  return conversation.dynamicTag;
};
</script>
<template>
  <aside class="conversation-panel">
    <div class="panel-head">
      <h2>我的会话</h2>
      <span class="count" :class="{ hidden: unreadTotal === 0 }">
        {{ unreadTotal || "" }}
      </span>
    </div>
    <el-input
      v-model="store.query"
      :prefix-icon="Search"
      placeholder="搜索用户 / 对话 / 聊天记录 / 关键词"
      size="large"
    />
    <div class="conversation-scroll">
      <div
        v-for="c in visibleConversations"
        :key="c.id"
        class="conversation"
        :class="[
          `emotion-${c.emotion || (c.risk > 70 ? 'critical' : 'neutral')}`,
          isBot(c) ? 'reception-blue' : `reception-${band(c)}`,
          { selected: c.id === store.activeId },
        ]"
        :style="{ '--emotion-progress': emotionProgress(c) }"
        @click="store.select(c.id)"
      >
        <div class="avatar">{{ c.avatar }}</div>
        <div class="conversation-copy">
          <div class="row">
            <div class="customer-title"><strong>{{ c.name }}</strong><button class="pin-customer" :class="{ pinned: isPinned(c) }" :aria-pressed="isPinned(c)" :aria-label="`${isPinned(c) ? '取消置顶' : '置顶'}${c.name}`" :title="isPinned(c) ? '取消置顶' : `置顶重要客户（${reception.pinnedIds.length}/10）`" :disabled="Boolean(reception.pinningId)" @click.stop="togglePin(c)"><svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 2h10M8 14V5m-4 4 4-4 4 4" /></svg></button></div>
            <div class="conversation-meta">
              <time>{{ c.time }}</time>
              <span v-if="c.unread" class="unread" :title="`${c.unread}条未读消息`" :aria-label="`${c.unread}条未读消息`">{{ c.unread }}</span>
            </div>
          </div>
          <small class="reception-owner">{{ isBot(c) ? 'AI自动回复中' : c.reception?.status === 'waiting' ? '等待人工接待' : c.reception?.status === 'closed' ? '已结束' : c.reception?.mode === 'HUMAN_PENDING' ? '待人工回复' : c.reception?.status === 'processing' ? '处理中' : '接待中' }}</small>
          <small v-if="waitLabel(c)" class="waiting-duration">{{ waitLabel(c) }}</small>
          <small v-if="unrepliedCount(c)" class="unreplied-count">未人工回复 {{ unrepliedCount(c) }} 条</small>
          <small v-if="c.reception?.emotionAnalysisPending" class="emotion-pending">情绪分析中…</small>
          <div class="preview">{{ c.preview }}</div>
          <div class="case-tag" :class="{ red: /高风险|情绪升级/.test(currentRiskTag(c) || '') }">
            {{
              (isBot(c) ? '机器人服务中' : currentRiskTag(c)) ||
              c.workflowStatus ||
              (c.needsSummary ? "状态分析中" : c.baseTag || c.tag)
            }}
          </div>
        </div>
      </div>
      <div v-if="!visibleConversations.length" class="empty">{{ store.query ? '没有匹配的会话' : '暂无分配给您的会话' }}</div>
    </div>
  </aside>
</template>

<style scoped>
.conversation.reception-red { background: rgb(244 130 136 / 25%); --emotion-ring-color: #c92e3b; }
.conversation.reception-yellow { background: rgb(255 218 122 / 25%); --emotion-ring-color: #b47710; }
.conversation.reception-green { background: rgb(174 224 190 / 25%); --emotion-ring-color: #28824b; }
.conversation.reception-blue { background: rgb(171 207 255 / 25%); --emotion-ring-color: #3676c7; }
.conversation.selected { box-shadow: inset 4px 0 #6847f5, inset 0 0 0 1px #8c78df; }
.waiting-duration { display: block; margin-top: 5px; color: #6b3b18; font-weight: 600; font-size: 11px; }
.customer-title { display: flex; align-items: center; min-width: 0; gap: 5px; }
.pin-customer { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; padding: 2px; flex-shrink: 0; background: transparent; border: 1px solid transparent; color: #706b78; border-radius: 4px; cursor: pointer; }
.pin-customer:hover, .pin-customer:focus-visible { background: #fff9; border-color: #9f8bde; }
.pin-customer.pinned { background: #eee6ff; color: #6948f5; border-color: #ac96eb; }
.pin-customer:disabled { cursor: wait; }
.unreplied-count { display: block; color: #8a321e; font-size: 11px; font-weight: 600; margin-top: 4px; }
.emotion-pending { display: block; color: #655f71; font-size: 11px; margin-top: 4px; }
</style>
