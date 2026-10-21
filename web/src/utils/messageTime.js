const timeText = (date) =>
  date.toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
  });

export const formatMessageTime = (message) => {
  if (!message?.sentAt) return message?.time || "";
  const date = new Date(message.sentAt);
  if (Number.isNaN(date.getTime())) return message?.time || "";

  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const messageDay = new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate(),
  );
  const daysAgo = Math.round((today - messageDay) / 86400000);
  const time = timeText(date);

  if (daysAgo <= 0) return time;
  if (daysAgo === 1) return `昨天 ${time}`;
  if (daysAgo === 2) return `前天 ${time}`;
  if (date.getFullYear() === now.getFullYear()) {
    return `${date.getMonth() + 1}月${date.getDate()}日 ${time}`;
  }
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${time}`;
};
