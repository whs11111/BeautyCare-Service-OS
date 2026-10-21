export function emotionDisplay(conversation) {
  const session = conversation?.reception;
  const pending = Boolean(session?.emotionAnalysisPending);
  const candidate = session?.emotionScore ?? conversation?.emotionScore;
  const score = Number.isFinite(candidate) ? Math.max(0, Math.min(100, candidate)) : null;
  const saved = session?.emotionTrend?.length ? session.emotionTrend : conversation?.emotionHistory || [];
  const history = saved.filter(point => Number.isFinite(point.score)).slice(-6).map(point => ({
    ...point, score: Math.max(0, Math.min(100, point.score)),
    time: Number.isFinite(Date.parse(point.time)) ? new Date(point.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : point.time,
  }));
  if (!pending && score !== null) {
    if (history.length) history[history.length - 1] = { ...history.at(-1), score };
    else history.push({ time: '当前', score });
  }
  return { pending, score: pending ? null : score, history };
}
