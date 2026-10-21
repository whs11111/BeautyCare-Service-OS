import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../src/components/AIManager.vue', import.meta.url), 'utf8');
assert(source.includes("const activeTab = ref('focus')"));
for (const [id, label] of [['focus', '接待重点'], ['profile', '用户画像'], ['records', '服务记录']]) {
  assert(source.includes(`{ id: '${id}', label: '${label}' }`));
  assert(source.includes(`v-show="activeTab === '${id}'"`));
  assert(source.includes(`id="assistant-panel-${id}"`));
  assert(source.includes(`aria-labelledby="assistant-tab-${id}"`));
}
const panels = ['focus', 'profile', 'records'].map((id, index, list) => {
  const start = source.indexOf(`id="assistant-panel-${id}"`);
  const end = index < list.length - 1 ? source.indexOf(`id="assistant-panel-${list[index + 1]}"`) : source.indexOf('</aside>');
  return source.slice(start, end);
});
assert(panels[0].includes('未处理事件') && panels[0].includes('<CommitmentList />'));
assert(!panels[0].includes('已处理事件') && !panels[0].includes('最近订单'));
assert(panels[1].includes('<CustomerProfile @profile-update="profileUpdated" />'));
assert(!panels[1].includes('已处理事件'));
assert(panels[2].includes('已处理事件') && panels[2].includes('最近订单'));
assert.equal((source.match(/<CustomerProfile\b/g) || []).length, 1);
assert(source.includes('role="tablist"') && source.includes('role="tab"') && source.includes('role="tabpanel"'));
for (const key of ['ArrowLeft', 'ArrowRight', 'Home', 'End']) assert(source.includes(key));
assert(source.includes("watch(() => store.activeId, () => { activeTab.value = 'focus'; })"));
assert(source.includes("'single-time': emotionHistory.length === 1"));
assert(source.includes('<span v-if="emotionHistory.length > 1">'));
assert(source.includes('.chart-times.single-time { justify-content: center; }'));
console.log('PASS assistant tabs: grouping, default focus, retained profile instance, conversation reset and keyboard navigation');
