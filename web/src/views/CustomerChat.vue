<script setup>
import { computed, ref, nextTick, onMounted, onBeforeUnmount, watch } from "vue";
import { useReceptionStore } from '../store/reception';
import { startReceptionSync, mergeReceptionState } from "../utils/receptionSync";
import { ElMessage } from "element-plus";
let stopReception;
onBeforeUnmount(() => stopReception?.());
import { useRoute } from "vue-router";
import { ArrowLeft, Service, Plus, Download } from "@element-plus/icons-vue";
import { useChatStore } from "../store/chat";
import { formatMessageTime } from "../utils/messageTime";
import EmojiPicker from "../components/EmojiPicker.vue";
import AttachmentButton from "../components/AttachmentButton.vue";
import AttachmentDraft from "../components/AttachmentDraft.vue";
import MessageAttachment from "../components/MessageAttachment.vue";
import RobotPromotionCard from '../components/RobotPromotionCard.vue';
import ProductRecommendationCard from '../components/ProductRecommendationCard.vue';
import { messageProduct } from '../utils/productPresentation';
import RobotAvatar from '../components/RobotAvatar.vue';
import { serviceSender, isRobotMessage } from '../utils/serviceIdentity';
import { quizUrl } from '../utils/quizSelection';
import { insertEmoji } from "../utils/insertEmoji";
const store = useChatStore();
const reception = useReceptionStore();
const robotMode = computed(() => store.active?.reception?.mode === 'BOT' && store.active?.reception?.status !== 'closed');
async function openReception(id) {
  await reception.open(id);
  mergeReceptionState(store, reception);
}
const quickSend = async text => {
  try { await store.send(text, 'user'); }
  catch (error) { ElMessage.error(error.response?.data?.message || '发送失败，请重试'); }
};
watch(() => store.activeId, async id => {
  if (reception.ready) { try { await openReception(id); } catch { ElMessage.error('机器人接待未连接，请重新编译启动新版后端'); } }
});
watch(() => store.active?.name, (name) => {
  document.title = `${name || '用户'} · 用户咨询 | BeautyCare`;
}, { immediate: true, flush: "post" });
const route = useRoute();
const messagesRef = ref(null);
const selectCustomer = () => {
  const id = Number(route.params.id || 1);
  if (store.conversations.some((item) => item.id === id)) store.select(id);
};
selectCustomer();
watch(() => route.params.id, selectCustomer);
const scrollToLatest = async () => {
  await nextTick();
  requestAnimationFrame(() => {
    const messages = messagesRef.value;
    if (messages) messages.scrollTop = messages.scrollHeight;
  });
};
watch(
  [
    () => store.activeId,
    () => store.active?.messages?.length || 0,
    () => store.active?.messages?.at(-1)?.id || "",
  ],
  scrollToLatest,
  { flush: "post" },
);
onMounted(async () => {
  store.initSync("customer");
  await store.hydrate();
  selectCustomer();
  try { stopReception = await startReceptionSync(store); await openReception(store.activeId); }
  catch (error) { ElMessage.error(error.response?.status === 404 ? '当前后端仍为旧版，请重新编译启动server-v2/server' : '接待服务未连接，请重启新版后端并刷新页面'); }
  await scrollToLatest();
});
const input = ref("");
const inputRef = ref(null);
const pendingAttachment = ref([]);
const uploadingAttachment = ref(false);
watch(() => store.activeId, () => { pendingAttachment.value = []; });
const addEmoji = (emoji) => insertEmoji(input, inputRef, emoji);
const send = async () => {
  const v = input.value;
  if (uploadingAttachment.value || (!v.trim() && !pendingAttachment.value.length)) return;
  const attachment = pendingAttachment.value;
  pendingAttachment.value = [];
  input.value = "";
  const sends = [];
  attachment.forEach((file) => sends.push(store.send("", "user", file)));
  if (v.trim()) sends.push(store.send(v, "user"));
  try { await Promise.all(sends); }
  catch (error) { input.value = v; pendingAttachment.value = attachment; ElMessage.error(error.response?.data?.message || error.message || "发送失败"); }
  scrollToLatest();
};
</script>
<template>
  <div class="customer-page">
    <header class="customer-nav">
      <div class="client-brand">
        <span class="brand-mark"></span><b>BeautyCare</b>
      </div>
      <span>肌肤之美，值得被温柔回应</span>
      <div class="customer-nav-actions">
        <div class="customer-identity">
          <span>{{ store.active.avatar }}</span>
          <div>
            <b>{{ store.active.name }}</b>
          </div>
        </div>
        <router-link to="/login">切换身份</router-link>
      </div>
    </header>
    <main class="customer-card">
      <div class="customer-head">
        <router-link to="/login" aria-label="返回"
          ><el-icon><ArrowLeft /></el-icon
        ></router-link>
        <div class="service-avatar">
          <el-icon><Service /></el-icon>
        </div>
        <div>
          <h2>{{ store.active.reception?.mode === 'HUMAN' ? store.active.reception?.agentName : '客服机器人' }}</h2>
          <p><i></i> {{ robotMode ? 'AI自动回复中' : store.active.reception?.status === 'processing' ? '客服正在处理您的问题' : store.active.reception?.status === 'closed' ? '本次服务已结束，发送消息可再次咨询' : store.active.reception?.mode === 'HUMAN' ? '正在为您服务' : '正在等待人工客服回复' }}</p>
        </div>
        <button>订单 {{ store.active.order }}</button>
      </div>
      <div ref="messagesRef" class="customer-messages">
        <template v-for="(m, i) in store.active.messages" :key="m.id || i">
        <div v-if="m.sessionId && m.sessionId !== store.active.messages[i - 1]?.sessionId" class="customer-session-divider">{{ m.sessionId === store.active.reception?.id ? '本次咨询' : '历史咨询' }}</div>
        <div v-if="m.from === 'system'" class="system-message" :class="{ 'handoff-notice': m.kind === 'handoff' }">{{ m.text }}</div>
        <div v-else
          :key="i"
          class="client-msg"
          :class="m.from"
        >
          <div v-if="m.from === 'agent'" class="client-sender" :class="{ 'robot-sender': isRobotMessage(m) }"><RobotAvatar v-if="isRobotMessage(m)" /><span v-else>{{ m.agentAvatar || '客' }}</span>{{ serviceSender(m) }}</div>
          <ProductRecommendationCard v-if="messageProduct(m)" :product="messageProduct(m)" customer-facing />
          <RobotPromotionCard v-else-if="m.kind === 'demo-promotion' && m.card" :card="m.card" />
          <div v-else class="client-bubble" :class="{ 'image-only': m.attachment && /^image\/(png|jpeg|gif|webp|bmp)$/.test(m.attachment.type || '') }"><MessageAttachment v-if="m.attachment" :attachment="m.attachment" open-on-click /><template v-else>{{ m.text }}</template><p v-if="m.attachment && m.text !== `[附件] ${m.attachment.name}`" class="attachment-caption">{{ m.text }}</p></div>
          <small class="client-message-meta">{{ formatMessageTime(m) }}<a v-if="m.attachment && !/^image\/(png|jpeg|gif|webp|bmp)$/.test(m.attachment.type || '')" class="file-download-icon" :href="`${m.attachment.url}?download=true`" :download="m.attachment.name" aria-label="下载文件" title="下载文件"><el-icon><Download /></el-icon></a></small>
        </div>
        </template>
      </div>
      <div class="quick">
        <b v-if="robotMode" class="quick-heading">猜你想问</b>
        <a :href="quizUrl(store.activeId)" target="_blank" rel="noopener" class="quiz-entry">产品推荐 · 自测</a>
        <button @click="quickSend('我想咨询物流问题')">物流问题</button>
        <button @click="quickSend('查看最近上新')">查看最近上新</button>
        <button @click="quickSend('转人工')">转人工</button>
        <button @click="quickSend('查看我的订单')">查看我的订单</button>
      </div>
      <div class="client-composer">
        <EmojiPicker @select="addEmoji" />
        <AttachmentButton :count="pendingAttachment.length" @ready="pendingAttachment.push($event)" @uploading="uploadingAttachment = $event" /><div class="composer-edit-area">
        <div class="attachment-drafts"><AttachmentDraft v-for="(file, index) in pendingAttachment" :key="file.url" :attachment="file" @remove="pendingAttachment.splice(index, 1)" /></div>
        <el-input
          ref="inputRef"
          v-model="input"
          placeholder="请描述您的问题…"
          @keyup.enter="send"
        /></div><el-button type="primary" :disabled="uploadingAttachment || (!input.trim() && !pendingAttachment.length)" @click="send"
          >发送</el-button
        >
      </div>
    </main>
    <footer>BeautyCare 隐私保护 · 本次对话由 AI 辅助服务</footer>
  </div>
</template>

<style scoped>
.client-sender { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; font-size: 12px; color: #817b89; }
.client-sender > span { display: grid; place-items: center; width: 27px; height: 27px; background: #efe9ff; color: #6948f5; font-weight: 600; font-size: 11px; border-radius: 8px; }
.client-sender.robot-sender > span { background: white; }
.customer-session-divider { text-align: center; color: #a5a0ab; font-size: 11px; margin: 18px 0; }
.system-message.handoff-notice { background: #f0f0f3; color: #85818b; border-radius: 5px; }
.quick { flex-wrap: wrap; align-items: center; }
.quick-heading { font-size: 12px; color: #6e6a78; font-weight: 500; margin-right: 4px; }
.quiz-entry { display: inline-flex; align-items: center; padding: 6px 12px; border: 1px solid #d7cbfa; border-radius: 16px; color: #6948f5; background: #f5f1ff; font-size: 11px; white-space: nowrap; text-decoration: none; }
</style>
