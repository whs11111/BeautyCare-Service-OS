<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue';
import { useReceptionStore } from '../store/reception';
import { useChatStore } from '../store/chat';
import { commitmentDisplay } from '../utils/commitmentDisplay';
const reception = useReceptionStore();
const store = useChatStore();
const now = ref(Date.now());
let timer;
onMounted(() => { timer = setInterval(() => { now.value = Date.now(); }, 1000); });
onBeforeUnmount(() => clearInterval(timer));
const tasks = computed(() => (store.active?.commitments || []).filter(task => task.status === 'OPEN'));
const analyzing = computed(() => (store.active?.messages || []).some(message => message.sessionId === store.active?.reception?.id && message.commitmentPending));
const canEdit = computed(() => store.active?.reception?.agentId === reception.agent?.id && ['active', 'processing'].includes(store.active?.reception?.status));
const display = task => commitmentDisplay(task, now.value);
const canManage = task => Boolean(reception.agent?.id) && (task.agentId || store.active?.reception?.agentId) === reception.agent.id;
const openTask = task => {
  if (canManage(task)) reception.deadlineEditor = { customerId: store.activeId, taskId: task.id };
};
</script>
<template>
  <div class="commitment-list">
    <div class="commitment-heading"><b>承诺时间跟进</b><el-button class="set-commitment-time" link type="primary" :disabled="!canEdit" @click="reception.deadlineEditor = { customerId: store.activeId }">设置时间</el-button></div>
    <p v-if="analyzing" class="empty-state">正在识别客服承诺…</p>
    <p v-else-if="!tasks.length" class="empty-state">暂无承诺事项</p>
    <button v-for="task in tasks" :key="task.id" type="button" class="commitment-item" :class="{ 'commitment-overdue': display(task).overdue }" :disabled="!canManage(task)" :aria-label="`管理承诺：${task.description}`" @click="openTask(task)">
      <span class="commitment-description">{{ task.description }}</span><small>承诺时间：{{ task.dueAt ? new Date(task.dueAt).toLocaleString('zh-CN', { hour12: false }) : '未明确时间' }}</small>
      <b v-if="display(task).visible" :class="{ overdue: display(task).overdue }">{{ display(task).text }}</b>
      <small v-else-if="!task.dueAt">未明确完成时间</small>
    </button>
  </div>
</template>
<style scoped>
.commitment-list { border-top: 1px solid var(--border); margin-top: 14px; padding-top: 12px; }
.commitment-heading { display: flex; align-items: center; justify-content: space-between; font-size: 13px; }
.set-commitment-time { --el-button-text-color: #6847f5; --el-button-hover-text-color: #876bf7; --el-button-active-text-color: #5032cf; --el-button-disabled-text-color: #c1b4ee; }
.commitment-item { display: block; width: 100%; box-sizing: border-box; text-align: left; font: inherit; color: inherit; background: #fff8ec; border: 1px solid #f2d5a6; padding: 10px; margin-top: 8px; border-radius: 8px; cursor: pointer; }
.commitment-item:not(:disabled):hover { background: #fff2db; border-color: #dca55a; }
.commitment-item.commitment-overdue { background: #fde4e5; border-color: #e79a9e; }
.commitment-item.commitment-overdue:not(:disabled):hover { background: #fbd5d8; border-color: #d36b75; }
.commitment-item:focus-visible { outline: 2px solid #9165d4; outline-offset: 2px; }
.commitment-item:disabled { cursor: default; }
.commitment-description { display: block; font-size: 12px; margin: 0 0 6px; overflow-wrap: anywhere; }
.commitment-item small, .commitment-item > b { display: block; font-size: 11px; margin: 5px 0; }
.overdue { color: #c53742; }
</style>
