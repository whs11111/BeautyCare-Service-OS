<script setup>
defineProps({
  title: String,
  subtitle: String,
  score: [String, Number],
  tone: { type: String, default: "purple" },
});
</script>
<template>
  <el-card shadow="never" class="ai-card" :class="tone"
    ><template #header
      ><div class="card-header">
        <div>
          <strong>{{ title }}</strong
          ><small>{{ subtitle }}</small>
        </div>
        <span v-if="score" class="score">{{ score }}</span>
      </div></template
    ><slot
  /></el-card>
</template>
