import axios from "axios";
export const http = axios.create({ baseURL: "/api", timeout: 30000 });
export const chatApi = {
  list: () => http.get("/chat/list"),
  detail: (id) => http.get(`/chat/detail/${id}`),
  send: (data) => http.post("/message/send", data),
  upload: (file) => {
    const data = new FormData();
    data.append("file", file);
    return http.post("/attachments", data, { timeout: 120000 });
  },
  analyze: (id) => http.get("/ai/analyze", { params: { id } }),
  createWorkorder: (data) => http.post("/workorder/create", data),
};

export const workspaceStateApi = {
  load: () => http.get("/workspace/state"),
  save: (state) => http.put("/workspace/state", state),
};

export const customerServiceAgentApi = {
  runTurn: (context) => http.post("/agents/customer-service/turn", context),
  handoffToHuman: (payload) =>
    http.post("/agents/customer-service/handoff", payload),
};

export const followUpAgentApi = {
  createTask: (payload) => http.post("/agents/follow-up/tasks", payload),
  getTask: (taskId) => http.get(`/agents/follow-up/tasks/${taskId}`),
  pushEvent: (taskId, payload) =>
    http.post(`/agents/follow-up/tasks/${taskId}/events`, payload),
};

export const assistanceWorkflowApi = {
  recommend: (context) => http.post('/workflows/assistance/recommend', context, { timeout: 90000 }),
  profile: (customerId) => http.get(`/personalization/profiles/${encodeURIComponent(customerId)}`),
  updateFact: (customerId, factId, status) => http.post(`/personalization/profiles/${encodeURIComponent(customerId)}/facts/${encodeURIComponent(factId)}`, { status }),
  buildResolvedEvents: (context) =>
    http.post("/workflows/assistance/context", context, { params: { fullHistory: true }, timeout: 120000 }),
  buildContext: (context) =>
    http.post("/workflows/assistance/context", context),
  analyzeRisk: (context) => http.post("/workflows/assistance/risk", context),
  generateReplyDraft: (context) =>
    http.post("/workflows/assistance/reply-draft", context),
  generateActionCard: (context) =>
    http.post("/workflows/assistance/action-card", context),
  rankConversations: (status = "all") =>
    http.get("/workflows/assistance/conversations/ranked", {
      params: { status },
    }),
};

export const quizApi = {
  next: (customerId) => http.get(`/quiz/${encodeURIComponent(customerId)}/next`),
  answer: (customerId, questionId, optionIds) => http.post(`/quiz/${encodeURIComponent(customerId)}/answer`, { questionId, optionIds }),
};
