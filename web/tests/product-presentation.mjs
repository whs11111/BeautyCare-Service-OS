import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { safeProductUrl, productImage, productDescription, messageProduct } from '../src/utils/productPresentation.js';
import vm from 'node:vm';
import { formatProductReasons, profileProductReasons } from '../src/utils/productReasonLabels.js';
assert.equal(safeProductUrl('javascript:alert(1)'), '');
assert.equal(safeProductUrl('http://example.com'), '');
assert.match(productImage({ sku: 'LP-SC596-R' }), /^https:\/\/res-wxec-unipt\.lorealchina\.com\//);
assert.equal(productImage({ sku: 'not-a-product' }), '');
const product = { sku: 'LP-MU0293', name: '巴黎欧莱雅水灵灵粉底液', price: 149 };
assert.deepEqual(messageProduct({ from: 'agent', product }), product);
assert.equal(messageProduct({ from: 'user', product }), null);
const legacy = messageProduct({ from: 'agent', text: '为您推荐：巴黎欧莱雅水灵灵粉底液\n参考价格：¥149（以商品页面为准）\n针对干燥起皮；商品定位：干皮\n商品来源：https://www.lorealparis.com.cn/product/?id=MU0293/' });
assert.equal(legacy.price, 149);
assert.equal(legacy.sku, 'LP-MU0293');
assert.deepEqual(legacy.reasons, ['针对干燥起皮', '商品定位：干皮']);
const quiz = await readFile(new URL('../public/quiz.html', import.meta.url), 'utf8');
assert(!quiz.includes('（参考价）'));
assert(quiz.includes('questionnaireReasons(p, r).map(reason =>'));
const formatterCode = quiz.slice(quiz.indexOf('function formatProductReasons('), quiz.indexOf('function renderResult('));
const formatReasons = vm.runInNewContext(formatterCode + '\nquestionnaireReasons');
const reasons = formatReasons({ ingredientsVerified: false, reasons: ['针对干燥起皮', '敏感肌适用性尚未核实，本推荐仅按其他需求匹配，使用前请先局部试用', '尚未核实是否含酒精、香精、A醇、高浓度酸，请以包装成分表为准'] }, { sensitive: true, avoid: ['含香精'] });
assert(reasons.includes('尚未核实是否含香精，请以包装成分表为准'));
assert(!reasons.join('').includes('酒精'));
assert(!reasons.join('').includes('A醇'));
assert(reasons.includes('尚未核实敏感肌适用性，使用前请先局部试用'));
assert.equal(reasons.at(-1), '本推荐仅按已确认的其他需求匹配。');
assert.equal(formatReasons({ reasons: ['针对干燥起皮', '成分未核实，请以包装成分表为准'] }, { sensitive: false, avoid: [] }).join(''), '匹配需求：干燥起皮');
assert.deepEqual(formatProductReasons(['针对干燥起皮', '商品定位：干皮', '葡萄籽', '泡沫丰富、水润不紧绷']), ['匹配需求：干燥起皮', '商品成分：葡萄籽', '适用肤质：干皮', '商品特点：泡沫丰富、水润不紧绷']);
assert.deepEqual(formatProductReasons(['针对毛孔', '针对脱妆', '控油定妆', '哑光轻雾']), ['匹配需求：毛孔、脱妆', '商品功能：控油定妆', '商品特点：哑光轻雾']);
const profileNotices = profileProductReasons({ ingredientsVerified: false, reasons: ['针对出油', '所需成分神经酰胺尚未核实，不作为已匹配条件', '敏感肌适用性尚未核实，本推荐仅按其他需求匹配，使用前请先局部试用', '尚未核实是否含酒精、香精、A醇、高浓度VC、高浓度酸，请以包装成分表为准'] }, [
  { dimension: 'avoid', value: '含酒精', status: 'CONFIRMED' }, { dimension: 'avoid', value: '含香精', status: 'CONFIRMED' }, { dimension: 'ingredient', value: '神经酰胺', status: 'CONFIRMED' },
  { dimension: 'avoid', value: 'A醇', status: 'REJECTED' }, { dimension: 'avoid', value: '高浓度VC', status: 'INFERRED' }
]);
assert(!/A醇|高浓度VC|高浓度酸/.test(profileNotices.join('')));
for (const value of ['酒精', '香精', '神经酰胺']) assert(profileNotices.some(line => line.startsWith('尚未核实是否含' + value)));
assert.equal(profileNotices.at(-1), '本推荐仅按已确认的其他需求匹配。');
assert.match(productDescription({ sku: 'LP-SC596-R' }), /玻色因/);
const powderDescription = productDescription({ sku: 'LP-MU033', name: '巴黎欧莱雅磨皮系列粉饼' });
assert(powderDescription.includes('控油定妆') && powderDescription.includes('哑光轻雾妆效'));
assert(!/官网介绍|选购参考|官网将其|官网页面/.test(powderDescription));
assert(!/官网介绍|选购参考/.test(productDescription({ sku: 'LP-SC596-R', name: '巴黎欧莱雅小蜜罐面霜4.0 滋润版' })));
assert(!powderDescription.includes('\n') && !powderDescription.includes('针对毛孔'));
const cardTemplate = await readFile(new URL('../src/components/ProductRecommendationCard.vue', import.meta.url), 'utf8');
assert(!cardTemplate.includes('v-for="(reason, index) in product.reasons"'), 'Chat cards show prose, not recommendation reason lists');
assert.equal(productDescription({ sku: 'unknown' }), '');
const client = await readFile(new URL('../src/views/CustomerChat.vue', import.meta.url), 'utf8');
const agent = await readFile(new URL('../src/components/MessageBubble.vue', import.meta.url), 'utf8');
assert(client.includes(':product="messageProduct(m)" customer-facing'));
assert(!agent.includes('customer-facing'));
for (const file of ['../src/components/MessageBubble.vue', '../src/views/CustomerChat.vue']) {
  assert((await readFile(new URL(file, import.meta.url), 'utf8')).includes('<ProductRecommendationCard'));
}
console.log('PASS product URL safety, official images, structured and historical cards, separate reason rows and both chat views');
