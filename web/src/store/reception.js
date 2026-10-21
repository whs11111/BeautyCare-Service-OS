import { defineStore } from "pinia";
import { http } from "../api/http";

const savedIdentity = () => {
  try {
    const identity = JSON.parse(sessionStorage.getItem("service-agent") || "null");
    const number = { agent1: "01", agent2: "02", agent3: "03" }[identity?.agent?.id];
    if (number) identity.agent = { ...identity.agent, name: `客服${number}`, avatar: number };
    return identity;
  } catch { return null; }
};
export const useReceptionStore = defineStore("reception", {
  state: () => ({ identity: savedIdentity(), customers: [], ready: false, error: "", deadlineEditor: null,
    pinnedIds: [], pinningId: '', pinVersion: 0, pinsLoadedAt: 0, readRequests: {} }),
  getters: {
    agent: (state) => state.identity?.agent,
  },
  actions: {
    async login(account, password) {
      const { data } = await http.post("/reception/login", { account, password });
      this.identity = data;
      sessionStorage.setItem("service-agent", JSON.stringify(data));
      this.pinnedIds = []; this.pinsLoadedAt = 0;
      await this.loadPins();
    },
    logout() { void http.post("/reception/logout", {}, { headers: this.headers() }).catch(() => {}); this.identity = null; sessionStorage.removeItem("service-agent"); },
    headers() { return { Authorization: `Bearer ${this.identity?.token || ""}` }; },
    async initialize(conversations) {
      const { data } = await http.post("/reception/bootstrap", conversations.map(({ id, name }) => ({ id, name })));
      this.customers = data;
      this.ready = true;
    },
    offline() {
      if (!this.identity) return;
      void fetch("/api/reception/presence", { method: "POST", headers: { ...this.headers(), "Content-Type": "application/json" }, body: JSON.stringify({ online: false }), keepalive: true }).catch(() => {});
    },
    async poll(heartbeat = false) {
      if (heartbeat && this.identity) await http.post("/reception/presence", { online: true }, { headers: this.headers() });
      const { data } = await http.get("/reception", { headers: this.identity ? this.headers() : {} });
      this.customers = data;
      if (this.identity && Date.now() - this.pinsLoadedAt > 5000) await this.loadPins();
      this.error = "";
    },
    async loadPins() {
      if (!this.identity || this.pinningId) return;
      const token = this.identity.token, version = this.pinVersion;
      try {
        const { data } = await http.get('/reception/pins', { headers: this.headers() });
        if (this.identity?.token === token && version === this.pinVersion) {
          this.pinnedIds = Array.isArray(data) ? data.map(String) : []; this.pinsLoadedAt = Date.now();
        }
      } catch { this.pinsLoadedAt = Date.now(); }
    },
    async togglePin(id) {
      if (this.pinningId) return;
      const token = this.identity?.token;
      const pinned = !this.pinnedIds.includes(String(id));
      if (pinned && this.pinnedIds.length >= 10) throw new Error('最多置顶10位客户，请先取消一个置顶');
      this.pinningId = String(id); this.pinVersion++;
      try {
        const { data } = await http.post(`/reception/${id}/pin`, { pinned }, { headers: this.headers() });
        if (this.identity?.token === token) { this.pinnedIds = data.map(String); this.pinsLoadedAt = Date.now(); }
      } finally { this.pinningId = ''; }
    },
    async markRead(id, messageId) {
      if (!this.identity || !messageId || this.readRequests[id]) return;
      this.readRequests[id] = true;
      try { await http.post(`/reception/${id}/read`, { messageId }, { headers: this.headers() }); }
      finally { delete this.readRequests[id]; }
    },
    async claim(id) {
      await http.post(`/reception/${id}/claim`, {}, { headers: this.headers() });
      await this.poll();
    },
    async open(id) { await http.post(`/reception/${id}/open`); await this.poll(); },
    async addCommitment(id, payload) { await http.post(`/reception/${id}/commitments`, payload, { headers: this.headers() }); await this.poll(); },
    async updateCommitment(id, taskId, payload) { await http.post(`/reception/${id}/commitments/${taskId}`, payload, { headers: this.headers() }); await this.poll(); },
    async reportAnalysis(id, payload) { await http.post(`/reception/${id}/analysis`, payload, { headers: this.headers() }); },
    async end(id) {
      await http.post(`/reception/${id}/end`, {}, { headers: this.headers() });
      await this.poll();
    },
    async setStatus(id, status) {
      await http.post(`/reception/${id}/status`, { status }, { headers: this.headers() });
      await this.poll();
    },
    async send(id, message) {
      const { data } = await http.post(`/reception/${id}/messages`, message, { headers: this.headers() });
      const index = this.customers.findIndex((item) => item.id === data.id);
      if (index >= 0) this.customers[index] = data;
      return data.messages.find((item) => item.id === message.id);
    },
  },
});
