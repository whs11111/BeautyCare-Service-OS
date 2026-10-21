<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { quizApi } from '../api/http';
import { notifyProfileUpdated, selectQuizOption } from '../utils/quizSelection';
const props = defineProps({ customerId: { type: String, required: true }, latestMessage: { type: String, default: '' } });
const emit = defineEmits(['layout']);
const question = ref(null);
const selected = ref([]);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const requested = ref(false);
const dismissed = ref(false);
const lastAnswer = ref('');
let generation = 0;
const eligible = computed(() => /(推荐|选购|肤质|干皮|油皮|混油|混干|敏感肌|护肤)/.test(props.latestMessage)
  && !/(漏液|破损|发错|错发|发货|物流|退款|赔偿|投诉|红肿|医院)/.test(props.latestMessage));
const visible = computed(() => !dismissed.value && (requested.value || (eligible.value && Boolean(question.value || error.value || loading.value))));
async function refresh() {
  if (dismissed.value || (!requested.value && !eligible.value)) { generation++; return; }
  const ticket = ++generation;
  const id = props.customerId;
  loading.value = true;
  error.value = '';
  try {
    const response = await quizApi.next(id);
    if (ticket !== generation || id !== props.customerId) return;
    question.value = response.status === 204 ? null : response.data;
    selected.value = [];
  } catch {
    if (ticket === generation) error.value = '暂时无法加载问题，请稍后重试';
  } finally { if (ticket === generation) { loading.value = false; emit('layout'); } }
}
function open() { requested.value = true; dismissed.value = false; void refresh(); }
defineExpose({ open });
async function choose(option) {
  if (saving.value) return;
  selected.value = selectQuizOption(question.value, selected.value, option.id);
  if (!question.value.multi) await save();
}
async function save() {
  if (saving.value || !question.value || !selected.value.length) return;
  const ticket = ++generation;
  const id = props.customerId;
  const current = question.value;
  const options = [...selected.value];
  saving.value = true;
  error.value = '';
  try {
    const { data } = await quizApi.answer(id, current.id, options);
    notifyProfileUpdated(id);
    if (ticket !== generation || id !== props.customerId) return;
    lastAnswer.value = current.options.filter(item => options.includes(item.id)).map(item => item.text).join('、');
    question.value = data.nextQuestion || null;
    selected.value = [];
  } catch {
    if (ticket === generation) error.value = '回答未保存，请重试，已选内容会保留';
  } finally { if (ticket === generation) { saving.value = false; emit('layout'); } }
}
watch(() => props.customerId, () => {
  generation++; requested.value = false; dismissed.value = false; question.value = null;
  lastAnswer.value = ''; selected.value = []; saving.value = false; loading.value = false;
  void refresh();
}, { immediate: true });
watch(() => props.latestMessage, () => { if (!saving.value) void refresh(); });
onBeforeUnmount(() => { generation++; });
</script>

<template>
  <section v-if="visible" class="skin-quick-card" aria-label="护肤需求快捷问答">
    <header><b>先了解一下您的护肤需求</b><button class="dismiss" :disabled="saving" @click="dismissed = true">暂不填写</button></header>
    <small>点击选项后将保存至您的画像，方便客服为您推荐。</small>
    <p v-if="loading">正在加载问题…</p>
    <template v-else-if="question">
      <p class="question">{{ question.text }}</p>
      <div class="answers"><button v-for="option in question.options" :key="option.id" :class="{ selected: selected.includes(option.id) }" :aria-pressed="selected.includes(option.id)" :disabled="saving" @click="choose(option)">{{ option.text }}</button></div>
      <el-button v-if="question.multi" type="primary" size="small" :loading="saving" :disabled="!selected.length" @click="save">确认选择</el-button>
    </template>
    <p v-else-if="!error">已收集到推荐所需的信息，您也可以填写完整问卷补充需求。</p>
    <small v-if="lastAnswer">已记录：{{ lastAnswer }}</small>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="dismiss" @click="question && selected.length ? save() : refresh()">重试</button></p>
    <a :href="`/quiz.html?c=${encodeURIComponent(customerId)}`" target="_blank" rel="noopener">填写一分钟肤质问卷 →</a>
  </section>
</template>

<style scoped>
.skin-quick-card { max-width: 540px; margin: 18px 0; padding: 18px; border: 1px solid #ddd3ff; border-radius: 18px; background: #fff; }
header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
header b { font-size: 15px; color: #352752; }
small { display: block; color: #8d8797; font-size: 12px; line-height: 1.7; margin-top: 6px; }
.question { font-size: 14px; color: #332c40; margin: 14px 0 10px; }
.answers { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.answers button { padding: 9px 13px; background: #faf8ff; border: 1px solid #e4dcf8; border-radius: 10px; color: #5b4d74; cursor: pointer; font: inherit; font-size: 13px; }
.answers button.selected { background: #eee8ff; border-color: #7955eb; color: #6948f5; }
button:disabled { opacity: .6; cursor: wait; }
.dismiss { background: none; border: none; color: #9b91ac; padding: 0; cursor: pointer; white-space: nowrap; }
a { display: inline-block; color: #6948f5; font-size: 13px; margin-top: 12px; text-decoration: none; }
.error { color: #c45656; font-size: 12px; }
</style>
