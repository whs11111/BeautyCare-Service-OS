<script setup>
import { useRouter } from "vue-router";
import { Headset, User } from "@element-plus/icons-vue";
import { useChatStore } from "../store/chat";
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { useReceptionStore } from "../store/reception";
import { http } from "../api/http";

const router = useRouter();
const store = useChatStore();
const reception = useReceptionStore();
const account = ref("agent1");
const password = ref("123456");
const loggingIn = ref(false);
const launchingDemo = ref(false);
onMounted(() => { void store.hydrate(); });
const launchDemo = async () => {
  const customers = [store.conversations.find(item => item.name === '陈女士'), store.conversations.find(item => item.id === 5)];
  if (customers.some((item) => !item)) return ElMessage.error("未找到对应演示客户的聊天记录");
  // Open synchronously from the click so browsers can allow the four tabs.
  const windows = Array.from({ length: 4 }, () => window.open("about:blank", "_blank"));
  if (windows.some((item) => !item)) {
    windows.forEach((item) => item?.close());
    return ElMessage.warning("请允许本站打开多个窗口后再点击一键展示");
  }
  windows.forEach((item) => { item.document.title = "正在打开演示"; item.document.body.textContent = "正在打开…"; });
  launchingDemo.value = true;
  try {
    const responses = await Promise.all(["agent1", "agent2"].map((account) => http.post("/reception/login", { account, password: "123456" })));
    responses.forEach(({ data }, index) => {
      const tab = windows[index];
      if (tab.closed) return;
      tab.sessionStorage.setItem("service-agent", JSON.stringify(data));
      tab.sessionStorage.setItem("beautycare-service-os-active-conversation", String(customers[index].id));
      tab.opener = null;
      tab.location.replace(router.resolve("/desk").href);
    });
    customers.forEach((customer, index) => {
      const tab = windows[index + 2];
      if (tab.closed) return;
      tab.sessionStorage.removeItem("service-agent");
      tab.opener = null;
      tab.location.replace(router.resolve(`/customer/${customer.id}`).href);
    });
  } catch (error) {
    windows.forEach((item) => item.close());
    ElMessage.error(error.response?.data?.message || "演示打开失败，请确认后端已启动");
  } finally { launchingDemo.value = false; }
};
const login = async () => {
  const deskWindow = window.open("about:blank", "_blank");
  if (!deskWindow) {
    ElMessage.warning("请允许浏览器打开新窗口后再登录");
    return;
  }
  deskWindow.document.title = "正在登录客服工作台";
  deskWindow.document.body.textContent = "正在登录…";
  loggingIn.value = true;
  try {
    await reception.login(account.value, password.value);
    if (!deskWindow.closed) {
      deskWindow.sessionStorage.setItem("service-agent", JSON.stringify(reception.identity));
      deskWindow.opener = null;
      deskWindow.location.replace(router.resolve("/desk").href);
    }
  }
  catch (error) { deskWindow.close(); ElMessage.error(error.response?.data?.message || "登录失败，请确认后端已启动"); }
  finally { loggingIn.value = false; }
};

const openPage = (path) => {
  window.open(router.resolve(path).href, "_blank", "noopener,noreferrer");
};
</script>

<template>
  <div class="login-page">
    <div class="login-glow login-glow-one"></div>
    <div class="login-glow login-glow-two"></div>

    <header class="login-brand">
      <span class="brand-mark"></span>
      <b>BeautyCare</b>
      <span>Service OS</span>
    </header>

    <main class="login-panel">
      <div class="login-intro">
        <span class="eyebrow">DEMO ACCESS</span>
        <h1>选择演示身份</h1>
        <p>分别登录不同客服账号，用户咨询页可在独立窗口打开，消息实时同步。</p>
      </div>

      <div class="role-grid">
        <section class="role-card agent-role agent-login-card">
          <div class="customer-role-head">
          <span class="role-icon"
            ><el-icon><Headset /></el-icon
          ></span>
          <span class="role-copy">
            <small>客服团队</small>
            <strong>进入客服工作台</strong>
            <em>处理会话、AI 建议与风险工单</em>
          </span>
          </div>
          <div class="agent-login-form">
            <el-select v-model="account"><el-option label="客服01" value="agent1" /><el-option label="客服02" value="agent2" /><el-option label="客服03" value="agent3" /></el-select>
            <el-input v-model="password" type="password" show-password placeholder="密码" @keyup.enter="login" />
            <el-button type="primary" :loading="loggingIn" @click="login">登录客服工作台</el-button>
          </div>
        </section>

        <section class="role-card customer-role">
          <div class="customer-role-head">
            <span class="role-icon"
              ><el-icon><User /></el-icon
            ></span>
            <span class="role-copy">
              <small>客户体验</small>
              <strong>进入用户咨询页</strong>
              <em>点击用户直接进入</em>
            </span>
          </div>

          <div class="customer-picker">
            <button
              v-for="customer in store.conversations"
              :key="customer.id"
              @click="openPage(`/customer/${customer.id}`)"
            >
              <span>{{ customer.avatar }}</span>
              {{ customer.name }}
            </button>
          </div>
        </section>
      </div>
      <div class="demo-launch"><el-button type="primary" :loading="launchingDemo" @click="launchDemo">一键展示</el-button></div>
    </main>
  </div>
</template>
