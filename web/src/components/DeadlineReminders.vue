<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { useReceptionStore } from '../store/reception';
import { ElMessage } from 'element-plus';
const reception = useReceptionStore();
const now = ref(Date.now());
const selected = ref(null);
const dueAt = ref('');
const delayMinutes = ref(15);
const delays = [15, 30, 45, 60, 90, 120, 180, 240, 360, 720, 1440, 2880, 4320, 10080];
const delayLabel = minutes => minutes < 60 ? `${minutes}分钟` : minutes < 1440 ? `${minutes / 60}小时` : `${minutes / 1440}天`;
const delayedTime = computed(() => new Date(Math.max(Date.parse(current.value.task?.dueAt) || now.value, now.value) + delayMinutes.value * 60000).toLocaleString('zh-CN', { hour12: false }));
const reason = ref('');
const description = ref('');
const busy = ref(false);
const error = ref('');
const postpone = ref(false);
let timer;
const current = computed(() => {
  const customer = reception.customers.find(item => item.id === selected.value?.customerId);
  const task = customer?.commitments?.find(item => item.id === selected.value?.taskId);
  return { customer, task };
});
function show(selection) {
  const task = reception.customers.find(item => item.id === selection.customerId)?.commitments?.find(item => item.id === selection.taskId);
  selected.value = { ...selection, revision: task?.revision }; dueAt.value = ''; delayMinutes.value = 15; reason.value = ''; description.value = ''; error.value = ''; postpone.value = false;
}
watch(() => reception.deadlineEditor, value => { if (value) { show(value); reception.deadlineEditor = null; } });
function check() {
  now.value = Date.now();
  if (document.hidden) return;
  if (selected.value) return;
  const candidates = reception.customers
    .flatMap(customer => (customer.commitments || []).filter(task => (task.agentId || customer.session?.agentId) === reception.agent?.id && task.status === 'OPEN' && task.reminderEligible !== false && Date.parse(task.dueAt) - now.value <= 15 * 60 * 1000)
      .map(task => ({ customerId: customer.id, taskId: task.id, dueAt: task.dueAt })));
  candidates.sort((a, b) => Date.parse(a.dueAt) - Date.parse(b.dueAt));
  if (candidates.length) show({ ...candidates[0], automatic: true });
}
onMounted(() => { check(); timer = setInterval(check, 1000); });
onBeforeUnmount(() => clearInterval(timer));
watch(() => current.value.task?.status, status => { if (selected.value?.taskId && status && status !== 'OPEN') selected.value = null; });
watch(() => current.value.task?.revision, revision => {
  if (selected.value?.taskId && revision != null && revision !== selected.value.revision && !busy.value) selected.value = null;
});
async function save(action) {
  if (busy.value || !selected.value) return;
  if (action === 'create' && !dueAt.value) { error.value = '请选择承诺完成时间'; return; }
  if (action === 'postpone' && !reason.value.trim()) { error.value = '请填写延后原因，将同步告知消费者'; return; }
  if (action === 'create' && !description.value.trim()) { error.value = '请填写待办事项'; return; }
  busy.value = true; error.value = '';
  const selection = { ...selected.value };
  try {
    if (action === 'create') await reception.addCommitment(selection.customerId, { description: description.value.trim(), dueAt: new Date(dueAt.value).toISOString() });
    else await reception.updateCommitment(selection.customerId, selection.taskId, {
      action, revision: String(selection.revision),
      ...(action === 'postpone' ? { delayMinutes: String(delayMinutes.value), reason: reason.value.trim() } : {}),
    });
    selected.value = null;
    ElMessage.success(action === 'end' ? '已结束提醒，不再弹窗' : action === 'postpone' ? '已延后并告知消费者' : '已记录承诺，超过15分钟才开启提醒');
  } catch (exception) { error.value = exception.response?.data?.message || '保存失败，请刷新后重试'; }
  finally { busy.value = false; }
}
</script>
<template>
  <el-dialog :model-value="Boolean(selected)" :title="selected?.taskId ? '承诺完成时间提醒' : '设置承诺完成时间'" width="min(500px, 94vw)" :show-close="!selected?.automatic && !busy" :close-on-click-modal="false" :close-on-press-escape="false" @close="selected = null">
    <template v-if="selected">
      <p><b>{{ current.customer?.name }}</b></p>
      <template v-if="selected.taskId">
        <p>{{ current.task?.description }}</p>
        <p>承诺时间：{{ current.task?.dueAt ? new Date(current.task.dueAt).toLocaleString('zh-CN', { hour12: false }) : '' }}</p>
        <p v-if="selected.automatic">{{ Date.parse(current.task?.dueAt) <= now ? '承诺已超时，请及时跟进。' : '距离承诺时间已不足15分钟，请及时跟进。' }}</p>
      </template>
      <el-input v-else v-model="description" placeholder="待办事项" maxlength="1000" />
      <div v-if="postpone || !selected.taskId" class="deadline-form">
        <label v-if="!selected.taskId">承诺完成时间<el-date-picker v-model="dueAt" type="datetime" placeholder="请选择承诺完成时间" :disabled="busy" style="width: 100%" /><small>仅为超过15分钟的承诺设置提醒。</small></label>
        <label v-else>往后延长<el-select v-model="delayMinutes" :disabled="busy"><el-option v-for="minutes in delays" :key="minutes" :value="minutes" :label="delayLabel(minutes)" /></el-select><small>从原承诺时间延长，已超时则从现在起算。新时间：{{ delayedTime }}</small></label>
        <template v-if="selected.taskId"><el-input v-model="reason" type="textarea" placeholder="延后原因（会同步告知消费者）" maxlength="500" /><small>保存后会向消费者发送新时间及原因。</small></template>
      </div>
      <p v-if="error" class="deadline-error" role="alert">{{ error }}</p>
    </template>
    <template #footer>
      <template v-if="selected?.taskId"><el-button :disabled="busy" @click="save('end')">结束提醒</el-button><el-button v-if="!postpone" type="primary" @click="postpone = true">延后</el-button><el-button v-else type="primary" :loading="busy" @click="save('postpone')">保存并告知消费者</el-button></template>
      <el-button v-else type="primary" :loading="busy" @click="save('create')">保存</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.deadline-form { display: grid; gap: 12px; margin-top: 16px; }
.deadline-form label { display: grid; gap: 8px; }
.deadline-form input { border: 1px solid #d7d1e3; border-radius: 6px; padding: 8px; font: inherit; }
.deadline-form small { color: #777; }
.deadline-error { color: #c53742; }
</style>
