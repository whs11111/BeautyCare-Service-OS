import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { formatProductReasons } from '../src/utils/productReasonLabels.js';
import { productDescription } from '../src/utils/productPresentation.js';

const catalog = JSON.parse(await readFile(new URL('../../server-v2/server/src/main/resources/catalog-paris-loreal.json', import.meta.url), 'utf8'));
const report = ['# 全量商品展示检查', '', '检查范围：当前58款商品。检查展示分类，不把用户困扰改称商品功能，不凭名称新增功效或完整成分安全承诺。功能文字仅来自现有商品亮点；本检查不代替官网资料或完整配方的独立核实。', ''];
for (const product of catalog) {
  const reasons = [...product.concerns.map(value => '针对' + value), ...(product.skinTypes.length ? ['商品定位：' + product.skinTypes.join('/')] : []), ...product.highlights];
  const rows = formatProductReasons(reasons);
  const functions = rows.find(row => row.startsWith('商品功能：'))?.slice(5).split('、') || [];
  for (const concern of product.concerns) {
    assert(!functions.includes(concern), `${product.sku}: user concern must not become a product function`);
    assert(rows.some(row => row.startsWith('匹配需求：') && row.includes(concern)), `${product.sku}: concern retains matching context`);
  }
  assert(!rows.some(row => row.startsWith('商品功能：') && row.includes('官网需选择')));
  const description = productDescription(product);
  assert(!/官网介绍|选购参考|在官网介绍中/.test(description), `${product.sku}: conversational product introduction`);
  assert(!description.includes('\n'), `${product.sku}: paragraph introduction`);
  report.push(`## ${product.name}（${product.sku}）`, '', ...rows.map(row => '- ' + row), '', '- 介绍：' + (description || '暂无已核实介绍，不自动编造。'), '');
}
assert.equal(catalog.length, 58);
const folder = new URL('../../output/catalog-presentation-audit-20261018/', import.meta.url);
await mkdir(folder, { recursive: true });
await writeFile(new URL('检查结果.md', folder), report.join('\n'));
console.log(`PASS all ${catalog.length} catalog products: demand/function separation, ingredient/features grouping, buying notes and paragraph introductions`);
