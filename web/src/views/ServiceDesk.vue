<script setup>
import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import { Setting, SwitchButton } from "@element-plus/icons-vue";
import { useChatStore } from "../store/chat";
import ConversationList from "../components/ConversationList.vue";
import ChatPanel from "../components/ChatPanel.vue";
import AIManager from "../components/AIManager.vue";
import DeadlineReminders from '../components/DeadlineReminders.vue';
import { useReceptionStore } from "../store/reception";
import { startReceptionSync } from "../utils/receptionSync";
import { useRouter } from "vue-router";
const reception = useReceptionStore();
watch(() => reception.agent?.name, (name) => {
  document.title = `${name || '客服'} · 客服工作台 | BeautyCare`;
}, { immediate: true, flush: "post" });
const router = useRouter();
let stopReception;
const logout = () => { reception.logout(); router.push("/login"); };
const store = useChatStore();
const assignedConversationIds = computed(() => reception.ready && reception.agent?.id
  ? reception.customers.filter((customer) => customer.session?.agentId === reception.agent.id || ['bot', 'waiting'].includes(customer.session?.status))
      .map((customer) => customer.id)
  : []);
const hasAssignedSelection = computed(() => assignedConversationIds.value.includes(store.activeId));
watch(
  () => [assignedConversationIds.value, store.activeId],
  () => {
    if (!hasAssignedSelection.value && assignedConversationIds.value.length) {
      const first = store.conversations.find((conversation) => assignedConversationIds.value.includes(conversation.id));
      if (first) store.select(first.id);
    }
  },
  { immediate: true, flush: "post" },
);
// Watch every conversation so analysis also runs for messages received through
// hydration or another sync path, without depending on the selected chat.
watch(
  () => store.conversations.map((conversation) => ({
    id: conversation.id,
    messageId: [...conversation.messages].reverse().find((message) => message.from === "user")?.id,
  })),
  (current, previous = []) => {
    if (store.viewMode !== "agent") return;
    const previousIds = new Map(previous.map((item) => [item.id, item.messageId]));
    current.forEach(({ id, messageId }) => {
      if (!messageId || previousIds.get(id) === messageId) return;
      const conversation = store.conversations.find((item) => item.id === id);
      conversation.needsAnalysis = true;
      store.queuedReplyMessageIds[id] = messageId;
      void store.refreshAssistance(id);
    });
  },
);
const saveWorkspaceState = () => store.persistConversations();
const leaveDesk = () => { stopReception?.(); reception.offline(); saveWorkspaceState(); };
onMounted(async () => {
  window.addEventListener("pagehide", leaveDesk);
  store.initSync("agent");
  await store.hydrate();
  try { stopReception = await startReceptionSync(store); }
  catch { reception.error = "接待服务未连接，请重启后端并刷新页面"; }
  if (hasAssignedSelection.value) store.markRead();
  store.startResolvedEventsAutoRefresh();
  void store.initializeResolvedEvents();
  void store.initializeWorkspace();
});
onBeforeUnmount(() => {
  stopReception?.();
  reception.offline();
  store.stopResolvedEventsAutoRefresh();
  saveWorkspaceState();
  window.removeEventListener("pagehide", leaveDesk);
});
</script>
<template>
  <div class="app-shell">
    <DeadlineReminders />
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark"></span><b>BeautyCare</b
        ><small>Service OS</small>
      </div>
      <div class="agent">
        <span class="online"><i></i>在线</span>
        <span v-if="reception.error">{{ reception.error }}</span>
        <div class="agent-avatar">{{ reception.agent?.avatar }}</div>
        <div><b>{{ reception.agent?.name }}</b><small>在线客服</small></div>
        <el-button :icon="Setting" circle /><el-button
          :icon="SwitchButton" @click="logout" title="退出登录"
          circle
        />
      </div>
    </header>
    <div class="workspace">
      <ConversationList />
      <template v-if="hasAssignedSelection"><ChatPanel /><AIManager /></template>
      <div v-else class="desk-empty-state">{{ reception.ready ? '暂无分配给您的会话，等待用户咨询' : '正在加载会话…' }}</div>
    </div>
  </div>
</template>
