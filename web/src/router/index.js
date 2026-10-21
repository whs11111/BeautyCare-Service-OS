import { createRouter, createWebHistory } from "vue-router";
import ServiceDesk from "../views/ServiceDesk.vue";
import CustomerChat from "../views/CustomerChat.vue";
import LoginView from "../views/LoginView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/login" },
    {
      path: "/login",
      component: LoginView,
      meta: { title: "BeautyCare Service OS" },
    },
    {
      path: "/desk",
      component: ServiceDesk,
      meta: { title: "客服工作台 | BeautyCare" },
    },
    {
      path: "/customer/:id?",
      component: CustomerChat,
      meta: { title: "用户咨询 | BeautyCare" },
    },
  ],
});

router.afterEach((to) => {
  if (to.path !== "/desk" && !to.path.startsWith("/customer")) {
    document.title = to.meta.title || "BeautyCare Service OS";
  }
});

export default router;
router.beforeEach((to) => {
  if (to.path === '/desk' && !sessionStorage.getItem("service-agent")) return "/login";
});
