<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useChatStore } from '../store/chat';
import { assistanceWorkflowApi } from '../api/http';
import { highlightProfileText, profileColor } from '../utils/profileHighlights';
import { profileProductReasons } from '../utils/productReasonLabels';
import { useReceptionStore } from '../store/reception';
import { ElMessage } from 'element-plus';

const store = useChatStore();
const emit = defineEmits(['profile-update']);
const reception = useReceptionStore();
const sendingSku = ref('');
const canSend = computed(() => {
  const session = reception.customers.find(customer => customer.id === store.activeId)?.session;
  return reception.ready && (['bot', 'waiting'].includes(session?.status) || (['active', 'processing'].includes(session?.status)
    && session?.agentId === reception.agent?.id));
});
async function sendProduct(item) {
  if (!canSend.value || sendingSku.value || loading.value || saving.value) return;
  sendingSku.value = item.sku;
  try {
    await store.send(`为您推荐：${item.name}\n${item.price > 0 ? `价格：¥${item.price}（以商品页面为准）` : '价格待确认'}\n${item.reasons?.join('\n') || ''}${productSource(item) ? `\n商品来源：${productSource(item)}` : ''}`, 'agent', null, { sku: item.sku, name: item.name, category: item.category, price: item.price, source: productSource(item), reasons: [...(item.reasons || [])] });
    ElMessage.success('商品推荐已发送');
  } catch (exception) {
    ElMessage.error(exception.response?.data?.message || exception.message || '发送失败，请重试');
  } finally {
    sendingSku.value = '';
  }
}
const profile = ref(null);
const recommendation = ref(null);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
let generation = 0;
let timer;
let profilePoll;
let checkingProfile = false;
const customerId = (conversation) => `conversation-${conversation.id}`;
const facts = computed(() => profile.value?.facts || []);
const hasQuizEvidence = fact => fact.source === 'QUIZ' || fact.evidenceMessageIds?.includes('quiz');
const profileGroups = computed(() => [
  { key: 'quiz', title: '问卷结果', empty: '暂无问卷结果', facts: facts.value.filter(hasQuizEvidence) },
  { key: 'chat', title: '聊天陈述', empty: '暂无聊天陈述', facts: facts.value.filter(fact =>
    !hasQuizEvidence(fact) || fact.evidenceMessageIds?.some(id => id !== 'quiz')) },
]);
const chatEvidence = fact => {
  if (fact.source !== 'QUIZ' && !fact.lastSnippet?.startsWith('肤质问卷')) return fact.lastSnippet;
  return store.active?.messages?.find(message => fact.evidenceMessageIds?.includes(message.id))?.text || '用户聊天陈述';
};
const factColors = computed(() => Object.fromEntries(facts.value.map((fact, index) => [fact.id, profileColor(index)])));
const reasonSegments = (reason) => highlightProfileText(reason, facts.value);
const dimensions = { skinType: '肤质', concern: '困扰', avoid: '规避成分', ingredient: '期望成分', finish: '妆效', budget: '预算', preference: '偏好', audience: '适用人群' };
const productSource = item => {
  try { const url = new URL(item.source); return url.protocol === 'https:' ? url.href : ''; } catch { return ''; }
};
const statuses = { STATED: '用户表达', INFERRED: '待核实', CONFIRMED: '已确认', REJECTED: '已排除' };
const latestUser = (conversation) => [...(conversation?.messages || [])].reverse().find(message => message.from === 'user');

async function refresh(fromQuiz = false) {
  const conversation = store.active;
  if (!conversation) return;
  const ticket = ++generation;
  const id = customerId(conversation);
  loading.value = true;
  error.value = '';
  try {
    const { data } = await assistanceWorkflowApi.recommend({
      customerId: id,
      latestMessage: latestUser(conversation)?.text || '',
      messages: (fromQuiz === true ? [] : conversation.messages.filter(message => message.from === 'user' && !message.automatic))
        .map(message => ({ id: message.id, role: 'user', text: message.text, sentAt: message.sentAt })),
      workOrders: [],
    });
    const result = await assistanceWorkflowApi.profile(id);
    if (ticket !== generation) return;
    recommendation.value = data;
    profile.value = result.data;
    emit('profile-update', { conversationId: conversation.id, facts: result.data.facts || [] });
  } catch (exception) {
    if (ticket !== generation) return;
    error.value = exception.response?.status === 404 ? '请启动新版后端以使用用户画像与推荐' : '画像更新失败，请稍后重试';
  } finally {
    if (ticket === generation) loading.value = false;
  }
}

