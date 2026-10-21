import { durationText } from './receptionDisplay.js';

// Card countdowns are independent of the long-promise popup eligibility.
export function commitmentDisplay(task, now = Date.now()) {
  const due = Date.parse(task?.dueAt);
  if (!Number.isFinite(due)) return { visible: false, overdue: false, text: '' };
  const remaining = due - now;
  if (remaining <= 0) return { visible: true, overdue: true, text: '已超时' };
  return {
    visible: remaining <= 10 * 60 * 1000,
    overdue: false,
    text: `剩余 ${durationText(Math.ceil(remaining / 1000) * 1000)}`,
  };
}
