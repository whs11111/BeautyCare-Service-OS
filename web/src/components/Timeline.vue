<script setup>
import { computed } from "vue";
import { useChatStore } from "../store/chat";
import { formatMessageTime } from "../utils/messageTime";

defineProps({ danger: { type: Boolean, default: false } });
const store = useChatStore();
const items = computed(() =>
  (store.active?.timeline || []).filter((item) => !/客服.*(切换|接待|身份|问候|为您服务)|切换客服/.test(item.label || "")).slice(-5).map((item) => ({
    ...item,
    label: String(item.label || "").replace(/[.…]+$/, "").slice(0, 12),
    displayTime: formatMessageTime({
      sentAt: item.occurredAt,
      time: item.occurredAt,
    }),
  })),
);
</script>

<template>
  <section class="timeline">
    <div class="timeline-title">用户全轨迹</div>
    <div v-if="items.length" class="timeline-line">
      <div
        v-for="(item, index) in items"
        :key="`${item.type}-${item.occurredAt}-${item.label}-${index}`"
        class="timeline-item"
        :class="{
          risk: item.risk,
          current: index === items.length - 1,
        }"
      >
        <span class="dot"></span>
        <strong>{{ item.label }}</strong>
        <small>{{ item.displayTime || "时间待确认" }}</small>
      </div>
    </div>
    <div v-else class="timeline-empty">
      AI 正在提炼当前用户的关键服务轨迹
    </div>
  </section>
</template>
