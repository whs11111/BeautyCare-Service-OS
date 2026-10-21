import images from '../data/productImages.json' with { type: 'json' };
import descriptions from '../data/productDescriptions.json' with { type: 'json' };

export function productDescription(product) {
  const official = descriptions[product?.sku]?.text?.trim();
  if (!official) return '';
  if (product.sku === 'LP-MU033') {
    return '这款巴黎欧莱雅磨皮系列粉饼主打控油定妆，帮助减少面部油光，打造哑光轻雾妆效。同时兼顾持妆表现，帮助减少脱妆，让妆面保持清爽自然。';
  }
  const name = product.name || '这款商品';
  const features = official.replace(/\s+/g, '，').replace(/[。；;]+$/g, '');
  return official === name || name.includes(official)
    ? `${name}。详细规格和使用方法可在商品详情中查看。`
    : `${name}主打${features}。`;
}

export function safeProductUrl(value) {
  try { const url = new URL(value); return url.protocol === 'https:' ? url.href : ''; } catch { return ''; }
}
export function productImage(product) {
  return safeProductUrl(images[product?.sku]);
}
export function messageProduct(message) {
  if (message?.from !== 'agent') return null;
  if (message.product?.name) return message.product;
  // Compatibility for product recommendations already stored as plain text.
  const text = String(message.text || '');
  if (!text.startsWith('为您推荐：')) return null;
  const lines = text.split('\n');
  const source = text.match(/https:\/\/www\.lorealparis\.com\.cn\/product\/\?id=([^\s/]+)\//);
  if (!source) return null;
  const price = text.match(/(?:参考价格|价格)：\s*¥([\d.]+)/);
  return { name: lines[0].replace('为您推荐：', ''), sku: `LP-${source[1]}`, price: price ? Number(price[1]) : 0,
    source: source[0], reasons: lines.slice(2).filter(line => !line.startsWith('商品来源：')).flatMap(line => line.split('；')).filter(Boolean) };
}