async function setStatus(fact, status) {
  const id = customerId(store.active);
  const ticket = generation;
  saving.value = true;
  error.value = '';
  try {
    const { data } = await assistanceWorkflowApi.updateFact(id, fact.id, status);
    if (ticket !== generation) return;
    profile.value.facts = facts.value.map(item => item.id === fact.id ? data : item);
    await refresh();
  } catch {
    if (ticket === generation) error.value = '偏好保存失败，请重试';
  } finally {
    saving.value = false;
  }
}

watch(() => store.activeId, () => {
  clearTimeout(timer);
  generation++;
  profile.value = null;
  recommendation.value = null;
  saving.value = false;
  void refresh();
}, { immediate: true });
watch(() => latestUser(store.active)?.id, () => {
  clearTimeout(timer);
  timer = setTimeout(() => { void refresh(); }, 800);
});
async function checkExternalProfile() {
  if (document.hidden || checkingProfile || loading.value || saving.value || !store.active) return;
  checkingProfile = true;
  const id = customerId(store.active);
  const ticket = generation;
  try {
    const { data } = await assistanceWorkflowApi.profile(id);
    if (ticket === generation && id === customerId(store.active)
        && JSON.stringify(data.facts) !== JSON.stringify(profile.value?.facts)) {
      emit('profile-update', { conversationId: store.activeId, facts: data.facts || [] });
      await refresh(true);
    }
  } catch { /* The ordinary refresh action displays errors; polling remains quiet. */ }
  finally { checkingProfile = false; }
}
function profileChanged(event) {
  let detail = event.detail;
  if (event.type === 'storage') {
    if (event.key !== 'beautycare-profile-update') return;
    try { detail = JSON.parse(event.newValue); } catch { return; }
  }
  if (detail?.customerId === customerId(store.active)) void refresh(true);
}
onMounted(() => {
  window.addEventListener('storage', profileChanged);
  window.addEventListener('beautycare-profile-update', profileChanged);
  window.addEventListener('focus', checkExternalProfile);
  profilePoll = setInterval(checkExternalProfile, 5000);
});
onBeforeUnmount(() => {
  clearTimeout(timer); clearInterval(profilePoll); generation++;
  window.removeEventListener('storage', profileChanged);
  window.removeEventListener('beautycare-profile-update', profileChanged);
  window.removeEventListener('focus', checkExternalProfile);
});
</script>

