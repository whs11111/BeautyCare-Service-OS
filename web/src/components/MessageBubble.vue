<script setup>
import { formatMessageTime } from "../utils/messageTime";
import MessageAttachment from "./MessageAttachment.vue";
import { Download } from "@element-plus/icons-vue";
import RobotPromotionCard from './RobotPromotionCard.vue';
import ProductRecommendationCard from './ProductRecommendationCard.vue';
import { messageProduct } from '../utils/productPresentation';
import RobotAvatar from './RobotAvatar.vue';
import { isRobotMessage } from '../utils/serviceIdentity';

defineProps({
  message: Object,
  customerName: {
    type: String,
    default: "用户",
  },
});
</script>
<template>
  <div v-if="message.from === 'system'" class="system-message" :class="{ 'handoff-notice': message.kind === 'handoff' }">
    {{ message.text }}
  </div>
  <div v-else :id="`message-${message.id}`" class="message-row" :class="message.from">
    <div v-if="message.from === 'user'" class="mini-avatar user-message-avatar">
      {{ customerName.slice(0, 1) }}
    </div>
    <div class="message-content">
      <ProductRecommendationCard v-if="messageProduct(message)" :product="messageProduct(message)" />
      <RobotPromotionCard v-else-if="message.kind === 'demo-promotion' && message.card" :card="message.card" />
      <div v-else class="bubble" :class="{ image: message.image, 'file-only': message.attachment && !/^image\/(png|jpeg|gif|webp|bmp)$/.test(message.attachment.type || ''), 'image-only': message.attachment && /^image\/(png|jpeg|gif|webp|bmp)$/.test(message.attachment.type || '') }">
        <MessageAttachment v-if="message.attachment" :attachment="message.attachment" open-on-click />
        <p v-if="message.attachment && message.text !== `[附件] ${message.attachment.name}`" class="attachment-caption">{{ message.text }}</p>
        <div v-if="message.image && !message.attachment" class="product-photo">
          <span>轻透粉底液</span><b>AI 识别：#05</b>
        </div>
        <template v-if="!message.attachment">{{ message.text }}</template>
      </div>
      <small class="desk-message-meta"
        >{{ message.from === "user" ? customerName : (message.agentName || "客服") }} ·
        {{ formatMessageTime(message) }}<a v-if="message.attachment && !/^image\/(png|jpeg|gif|webp|bmp)$/.test(message.attachment.type || '')" class="file-download-icon" :href="`${message.attachment.url}?download=true`" :download="message.attachment.name" aria-label="下载文件" title="下载文件"><el-icon><Download /></el-icon></a></small
      >
    </div>
    <RobotAvatar v-if="isRobotMessage(message)" />
    <div v-else-if="message.from === 'agent'" class="mini-avatar agent-message-avatar">
      {{ message.agentAvatar || '客' }}
    </div>
  </div>
</template>

<style scoped>
.system-message.handoff-notice { background: #f0f0f3; color: #85818b; border-radius: 5px; }
</style>
