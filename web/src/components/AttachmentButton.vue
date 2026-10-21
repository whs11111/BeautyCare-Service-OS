<script setup>
import { ref } from "vue";
import { Paperclip, Loading } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { chatApi } from "../api/http";
import { useChatStore } from "../store/chat";
const emit = defineEmits(["ready", "uploading"]);
const props = defineProps({ count: { type: Number, default: 0 } });
const store = useChatStore();
const fileInput = ref(null);
const uploading = ref(false);
const upload = async (event) => {
  const selected = Array.from(event.target.files || []);
  event.target.value = "";
  const available = Math.max(0, 3 - props.count);
  if (!selected.length) return;
  if (!available) return ElMessage.warning("最多添加3个附件");
  if (selected.length > available) ElMessage.warning(`最多添加3个附件，本次只添加前${available}个`);
  const conversationId = store.activeId;
  uploading.value = true;
  emit("uploading", true);
  try {
    for (const file of selected.slice(0, available)) {
    if (file.size > 20 * 1024 * 1024) { ElMessage.warning(`${file.name}超过20MB，已跳过`); continue; }
    try {
    const { data } = await chatApi.upload(file);
    if (!data?.url || !data?.name) {
      throw new Error("上传接口没有返回文件地址，请重启后端后重试");
    }
    if (store.activeId !== conversationId) return ElMessage.warning("会话已切换，请回到原会话重新发送附件");
    emit("ready", {
      ...data,
      type: file.type || data.type || "application/octet-stream",
    });
    } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || "上传失败，请检查后端服务后重试");
    }
    }
  } finally { uploading.value = false; emit("uploading", false); }
};
</script>
<template>
  <button class="attach" type="button" :disabled="uploading" :aria-label="uploading ? '正在上传' : '上传文件'" title="上传文件（最多20MB）" @click="fileInput.click()">
    <el-icon :class="{ 'is-loading': uploading }"><Loading v-if="uploading" /><Paperclip v-else /></el-icon>
  </button>
  <input ref="fileInput" type="file" multiple hidden @change="upload" />
</template>
