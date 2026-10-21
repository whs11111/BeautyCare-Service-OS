export const profileCustomerId = id => `conversation-${id}`;
export const quizUrl = id => `/quiz.html?c=${encodeURIComponent(profileCustomerId(id))}`;
export function selectQuizOption(question, current, optionId) {
  const option = question.options.find(item => item.id === optionId);
  if (!option) return current;
  if (!question.multi) return [optionId];
  if (option.exclusive) return current.includes(optionId) ? [] : [optionId];
  const selected = current.filter(id => !question.options.find(item => item.id === id)?.exclusive);
  if (selected.includes(optionId)) return selected.filter(id => id !== optionId);
  if (question.maxSelect && selected.length >= question.maxSelect) return selected;
  return [...selected, optionId];
}
export function notifyProfileUpdated(customerId) {
  const detail = { customerId, time: Date.now() };
  window.dispatchEvent(new CustomEvent('beautycare-profile-update', { detail }));
  try { localStorage.setItem('beautycare-profile-update', JSON.stringify(detail)); } catch {}
}
