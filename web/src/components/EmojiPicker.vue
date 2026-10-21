<script setup>
import { ref } from "vue";

const emit = defineEmits(["select"]);
const visible = ref(false);
const emojis = [
  "😀", "😊", "😄", "😁", "😂", "🤣", "🥰", "😍",
  "😘", "😉", "😎", "🤔", "😅", "🥲", "😭", "😔",
  "😤", "😡", "😱", "😳", "🥺", "🙏", "👍", "👎",
  "👌", "👏", "🤝", "💪", "👋", "❤️", "💖", "💕",
  "🌹", "🌸", "🎉", "✨", "✅", "🎁", "☀️", "🍀",
];
const select = (emoji) => {
  visible.value = false;
  emit("select", emoji);
};
</script>

<template>
  <el-popover v-model:visible="visible" placement="top" :width="292" trigger="click" popper-class="emoji-popover">
    <template #reference>
      <button class="emoji-trigger" type="button" aria-label="选择表情" title="表情" :aria-expanded="visible" @mousedown.prevent>☺</button>
    </template>
    <div class="emoji-picker-title">选择表情</div>
    <div class="emoji-grid">
      <button v-for="emoji in emojis" :key="emoji" type="button" :aria-label="emoji" @mousedown.prevent @click="select(emoji)">{{ emoji }}</button>
    </div>
  </el-popover>
</template>
