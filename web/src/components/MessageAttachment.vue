<script setup>
import { computed } from "vue";
import { ElMessage } from "element-plus";
import { getFileIcon } from "../utils/fileIcon";
const props = defineProps({ attachment: Object, openOnClick: Boolean });
const fileIcon = computed(() => getFileIcon(props.attachment.name));
const isImage = (type) => /^image\/(png|jpeg|gif|webp|bmp)$/.test(type || "");
const size = (bytes) => bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
const openFile = async (event) => {
  if (!props.openOnClick) return;
  event.preventDefault();
  const extension = props.attachment.name.split(".").pop().toLowerCase();
  const previewType = extension === "pdf" ? "application/pdf"
    : ["txt", "md", "csv", "json", "log"].includes(extension) ? "text/plain;charset=UTF-8" : null;
  if (!previewType) {
    ElMessage.info("该文件类型暂不支持在线预览，请点击时间旁的下载图标后打开");
    return;
  }
  const preview = window.open("", "_blank");
  if (!preview) { ElMessage.warning("请允许浏览器打开预览窗口"); return; }
  preview.opener = null;
  preview.document.title = "正在打开文件";
  preview.document.body.textContent = "正在加载文件…";
  try {
    const response = await fetch(`${props.attachment.url}?open=true`);
    if (!response.ok) throw new Error("文件读取失败");
    const blob = new Blob([await response.arrayBuffer()], { type: previewType });
    const url = URL.createObjectURL(blob);
    if (!preview.closed) preview.location.replace(url);
    window.setTimeout(() => URL.revokeObjectURL(url), 300000);
  } catch (error) {
    preview.close();
    ElMessage.error(error.message || "文件预览失败");
  }
};
</script>
<template>
  <div class="message-attachment">
    <el-image v-if="isImage(attachment.type)" :src="attachment.url" :preview-src-list="[attachment.url]" preview-teleported fit="contain" />
    <template v-else>
    <a class="sent-file-card" :href="openOnClick ? `${attachment.url}?open=true` : attachment.url" :download="openOnClick ? undefined : attachment.name" :target="openOnClick ? '_blank' : undefined" rel="noopener" :title="attachment.name" @click="openFile">
      <div class="sent-file-info"><b>{{ attachment.name }}</b><span>{{ size(attachment.size) }}</span></div>
      <span class="sent-file-icon" :class="fileIcon.tone">{{ fileIcon.label }}</span>
    </a>
    </template>
  </div>
</template>
