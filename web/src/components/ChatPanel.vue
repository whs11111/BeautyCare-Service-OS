<script setup>
import { ref, nextTick, watch, computed } from "vue";
import { useChatStore } from "../store/chat";
import Timeline from "./Timeline.vue";
import MessageBubble from "./MessageBubble.vue";
import EmojiPicker from "./EmojiPicker.vue";
import AttachmentButton from "./AttachmentButton.vue";
import AttachmentDraft from "./AttachmentDraft.vue";
import { insertEmoji } from "../utils/insertEmoji";
import { useReceptionStore } from "../store/reception";
import { ElMessage } from "element-plus";
const reception = useReceptionStore();
const store = useChatStore();
const input = ref("");
const session = computed(() => reception.customers.find((item) => item.id === store.activeId)?.session);
const isHandling = computed(() => ["active", "processing"].includes(session.value?.status) && session.value.agentId === reception.agent?.id);
const canReply = computed(() => reception.ready && (isHandling.value || ['bot', 'waiting'].includes(session.value?.status)));
const receptionAction = async (action) => {
  try { await reception[action](store.activeId); if (action === 'claim') await store.refreshAssistance(store.activeId); }
  catch (error) { ElMessage.error(error.response?.data?.message || "操作失败，请重新登录或重试"); }
};
const changeProcessing = async () => {
  try { await reception.setStatus(store.activeId, session.value.status === "processing" ? "active" : "processing"); }
  catch (error) { ElMessage.error(error.response?.data?.message || "状态更新失败"); }
};
const inputRef = ref(null);
const pendingAttachment = ref([]);
const uploadingAttachment = ref(false);
watch(() => store.activeId, () => { pendingAttachment.value = []; });
const addEmoji = (emoji) => insertEmoji(input, inputRef, emoji);
const latestUserMessageId = computed(
  () =>
    [...(store.active?.messages || [])]
      .reverse()
      .find((message) => message.from === "user")?.id || null,
);
const currentSuggestion = computed(() =>
  store.suggestionMessageId === latestUserMessageId.value
    ? store.suggestion
    : "",
);

const scrollMessagesToBottom = () => {
  nextTick(() => {
    const messages = document.querySelector(".messages");
    if (messages) {
      messages.scrollTop = messages.scrollHeight;
    }
  });
};

watch(
  [
    () => store.activeId,
    () => currentSuggestion.value,
    () => latestUserMessageId.value,
  ],
  () => {
    input.value = store.active.aiHidden ? "" : currentSuggestion.value || "";
    scrollMessagesToBottom();
  },
  { immediate: true },
);

watch(() => store.active.messages.length, scrollMessagesToBottom);

watch(
  () => store.messageFocusRequest,
  (request) => {
    if (!request || request.conversationId !== store.activeId) return;
    nextTick(() => {
      const target = document.getElementById(`message-${request.messageId}`);
      if (!target) return;
      target.scrollIntoView({ behavior: "smooth", block: "center" });
      target.classList.remove("message-located");
      requestAnimationFrame(() => target.classList.add("message-located"));
      window.setTimeout(() => target.classList.remove("message-located"), 3000);
    });
  },
  { deep: true },
);

const send = async () => {
  if (!canReply.value) return ElMessage.warning("会话已结束或由其他客服接待，无法回复");
  if (uploadingAttachment.value || (!input.value.trim() && !pendingAttachment.value.length)) return;
  const text = input.value;
  const attachment = pendingAttachment.value;
  pendingAttachment.value = [];
  input.value = "";
  scrollMessagesToBottom();
  const sends = [];
  attachment.forEach((file) => sends.push(store.send("", "agent", file)));
  if (text.trim()) sends.push(store.send(text, "agent"));
  try { await Promise.all(sends); }
  catch (error) { input.value = text; pendingAttachment.value = attachment; ElMessage.error(error.response?.data?.message || error.message || "发送失败"); }
};
const sendByEnter = (event) => {
  if (event.isComposing) return;
  event.preventDefault();
  send();
};
const ignoreSuggestion = () => {
  input.value = "";
  store.active.aiHidden = true;
  store.persistConversations();
};
const generateSuggestion = async () => {
  store.active.aiHidden = false;
  store.persistConversations();
  await store.regenerate();
  input.value = currentSuggestion.value || "";
};
</script>
<template>
  <main class="chat-panel">
    <Timeline :danger="store.active.risk > 70" />
    <div class="chat-head">
      <div>
        <strong>{{ store.active.name }}</strong
        ><span>{{ store.active.order }}</span>
      </div>
      <div class="reception-actions">
        <span>{{ session?.mode === 'BOT' && session?.status !== 'closed' ? 'AI自动回复中' : session?.status === 'waiting' ? '等待人工接待' : session?.status === 'closed' ? '已结束' : session?.status === 'processing' ? '当前处理中' : session?.mode === 'HUMAN_PENDING' ? '等待您回复接管' : '当前接待中' }}</span>
        <el-button v-if="isHandling" size="small" :class="{ 'processing-status-button': session?.status === 'processing' }" @click="changeProcessing">{{ session?.status === 'processing' ? '恢复接待' : '标记处理中' }}</el-button>
        <el-button v-if="isHandling" size="small" @click="receptionAction('end')">结束会话</el-button>
      </div>
    </div>
    <div class="messages">
      <template v-for="(m, i) in store.active.messages" :key="m.id || i">
      <div v-if="m.sessionId && m.sessionId !== store.active.messages[i - 1]?.sessionId" class="session-divider">新一轮咨询 · {{ store.active.sessions?.find(item => item.id === m.sessionId)?.agentName || '等待接待' }}</div>
      <MessageBubble
        :message="m"
        :customer-name="store.active.name"
      />
      </template>
    </div>
    <div class="composer-zone">
      <div class="composer">
        <div class="composer-edit-area">
        <div class="attachment-drafts"><AttachmentDraft v-for="(file, index) in pendingAttachment" :key="file.url" :attachment="file" @remove="pendingAttachment.splice(index, 1)" /></div>
        <el-input
          ref="inputRef"
          :disabled="!canReply"
          v-model="input"
          type="textarea"
          :rows="4"
          resize="none"
          :placeholder="`回复${store.active.name}…`"
          @keydown.enter.exact="sendByEnter"
        /></div><EmojiPicker @select="addEmoji" /><AttachmentButton :count="pendingAttachment.length" @ready="pendingAttachment.push($event)" @uploading="uploadingAttachment = $event" /><el-button
          v-if="input.trim()"
          class="ignore-draft"
          @click="ignoreSuggestion"
          >忽略</el-button
        ><el-button
          v-else
          class="generate-draft"
          :disabled="!canReply"
          :loading="
            store.replyGenerating ||
            store.replyPendingIds.includes(store.activeId)
          "
          @click="generateSuggestion"
          >生成</el-button
        ><el-button type="primary" :disabled="!canReply || uploadingAttachment || (!input.trim() && !pendingAttachment.length)" @click="send"
          >发送</el-button
        >
      </div>
    </div>
  </main>
</template>
