// Only highlight phrases describing a match, not unrelated ingredient mentions.
export function factTerms(fact) {
  const value = String(fact.value || '');
  if (!value) return [];
  if (fact.dimension === 'avoid') return [value.replace(/^含/, '')];
  if (fact.dimension === 'ingredient') {
    const aliases = { '透明质酸': ['透明质酸', '玻尿酸'], 'VC': ['VC', '维C', '维生素C'], 'A醇': ['A醇', '视黄醇'] };
    const ingredient = value.replace(/^含/, '');
    return aliases[ingredient] || [ingredient];
  }
  if (fact.dimension === 'skinType') return [value];
  if (fact.dimension === 'concern') return [value];
  if (fact.dimension === 'finish') return [value + '妆效符合偏好'];
  if (fact.dimension === 'budget') return ['预算' + value + '元'];
  return [];
}

export function highlightProfileText(text, facts) {
  text = String(text || '');
  const matches = [];
  facts.forEach(fact => {
    if (fact.status === 'REJECTED') return;
    // 只高亮需求字段中的值，不包含标签，也不误框未核实提醒中的词。
    if (fact.dimension === 'concern' && !/^(匹配需求[：:]|针对)/.test(text)) return;
    if (fact.dimension === 'skinType' && !/^(适用肤质[：:]|商品定位[：:]|适合)/.test(text)) return;
    factTerms(fact).forEach(term => {
      let offset = 0;
      while (offset < text.length) {
        const start = text.indexOf(term, offset);
        if (start < 0) break;
        // 规避成分只标记无该成分或待核实条件；“含酒精”不能算规避条件匹配。
        if (fact.dimension === 'avoid' && text[start - 1] !== '无' && !/尚未核实|未核实|未确认/.test(text)) {
          offset = start + term.length;
          continue;
        }
        // A budget overrun is not a positive match.
        if (!(fact.dimension === 'budget' && text.slice(Math.max(0, start - 2), start) === '略超')) {
          matches.push({ start, end: start + term.length, factId: fact.id });
        }
        offset = start + term.length;
      }
    });
  });
  matches.sort((a, b) => a.start - b.start || b.end - a.end);
  const segments = [];
  let cursor = 0;
  for (const match of matches) {
    if (match.start < cursor) continue;
    if (match.start > cursor) segments.push({ text: text.slice(cursor, match.start) });
    segments.push({ text: text.slice(match.start, match.end), factId: match.factId });
    cursor = match.end;
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor) });
  return segments;
}

export function profileColor(index) {
  const hue = Math.round((255 + index * 137.508) % 360);
  return { '--fact-color': `hsl(${hue} 58% 40%)`, '--fact-border': `hsl(${hue} 62% 65%)`, '--fact-bg': `hsl(${hue} 75% 96%)` };
}