<template>
  <section class="overview-card profile-card">
    <header><span class="section-index">01</span><div><h3>用户画像与推荐</h3></div><el-button class="section-refresh" :icon="Refresh" circle size="small" :loading="loading" :disabled="saving" title="刷新画像与推荐" @click="refresh" /></header>
    <p v-if="error" class="profile-error" role="alert">{{ error }}</p>
    <p v-if="loading && !profile" class="empty-state">正在整理用户偏好…</p>
    <template v-else>
      <section v-for="group in profileGroups" :key="group.key" class="profile-source-group">
        <h4 class="profile-source-title">{{ group.title }}</h4>
        <p v-if="!group.facts.length" class="empty-state">{{ group.empty }}</p>
      <div v-for="fact in group.facts" :key="fact.id" class="profile-fact" :style="factColors[fact.id]" :class="{ rejected: fact.status === 'REJECTED' }">
        <div class="fact-heading"><b class="fact-label"><i class="fact-color-dot" aria-hidden="true"></i>{{ dimensions[fact.dimension] || fact.dimension }} · {{ fact.value }}</b><small>{{ statuses[fact.status] || fact.status }}</small></div>
        <p v-if="group.key === 'chat' && chatEvidence(fact)" class="fact-evidence">依据：{{ chatEvidence(fact) }}</p>
        <div class="fact-actions"><small v-if="group.key === 'chat'">提及 {{ fact.mentions }} 次</small><el-button v-if="group.key === 'chat' && fact.evidenceMessageIds?.some(id => store.active.messages.some(m => m.id === id))" link @click="store.focusMessage(fact.evidenceMessageIds.find(id => store.active.messages.some(m => m.id === id)))">查看消息</el-button><el-button v-if="fact.status !== 'CONFIRMED'" link type="primary" :disabled="loading || saving" @click="setStatus(fact, 'CONFIRMED')">{{ fact.status === 'REJECTED' ? '恢复确认' : '确认' }}</el-button><el-button v-if="fact.status !== 'REJECTED'" link type="danger" :disabled="loading || saving" @click="setStatus(fact, 'REJECTED')">排除</el-button></div>
      </div>
      </section>
      <div class="profile-recommendations">
        <div class="recommend-title"><b>推荐参考</b></div>
        <p v-if="recommendation?.missingInfo?.length" class="empty-state">待确认：{{ recommendation.missingInfo.join('、') }}</p>
        <div v-for="item in recommendation?.items || []" :key="item.sku" class="recommend-item">
          <div class="fact-heading"><b>{{ item.name }}</b><span>{{ item.price > 0 ? `¥${item.price}` : '价格待确认' }}</span></div>
          <small>{{ item.category }} · {{ item.sku }}</small>
          <a v-if="productSource(item)" :href="productSource(item)" target="_blank" rel="noopener noreferrer" class="product-source">查看商品来源</a>
          <div class="recommend-reasons"><p v-for="(reason, reasonIndex) in profileProductReasons(item, facts)" :key="reasonIndex"><template v-for="(segment, index) in reasonSegments(reason)" :key="index"><span v-if="segment.factId" class="fact-match" :style="factColors[segment.factId]" :title="'对应画像：' + facts.find(fact => fact.id === segment.factId)?.value">{{ segment.text }}</span><template v-else>{{ segment.text }}</template></template></p></div>
          <div class="product-send"><el-button type="primary" size="small" :loading="sendingSku === item.sku" :disabled="!canSend || loading || saving || Boolean(sendingSku)" :title="canSend ? '发送商品推荐卡片' : '会话已结束或不属于当前客服'" @click="sendProduct(item)">发送</el-button></div>
        </div>
        <p v-if="recommendation && !recommendation.items?.length && !recommendation.missingInfo?.length" class="empty-state">商品库暂无同时满足这些条件的商品</p>
      </div>
    </template>
  </section>
</template>

<style scoped>
.profile-card { min-width: 0; }
.product-source { display: inline-block; margin-top: 5px; font-size: 11px; color: #6847f5; }
.profile-card > header { align-items: center; }
.profile-card > header .section-refresh { margin-left: auto; }
.profile-error { color: #d85959; font-size: 12px; }
.profile-source-group { margin-top: 14px; padding: 10px; border: 1px solid var(--border); border-radius: 10px; min-width: 0; }
.profile-source-title { margin: 0 0 6px; font-size: 14px; color: var(--purple); }
.profile-fact, .recommend-item { padding: 10px 0; border-bottom: 1px solid var(--border); }
.profile-fact { border-left: 3px solid var(--fact-border); padding-left: 10px; margin-top: 6px; }
.fact-label { display: flex; align-items: center; gap: 6px; }
.fact-color-dot { width: 8px; height: 8px; flex-shrink: 0; border-radius: 50%; background: var(--fact-color); }
.recommend-reasons { line-height: 2 !important; }
.recommend-reasons .fact-match { color: var(--fact-color); background: var(--fact-bg); border: 1px solid var(--fact-border); border-radius: 4px; padding: 1px 4px; box-decoration-break: clone; -webkit-box-decoration-break: clone; font-size: inherit; }
.fact-heading, .fact-actions, .recommend-title { display: flex; gap: 8px; align-items: center; justify-content: space-between; }
.fact-heading b { font-size: 13px; overflow-wrap: anywhere; }
small { font-size: 11px; color: var(--muted); }
.fact-heading small { flex-shrink: 0; }
.fact-evidence, .recommend-item p { font-size: 12px; color: #77717e; line-height: 1.6; margin: 6px 0; overflow-wrap: anywhere; }
.fact-actions { justify-content: flex-end; flex-wrap: wrap; }
.fact-actions small { margin-right: auto; }
.fact-actions .el-button { margin-left: 0; font-size: 11px; }
.rejected { opacity: .6; }
.profile-recommendations { margin-top: 14px; }
.recommend-title { flex-wrap: wrap; font-size: 16px; }
.product-send { display: flex; justify-content: flex-end; margin-top: 8px; }
.recommend-item span { color: var(--purple); font-size: 13px; flex-shrink: 0; }
</style>
