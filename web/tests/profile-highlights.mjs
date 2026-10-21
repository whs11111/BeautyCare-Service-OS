import assert from 'node:assert/strict';
import { highlightProfileText } from '../src/utils/profileHighlights.js';
const facts = [
  { id: 'pore', dimension: 'concern', value: '毛孔', status: 'CONFIRMED' },
  { id: 'makeup', dimension: 'concern', value: '脱妆', status: 'CONFIRMED' },
  { id: 'dryness', dimension: 'concern', value: '干燥起皮', status: 'CONFIRMED' },
  { id: 'dry', dimension: 'skinType', value: '干皮', status: 'CONFIRMED' },
  { id: 'sensitive', dimension: 'skinType', value: '敏感肌', status: 'CONFIRMED' },
];
const highlights = text => highlightProfileText(text, facts).filter(segment => segment.factId);
assert.deepEqual(highlights('匹配需求：毛孔、脱妆'), [{ text: '毛孔', factId: 'pore' }, { text: '脱妆', factId: 'makeup' }]);
assert.deepEqual(highlights('匹配需求：干燥起皮'), [{ text: '干燥起皮', factId: 'dryness' }]);
assert.deepEqual(highlights('适用肤质：干皮'), [{ text: '干皮', factId: 'dry' }]);
assert.deepEqual(highlights('针对干燥起皮'), [{ text: '干燥起皮', factId: 'dryness' }]);
assert.deepEqual(highlights('尚未核实敏感肌适用性'), []);
assert.deepEqual(highlights('商品特点：减少脱妆'), []);
const ingredientFacts = [{ id: 'ceramide', dimension: 'ingredient', value: '神经酰胺', status: 'CONFIRMED' }, { id: 'alcohol', dimension: 'avoid', value: '含酒精', status: 'CONFIRMED' }];
for (const text of ['尚未核实是否含神经酰胺', '含神经酰胺', '商品成分：神经酰胺']) {
  const segments = highlightProfileText(text, ingredientFacts);
  assert.deepEqual(segments.filter(segment => segment.factId), [{ text: '神经酰胺', factId: 'ceramide' }]);
  assert.equal(segments.map(segment => segment.text).join(''), text);
}
assert.deepEqual(highlightProfileText('无酒精', ingredientFacts).filter(segment => segment.factId), [{ text: '酒精', factId: 'alcohol' }]);
assert.deepEqual(highlightProfileText('含酒精', ingredientFacts).filter(segment => segment.factId), []);
assert.deepEqual(highlightProfileText('匹配需求：毛孔', [{ ...facts[0], status: 'REJECTED' }]).filter(segment => segment.factId), []);
for (const text of ['匹配需求：毛孔、脱妆', '匹配需求：干燥起皮', '适用肤质：干皮']) {
  assert.equal(highlightProfileText(text, facts).map(segment => segment.text).join(''), text);
  assert(highlights(text).every(segment => !segment.text.includes('：')));
}
console.log('PASS value-only highlights, multiple matches, unchanged identity colors, excluded facts and no warning highlights');
