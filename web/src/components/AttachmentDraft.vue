<script setup>
import { computed } from "vue";
import { getFileIcon } from "../utils/fileIcon";
const props = defineProps({ attachment: Object });
const fileIcon = computed(() => getFileIcon(props.attachment.name));
const isImage = computed(() => /^image\/(png|jpeg|gif|webp|bmp)$/.test(props.attachment.type || ""));
const fileSize = computed(() => {
  const bytes = Number(props.attachment.size) || 0;
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
});
defineEmits(["remove"]);
</script>
<template>
  <div class="attachment-draft" :class="{ 'image-draft': isImage }">
    <img v-if="isImage" :src="attachment.url" alt="待发送图片" />
    <span v-else class="sent-file-icon" :class="fileIcon.tone">{{ fileIcon.label }}</span>
    <div v-if="!isImage"><b :title="attachment.name">{{ attachment.name }}</b><small>{{ fileSize }}</small></div>
    <button type="button" aria-label="移除附件" @click="$emit('remove')">×</button>
  </div>
</template>
