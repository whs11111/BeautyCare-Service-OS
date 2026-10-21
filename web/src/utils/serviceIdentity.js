export const isRobotMessage = message => message?.from === 'agent' && (
  /^bot-/.test(message.kind || '') || ['demo-promotion', 'handoff-request'].includes(message.kind)
  || message.agentName === '客服机器人'
);
export const isLegacyGreeting = message => message?.automatic === true && /^我是客服\d+，很高兴为您服务[！!？?]?$/.test(message.text || '');
export const serviceSender = message => isRobotMessage(message) ? '客服机器人' : isLegacyGreeting(message) ? '历史自动接待通知' : message.agentName || '客服';
