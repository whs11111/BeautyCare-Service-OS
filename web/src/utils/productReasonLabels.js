// Also normalizes older backend responses so cached/default risk lists do not leak into user-specific notices.
export function profileProductReasons(product, facts = []) {
  const original = product.reasons || [];
  const lines = original.filter(reason => !/尚未核实|未核实|本推荐仅/.test(reason));
  const requested = facts.filter(fact => !['REJECTED', 'INFERRED'].includes(fact.status));
  let hasUnknown = false;
  for (const fact of requested) {
    if (fact.dimension === 'avoid' && !product.ingredientsVerified) {
      lines.push('尚未核实是否含' + fact.value.replace(/^含/, '') + '，请以包装成分表为准');
      hasUnknown = true;
    }
    if (fact.dimension === 'ingredient' && original.some(reason => reason.startsWith('所需成分') && reason.includes(fact.value) && reason.includes('尚未核实'))) {
      lines.push('尚未核实是否含' + fact.value + '，不作为已匹配条件');
      hasUnknown = true;
    }
  }
  if (original.some(reason => reason.includes('敏感肌') && reason.includes('未核实'))) {
    lines.push('尚未核实敏感肌适用性，使用前请先局部试用');
    hasUnknown = true;
  }
  if (hasUnknown) lines.push('本推荐仅按已确认的其他需求匹配。');
  return formatProductReasons([...new Set(lines)]);
}

export function formatProductReasons(reasons = []) {
  const groups = { '匹配需求': [], '商品功能': [], '商品成分': [], '适用肤质': [], '商品特点': [] };
  const notes = [];
  const ingredientNames = new Set(['葡萄籽', '玻尿酸', '透明质酸', '玻色因', '胜肽', '神经酰胺', '视黄醇', 'A醇', '烟酰胺', '二裂酵母', '花蜜', '松露', '氨基酸', '维生素C', 'VC', '水杨酸', '燕窝酸']);
  for (const value of reasons || []) {
    const reason = String(value).trim();
    if (!reason) continue;
    if (/尚未核实|未核实|未确认|本推荐仅|候选参考|预算/.test(reason)) { notes.push(reason); continue; }
    if (reason.startsWith('针对')) { groups['匹配需求'].push(reason.slice(2)); continue; }
    if (reason.startsWith('官网需选择')) { notes.push('购买提示：' + reason); continue; }
    if (/^(商品定位[：:]|适合)/.test(reason)) { groups['适用肤质'].push(reason.replace(/^(商品定位[：:]|适合)/, '')); continue; }
    if (reason.startsWith('商品资料提及')) { groups['商品成分'].push(reason.slice(6)); continue; }
    if (reason.startsWith('含') && !reason.startsWith('含量')) { groups['商品成分'].push(reason.slice(1)); continue; }
    if (/妆效符合偏好$/.test(reason)) { groups['商品特点'].push(reason.replace('妆效符合偏好', '妆效')); continue; }
    if (reason.startsWith('无')) { notes.push(reason); continue; }
    reason.split('、').filter(Boolean).forEach(part => {
      const ingredients = part.split('+');
      if (ingredients.every(term => ingredientNames.has(term))) groups['商品成分'].push(...ingredients);
      else if (/保湿|补水|控油|定妆|持妆|遮瑕|淡纹|淡化眼纹|抗皱|提拉|紧致|防晒|修护|舒缓|美白|清洁|清痘|去黑头|润色|提亮/.test(part)) groups['商品功能'].push(part);
      else groups['商品特点'].push(part);
    });
  }
  return [...Object.entries(groups).filter(([, values]) => values.length).map(([label, values]) => label + '：' + [...new Set(values)].join('、')), ...notes];
}
