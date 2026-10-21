package com.beautycare.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import com.beautycare.workflow.ReceptionRules;

/** Shared, server-owned reception state for the multi-agent demo. */
@RestController
@RequestMapping("/api/reception")
@EnableScheduling
public class ReceptionController {
    private final ObjectMapper mapper;
    private final Path file = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "reception.json");
    private final Map<String, String> tokens = new HashMap<>();
    private final Map<String, OffsetDateTime> heartbeats = new HashMap<>();
    @Value("${reception.idle-minutes:15}")
    private long idleMinutes = 15;
    @Value("${reception.max-conversations:5}")
    private int maxConversations = 5;
    private final Map<String, Map<String, Object>> customers = new LinkedHashMap<>();
    private final Path pinsFile = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "reception-pins.json");
    private final Map<String, List<String>> pinsByAgent = new LinkedHashMap<>();
    private final List<Map<String, Object>> agents = List.of(
            Map.of("id", "agent1", "name", "客服01", "avatar", "01"),
            Map.of("id", "agent2", "name", "客服02", "avatar", "02"),
            Map.of("id", "agent3", "name", "客服03", "avatar", "03"));

    public ReceptionController(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        if (Files.exists(pinsFile)) pinsByAgent.putAll(mapper.readValue(pinsFile.toFile(), new TypeReference<Map<String, List<String>>>() {}));
        if (Files.exists(file)) customers.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, Map<String, Object>>>() {}));
        for (Map<String, Object> state : customers.values()) {
            for (String key : List.of("sessions", "messages")) {
                for (Map<String, Object> item : list(state, key)) {
                    agents.stream().filter(agent -> agent.get("id").equals(item.get("agentId"))).findFirst().ifPresent(agent -> {
                        item.put("agentName", agent.get("name"));
                        if (key.equals("messages")) item.put("agentAvatar", agent.get("avatar"));
                    });
                }
            }
            Map<String, Object> current = session(state);
            for (Map<String, Object> task : tasks(state)) {
                if (!task.containsKey("reminderEligible")) {
                    boolean eligible = true;
                    for (Map<String, Object> source : list(state, "messages")) {
                        if (Objects.equals(task.get("sourceMessageId"), source.get("id"))) {
                            try { eligible = java.time.Duration.between(OffsetDateTime.parse(source.get("sentAt").toString()), OffsetDateTime.parse(task.get("dueAt").toString())).toSeconds() > 900; }
                            catch (RuntimeException ignored) { /* Preserve legacy reminders with missing dates. */ }
                            break;
                        }
                    }
                    task.put("reminderEligible", eligible);
                }
            }
            current.put("emotionAnalysisOwner", "server");
            boolean actualHumanReply = list(state, "messages").stream().anyMatch(message -> current.get("id").equals(message.get("sessionId"))
                    && "agent".equals(message.get("from")) && !Boolean.TRUE.equals(message.get("automatic"))
                    && !Objects.toString(message.get("text"), "").matches("^我是客服\\d+，很高兴为您服务[！!]?$") );
            boolean legacy = !current.containsKey("mode") || ("HUMAN".equals(current.get("mode")) && !current.containsKey("humanStartedAt") && !actualHumanReply);
            if (legacy && !"closed".equals(current.get("status")) && !actualHumanReply) {
                boolean requested = list(state, "messages").stream().anyMatch(message -> current.get("id").equals(message.get("sessionId"))
                    && "user".equals(message.get("from")) && ReceptionRules.humanRequested(Objects.toString(message.get("text"), "")));
                current.put("mode", requested ? "HUMAN_PENDING" : "BOT");
                current.put("status", requested ? "waiting" : "bot");
                current.put("agentId", ""); current.put("agentName", "");
            } else if (!current.containsKey("mode")) current.put("mode", "HUMAN");
            if (!current.containsKey("emotionScore")) current.put("emotionScore", 30);
            list(state, "messages").stream().filter(message -> "user".equals(message.get("from")) && current.get("id").equals(message.get("sessionId")))
                    .reduce((a,b) -> b).ifPresent(message -> current.put("latestUserMessageId", message.get("id")));
        }
        if (!customers.isEmpty()) save();
    }
    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("reception.json.tmp");
        mapper.writeValue(temp.toFile(), customers);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
    synchronized Object responseSnapshot(Object body) { return body == null ? null : mapper.convertValue(body, Object.class); }
    private boolean visibleTo(Map<String, Object> state, Map<String, Object> agent) {
        Map<String, Object> current = session(state);
        return agent.get("id").equals(current.get("agentId")) || List.of("bot", "waiting").contains(current.get("status"));
    }
    @GetMapping("/pins")
    public synchronized List<String> pins(@RequestHeader(value="Authorization", required=false) String authorization) {
        return List.copyOf(pinsByAgent.getOrDefault(identity(authorization).get("id").toString(), List.of()));
    }
    @PostMapping("/{id}/pin")
    public synchronized List<String> pin(@PathVariable("id") String id, @RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, Boolean> body) throws IOException {
        Map<String, Object> agent = identity(authorization), state = customer(id);
        String agentId = agent.get("id").toString();
        List<String> pinned = new ArrayList<>(pinsByAgent.getOrDefault(agentId, List.of()));
        if (Boolean.TRUE.equals(body.get("pinned"))) {
            if (!visibleTo(state, agent)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能置顶其他客服的会话");
            if (!pinned.contains(id)) {
                if (pinned.size() >= 10) throw new ResponseStatusException(HttpStatus.CONFLICT, "最多置顶10位客户，请先取消一个置顶");
                pinned.add(id);
            }
        } else pinned.remove(id);
        pinsByAgent.put(agentId, pinned);
        Files.createDirectories(pinsFile.getParent());
        Path temp = pinsFile.resolveSibling("reception-pins.json.tmp");
        mapper.writeValue(temp.toFile(), pinsByAgent);
        Files.move(temp, pinsFile, StandardCopyOption.REPLACE_EXISTING);
        return List.copyOf(pinned);
    }
    @SuppressWarnings("unchecked")
    private Map<String, String> readCursors(Map<String, Object> state) {
        return (Map<String, String>)state.computeIfAbsent("readByAgent", key -> new LinkedHashMap<String, String>());
    }
    private int unreadCount(Map<String, Object> state, String agentId) {
        List<Map<String, Object>> messages = list(state, "messages");
        String cursor = readCursors(state).get(agentId);
        int count = 0;
        for (Map<String, Object> message : messages) {
            if (Objects.equals(cursor, message.get("id"))) { count = 0; continue; }
            if (session(state).get("id").equals(message.get("sessionId")) && "user".equals(message.get("from"))) count++;
        }
        return count;
    }
    @PostMapping("/{id}/read")
    public synchronized Map<String, Object> read(@PathVariable("id") String id, @RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, String> body) throws IOException {
        Map<String, Object> agent = identity(authorization), state = customer(id);
        if (!visibleTo(state, agent)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能读取其他客服的会话");
        String messageId = body.get("messageId");
        List<Map<String, Object>> messages = list(state, "messages");
        int requested = -1, previous = -1;
        String agentId = agent.get("id").toString(), cursor = readCursors(state).get(agentId);
        for (int i = 0; i < messages.size(); i++) {
            if (Objects.equals(messageId, messages.get(i).get("id")) && "user".equals(messages.get(i).get("from"))) requested = i;
            if (Objects.equals(cursor, messages.get(i).get("id"))) previous = i;
        }
        if (requested < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已读来源消息不存在");
        if (requested > previous) { readCursors(state).put(agentId, messageId); save(); }
        return Map.of("unreadCount", unreadCount(state, agentId));
    }
    private Map<String, Object> identity(String authorization) {
        String id = tokens.get(authorization == null ? "" : authorization.replaceFirst("^Bearer ", ""));
        return agents.stream().filter(agent -> agent.get("id").equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录客服账号"));
    }
    private String token(String authorization) { return authorization == null ? "" : authorization.replaceFirst("^Bearer ", ""); }
    private boolean online(String agentId) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(60);
        return tokens.entrySet().stream().anyMatch(entry -> entry.getValue().equals(agentId)
                && heartbeats.getOrDefault(entry.getKey(), OffsetDateTime.MIN).isAfter(cutoff));
    }
    private long workload(String agentId) {
        return customers.values().stream().map(this::session)
                .filter(item -> agentId.equals(item.get("agentId")) && List.of("active", "processing").contains(item.get("status"))).count();
    }
    private void assign(Map<String, Object> state, Map<String, Object> session, Map<String, Object> agent) {
        session.put("status", "active");
        session.put("agentId", agent.get("id"));
        session.put("agentName", agent.get("name"));
        session.put("claimedAt", OffsetDateTime.now().toString());
        session.put("lastActivityAt", OffsetDateTime.now().toString());
        state.put("previousAgentId", agent.get("id"));
        session.put("mode", "HUMAN_PENDING");
    }
    private boolean route(Map<String, Object> state) {
        Map<String, Object> current = session(state);
        if (!"waiting".equals(current.get("status"))) return false;
        List<Map<String, Object>> available = agents.stream().filter(agent -> online(agent.get("id").toString()) && workload(agent.get("id").toString()) < maxConversations).toList();
        if (available.isEmpty()) return false;
        Object previous = state.get("previousAgentId");
        if (previous == null) {
            List<Map<String, Object>> sessions = list(state, "sessions");
            for (int i = sessions.size() - 1; i >= 0; i--) {
                if (!Objects.toString(sessions.get(i).get("agentId"), "").isBlank()) { previous = sessions.get(i).get("agentId"); break; }
            }
        }
        final Object preferred = previous;
        Map<String, Object> chosen = available.stream().filter(agent -> agent.get("id").equals(preferred)).findFirst()
                .orElseGet(() -> available.stream().min(Comparator.comparingLong(agent -> workload(agent.get("id").toString()))).orElseThrow());
        assign(state, current, chosen);
        return true;
    }
    private void expire(Map<String, Object> state) {
        Map<String, Object> current = session(state);
        if (List.of("closed", "waiting", "processing").contains(current.get("status"))) return;
        if (Boolean.TRUE.equals(current.get("immediateTodo")) || Boolean.TRUE.equals(current.get("botPending"))) return;
        if (tasks(state).stream().anyMatch(task -> "OPEN".equals(task.get("status")) && task.get("dueAt") != null
                && !Boolean.FALSE.equals(task.get("reminderEligible"))
                && !OffsetDateTime.parse(task.get("dueAt").toString()).isAfter(OffsetDateTime.now().plusMinutes(15)))) return;
        List<Map<String, Object>> messages = list(state, "messages").stream().filter(message -> current.get("id").equals(message.get("sessionId"))).toList();
        Map<String, Object> lastUser = messages.stream().filter(message -> "user".equals(message.get("from"))).reduce((a,b) -> b).orElse(null);
        if (lastUser == null) return;
        int userIndex = messages.indexOf(lastUser);
        boolean unanswered = ReceptionRules.meaningful(lastUser) && messages.subList(userIndex + 1, messages.size()).stream()
                .noneMatch(message -> "agent".equals(message.get("from")) && !List.of("demo-promotion", "bot-welcome", "handoff-request").contains(Objects.toString(message.get("kind"), "")));
        if (unanswered) return;
        OffsetDateTime last = OffsetDateTime.parse(lastUser.get("sentAt").toString());
        for (Map<String, Object> message : messages.subList(userIndex + 1, messages.size())) {
            if ("agent".equals(message.get("from")) && !List.of("demo-promotion", "handoff-request").contains(Objects.toString(message.get("kind"), ""))) {
                OffsetDateTime sent = OffsetDateTime.parse(message.get("sentAt").toString());
                if (sent.isAfter(last)) last = sent;
            }
        }
        if (last.isBefore(OffsetDateTime.now().minusMinutes(idleMinutes))) {
            append(state, "agent", "亲，这边看到您没有回复，暂时结束对话了，后续有问题可以继续联系客服哦~", "closure");
            current.put("status", "closed");
            current.put("endedAt", OffsetDateTime.now().toString());
            current.put("endReason", "idle_timeout");
        }
    }
    @Scheduled(fixedDelay = 10000)
    public synchronized void sweep() throws IOException {
        String before = mapper.writeValueAsString(customers);
        customers.values().forEach(this::expire);
        customers.values().forEach(this::route);
        if (!before.equals(mapper.writeValueAsString(customers))) save();
    }
    @PostMapping("/logout")
    public synchronized Map<String, Boolean> logout(@RequestHeader(value="Authorization", required=false) String authorization) {
        heartbeats.remove(token(authorization)); tokens.remove(token(authorization));
        return Map.of("success", true);
    }
    @PostMapping("/presence")
    public synchronized Map<String, Boolean> presence(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, Boolean> body) throws IOException {
        identity(authorization);
        if (Boolean.TRUE.equals(body.get("online"))) heartbeats.put(token(authorization), OffsetDateTime.now());
        else heartbeats.remove(token(authorization));
        sweep();
        return Map.of("success", true);
    }
    private Map<String, Object> customer(String id) {
        Map<String, Object> result = customers.get(id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return result;
    }
    @GetMapping("/agents")
    public List<Map<String, Object>> agents() { return agents; }
    @PostMapping("/login")
    public synchronized Map<String, Object> login(@RequestBody Map<String, String> body) {
        if (!"123456".equals(body.get("password"))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        Map<String, Object> agent = agents.stream().filter(item -> item.get("id").equals(body.get("account"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误"));
        String token = UUID.randomUUID().toString();
        tokens.put(token, agent.get("id").toString());
        return Map.of("token", token, "agent", agent);
    }
    @PostMapping("/bootstrap")
    public synchronized Collection<Map<String, Object>> bootstrap(@RequestBody List<Map<String, Object>> input) throws IOException {
        for (Map<String, Object> item : input) {
            String id = item.get("id").toString();
            if (customers.containsKey(id)) continue;
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("id", item.get("id"));
            state.put("name", item.get("name"));
            state.put("sessions", new ArrayList<Map<String, Object>>());
            state.put("messages", new ArrayList<Map<String, Object>>());
            newSession(state);
            customers.put(id, state);
        }
        save();
        return customers.values();
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> state, String key) { return (List<Map<String, Object>>) state.get(key); }
    private Map<String, Object> newSession(Map<String, Object> state) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", UUID.randomUUID().toString());
        session.put("status", "bot");
        session.put("mode", "BOT");
        session.put("emotionScore", 30);
        session.put("emotionAnalysisOwner", "server");
        session.put("emotionAnalysisPending", false);
        session.put("agentId", "");
        session.put("agentName", "");
        session.put("startedAt", OffsetDateTime.now().toString());
        list(state, "sessions").add(session);
        state.put("session", session);
        return session;
    }
    private Map<String, Object> session(Map<String, Object> state) {
        List<Map<String, Object>> sessions = list(state, "sessions");
        Map<String, Object> current = sessions.get(sessions.size() - 1);
        state.put("session", current);
        return current;
    }
    @GetMapping
    public synchronized Collection<Map<String, Object>> state(@RequestHeader(value="Authorization", required=false) String authorization) throws IOException {
        sweep();
        customers.values().forEach(this::session);
        if (authorization == null || authorization.isBlank()) return customers.values();
        String agentId = identity(authorization).get("id").toString();
        return customers.values().stream().map(state -> {
            Map<String, Object> copy = new LinkedHashMap<>(state);
            copy.put("unreadCount", unreadCount(state, agentId));
            return copy;
        }).toList();
    }
    @PostMapping("/{id}/claim")
    public synchronized Map<String, Object> claim(@PathVariable String id, @RequestHeader(value="Authorization", required=false) String authorization) throws IOException {
        Map<String, Object> agent = identity(authorization);
        Map<String, Object> state = customer(id), session = session(state);
        if (!List.of("waiting", "bot").contains(session.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话已被领取或已结束");
        assign(state, session, agent);
        save();
        return state;
    }
    @PostMapping("/{id}/end")
    public synchronized Map<String, Object> end(@PathVariable String id, @RequestHeader(value="Authorization", required=false) String authorization) throws IOException {
        Map<String, Object> agent = identity(authorization);
        Map<String, Object> state = customer(id), session = session(state);
        if (!List.of("active", "processing").contains(session.get("status")) || !agent.get("id").equals(session.get("agentId"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有接待客服能结束会话");
        session.put("status", "closed");
        session.put("endedAt", OffsetDateTime.now().toString());
        save();
        return state;
    }
    @PostMapping("/{id}/status")
    public synchronized Map<String, Object> status(@PathVariable String id, @RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, String> body) throws IOException {
        expire(customer(id));
        Map<String, Object> agent = identity(authorization), state = customer(id), current = session(state);
        String status = body.getOrDefault("status", "");
        if (!List.of("active", "processing").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的接待状态");
        if (!List.of("active", "processing").contains(current.get("status")) || !agent.get("id").equals(current.get("agentId"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "会话已结束或不属于当前客服");
        current.put("status", status);
        current.put("lastActivityAt", OffsetDateTime.now().toString());
        save(); return state;
    }
    @PostMapping("/{id}/messages")
    public synchronized Map<String, Object> send(@PathVariable String id, @RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, Object> body) throws IOException {
        Map<String, Object> state = customer(id);
        expire(state);
        Map<String, Object> session = session(state);
        boolean agentMessage = "agent".equals(body.get("from"));
        Map<String, Object> agent = agentMessage ? identity(authorization) : null;
        String messageId = Objects.toString(body.get("id"), "");
        if (messageId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少消息编号");
        for (Map<String, Object> existing : list(state, "messages")) if (messageId.equals(existing.get("id"))) return state;
        if (agentMessage) {
            if (Objects.toString(body.get("text"), "").isBlank() && !(body.get("attachment") instanceof Map))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写消息或添加附件");
            if (List.of("bot", "waiting").contains(session.get("status"))) assign(state, session, agent);
            if (!List.of("active", "processing").contains(session.get("status")) || !agent.get("id").equals(session.get("agentId")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "会话已结束或由其他客服接待，无法回复");
        }
        if (!agentMessage && "closed".equals(session.get("status"))) session = newSession(state);
        if (!agentMessage && List.of("active", "processing").contains(session.get("status"))
                && !online(Objects.toString(session.get("agentId"), ""))) {
            session.put("status", "closed");
            session.put("endedAt", OffsetDateTime.now().toString());
            session.put("endReason", "agent_offline");
            session = newSession(state);
        }
        if (!agentMessage && "BOT".equals(session.get("mode")) && !Boolean.TRUE.equals(session.get("welcomed"))) open(id);
        Map<String, Object> message = new LinkedHashMap<>(body);
        for (String field : List.of("kind", "automatic", "agentId", "agentName", "agentAvatar")) message.remove(field);
        message.put("from", agentMessage ? "agent" : "user");
        message.put("sessionId", session.get("id"));
        message.put("sentAt", OffsetDateTime.now().toString());
        if (agent != null) {
            if (!"HUMAN".equals(session.get("mode"))) {
                session.put("mode", "HUMAN");
                session.put("botPending", false);
                session.put("humanStartedAt", OffsetDateTime.now().toString());
                append(state, "system", agent.get("name") + "正在为您服务", "handoff");
            }
            message.put("agentId", agent.get("id"));
            message.put("agentName", agent.get("name"));
            message.put("agentAvatar", agent.get("avatar"));
        }
        list(state, "messages").add(message);
        session.put("lastActivityAt", OffsetDateTime.now().toString());
        if (agentMessage) {
            ReceptionRules.deadline(Objects.toString(message.get("text"), ""), OffsetDateTime.parse(message.get("sentAt").toString()))
                .ifPresent(due -> addTask(state, "承诺跟进：" + message.get("text"), due, messageId));
            if (!Objects.toString(message.get("text"), "").isBlank()) message.put("commitmentPending", true);
        } else {
            String text = Objects.toString(message.get("text"), "");
            session.put("latestUserMessageId", messageId);
            session.put("emotionAnalysisPending", true);
            if (ReceptionRules.meaningful(message)) session.put("immediateTodo", true);
            // 规则只用于即时安全分流，不把临时规则分覆盖到展示用的模型评分。
            int triageScore = ReceptionRules.emotionScore(text);
            if ("BOT".equals(session.get("mode"))) {
                if (ReceptionRules.humanRequested(text) || triageScore >= 70 || com.beautycare.workflow.RuleSignals.isSafetyEvent(text)) {
                    requestHuman(state);
                } else {
                    session.put("botPending", true);
                }
            }
            route(state);
        }
        save();
        return state;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tasks(Map<String, Object> state) {
        return (List<Map<String, Object>>)state.computeIfAbsent("commitments", key -> new ArrayList<Map<String, Object>>());
    }
    private void append(Map<String, Object> state, String from, String text, String kind) {
        Map<String, Object> current = session(state), message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString()); message.put("from", from); message.put("text", text);
        message.put("kind", kind); message.put("automatic", true); message.put("sessionId", current.get("id"));
        message.put("sentAt", OffsetDateTime.now().toString());
        message.put("agentName", "HUMAN".equals(current.get("mode")) ? current.get("agentName") : "客服机器人");
        message.put("agentAvatar", "AI");
        list(state, "messages").add(message);
    }
    private void requestHuman(Map<String, Object> state) {
        Map<String, Object> current = session(state);
        if (!"BOT".equals(current.get("mode"))) return;
        current.put("mode", "HUMAN_PENDING"); current.put("status", "waiting"); current.put("botPending", false);
        current.put("handoffAt", OffsetDateTime.now().toString());
        append(state, "agent", "正在为您转接人工客服，请您稍等。", "handoff-request");
        route(state);
    }
    @PostMapping("/{id}/open")
    public synchronized Map<String, Object> open(@PathVariable("id") String id) throws IOException {
        Map<String, Object> state = customer(id), current = session(state);
        if ("closed".equals(current.get("status"))) current = newSession(state);
        if ("BOT".equals(current.get("mode")) && !"closed".equals(current.get("status"))) {
            if (!Boolean.TRUE.equals(current.get("welcomed"))) {
                current.put("welcomed", true);
                append(state, "agent", "亲爱的，有什么可以帮您？", "bot-welcome");
            }
            if (!Boolean.TRUE.equals(current.get("promotionsSent"))) {
                appendPromotion(state, "coupon", "专属优惠券", "¥20", "满199元可用", "查看优惠券", "虚构演示优惠券，不可领取或核销，不构成实际优惠承诺。");
                appendPromotion(state, "activity", "近期活动", "护肤搭配体验周", "了解洁面、补水与屏障护理的搭配思路", "查看活动", "这是虚构活动，仅用于演示客服机器人主动推送近期活动的流程。");
                appendPromotion(state, "monthly", "当月特惠", "第二件8折", "指定演示商品 · 当月精选", "查看特惠", "这是虚构特惠，不提供真实下单或折扣结算。");
                current.put("promotionsSent", true);
            }
            save();
        }
        return state;
    }
    private void appendPromotion(Map<String, Object> state, String type, String title, String value, String subtitle, String action, String details) {
        append(state, "agent", "【演示" + title + "】" + value + "，" + subtitle + "。" + details, "demo-promotion");
        Map<String, Object> message = list(state, "messages").get(list(state, "messages").size() - 1);
        message.put("card", Map.of("type", type, "title", title, "value", value, "subtitle", subtitle, "action", action, "details", details));
    }
    private void requireOwner(Map<String, Object> state, String authorization) {
        Map<String, Object> agent = identity(authorization), current = session(state);
        if (!agent.get("id").equals(current.get("agentId")) || "closed".equals(current.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话不属于当前客服或已结束");
    }
    private void addTask(Map<String, Object> state, String description, OffsetDateTime due, String sourceId) {
        if (tasks(state).stream().anyMatch(task -> sourceId.equals(task.get("sourceMessageId")))) return;
        Map<String, Object> source = list(state, "messages").stream().filter(message -> sourceId.equals(message.get("id"))).findFirst().orElse(null);
        OffsetDateTime sent = source == null ? OffsetDateTime.now() : OffsetDateTime.parse(source.get("sentAt").toString());
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", UUID.randomUUID().toString()); task.put("description", description);
        task.put("dueAt", due == null ? null : due.toString()); task.put("status", "OPEN"); task.put("sourceMessageId", sourceId);
        task.put("sessionId", source == null ? session(state).get("id") : source.get("sessionId")); task.put("revision", 1);
        task.put("agentId", source == null ? session(state).get("agentId") : source.get("agentId"));
        task.put("reminderEligible", due != null && java.time.Duration.between(sent, due).toSeconds() > 900);
        task.put("extractionSource", source == null ? "MANUAL" : "RULE");
        tasks(state).add(task);
    }
    @PostMapping("/{id}/commitments")
    public synchronized Map<String, Object> commitment(@PathVariable("id") String id, @RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, String> body) throws IOException {
        Map<String, Object> state = customer(id); requireOwner(state, authorization);
        OffsetDateTime due = parseFuture(body.get("dueAt"));
        String description = Objects.toString(body.get("description"), "").strip();
        if (description.isBlank() || description.length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写待办事项（最多1000字）");
        addTask(state, description, due, "manual-" + UUID.randomUUID()); save(); return state;
    }
    private OffsetDateTime parseFuture(String value) {
        try {
            OffsetDateTime due = OffsetDateTime.parse(value);
            if (!due.isAfter(OffsetDateTime.now()) || due.isAfter(OffsetDateTime.now().plusDays(90))) throw new IllegalArgumentException();
            return due;
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择未来90天内的承诺时间"); }
    }
    @PostMapping("/{id}/commitments/{taskId}")
    public synchronized Map<String, Object> updateCommitment(@PathVariable("id") String id, @PathVariable("taskId") String taskId, @RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, String> body) throws IOException {
        Map<String, Object> state = customer(id);
        Map<String, Object> task = tasks(state).stream().filter(item -> taskId.equals(item.get("id"))).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "待办不存在"));
        Map<String, Object> agent = identity(authorization);
        if (!agent.get("id").equals(task.getOrDefault("agentId", session(state).get("agentId"))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有承诺跟进的客服能修改提醒");
        if (!"OPEN".equals(task.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "该提醒已结束");
        if (!Objects.toString(task.get("revision")).equals(body.get("revision"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "待办已更新，请刷新后再操作");
        if ("end".equals(body.get("action"))) { task.put("status", "ENDED"); task.put("endedAt", OffsetDateTime.now().toString()); }
        else if ("postpone".equals(body.get("action"))) {
            int delay;
            try { delay = Integer.parseInt(body.get("delayMinutes")); }
            catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择延后时长"); }
            if (!List.of(15, 30, 45, 60, 90, 120, 180, 240, 360, 720, 1440, 2880, 4320, 10080).contains(delay))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的延后时长");
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime previous = task.get("dueAt") == null ? now : OffsetDateTime.parse(task.get("dueAt").toString());
            OffsetDateTime due = (previous.isAfter(now) ? previous : now).plusMinutes(delay);
            String reason = Objects.toString(body.get("reason"), "").strip();
            if (reason.isBlank() || reason.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写延后原因（最多500字）");
            task.put("dueAt", due.toString()); task.put("reason", reason);
            task.put("reminderEligible", java.time.Duration.between(now, due).toSeconds() > 900);
            append(state, "agent", "很抱歉，关于“" + task.get("description") + "”，需要延后至" + due.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("MM月dd日 HH:mm")) + "。原因：" + reason + "。", "commitment-postponed");
            Map<String, Object> notice = list(state, "messages").get(list(state, "messages").size() - 1);
            notice.put("agentId", agent.get("id")); notice.put("agentName", agent.get("name")); notice.put("agentAvatar", agent.get("avatar"));
            notice.put("sessionId", task.get("sessionId"));
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效操作");
        task.put("revision", ((Number)task.get("revision")).intValue() + 1); save(); return state;
    }
    @PostMapping("/{id}/analysis")
    public synchronized void analysis(@PathVariable("id") String id, @RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String, Object> body) throws IOException {
        Map<String, Object> state = customer(id); requireOwner(state, authorization);
        Map<String, Object> current = session(state);
        if (!Objects.equals(body.get("messageId"), current.get("latestUserMessageId"))) return;
        if (!"server".equals(current.get("emotionAnalysisOwner")) && body.get("emotionScore") instanceof Number score)
            current.put("emotionScore", Math.max(0, Math.min(100, score.intValue())));
        if (body.get("immediateTodo") instanceof Boolean immediate) current.put("immediateTodo", immediate);
        save();
    }
    /** Snapshots leave the reception lock before any model/network call. */
    public synchronized List<Map<String, Object>> botJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Map<String, Object> state : customers.values()) {
            Map<String, Object> current = session(state);
            if (!"BOT".equals(current.get("mode")) || !Boolean.TRUE.equals(current.get("botPending")) || "closed".equals(current.get("status"))) continue;
            jobs.add(mapper.convertValue(state, new TypeReference<Map<String, Object>>() {}));
        }
        return jobs;
    }
    public synchronized List<Map<String, Object>> commitmentJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Map<String, Object> state : customers.values()) {
            List<Map<String, Object>> messages = list(state, "messages");
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> source = messages.get(i);
                if (!Boolean.TRUE.equals(source.get("commitmentPending")) || !"agent".equals(source.get("from")) || Boolean.TRUE.equals(source.get("automatic"))) continue;
                List<Map<String, Object>> context = messages.subList(Math.max(0, i - 8), i).stream()
                    .filter(m -> Objects.equals(source.get("sessionId"), m.get("sessionId")) && !Boolean.TRUE.equals(m.get("automatic")))
                    .map(m -> Map.<String, Object>of("from", Objects.toString(m.get("from"), ""), "text", Objects.toString(m.get("text"), ""))).toList();
                Map<String, Object> safeSource = Map.of("id", source.get("id"), "text", source.get("text"), "sentAt", source.get("sentAt"));
                jobs.add(mapper.convertValue(Map.of("customerId", state.get("id"), "messageId", source.get("id"), "source", safeSource, "context", context), new TypeReference<Map<String, Object>>() {}));
                if (jobs.size() >= 100) return jobs;
            }
        }
        return jobs;
    }
    public synchronized void finishCommitment(String id, String messageId, com.beautycare.workflow.CommitmentAnalyzer.Result result, String status) throws IOException {
        Map<String, Object> state = customer(id);
        Map<String, Object> source = list(state, "messages").stream().filter(m -> messageId.equals(m.get("id"))).findFirst().orElse(null);
        if (source == null || !Boolean.TRUE.equals(source.get("commitmentPending"))) return;
        Map<String, Object> existing = tasks(state).stream().filter(t -> messageId.equals(t.get("sourceMessageId"))).findFirst().orElse(null);
        if (result != null && (existing == null || ("OPEN".equals(existing.get("status")) && ((Number)existing.get("revision")).intValue() == 1))) {
            if (result.promised()) {
                if (existing == null) addTask(state, result.description(), result.dueAt(), messageId);
                else {
                    existing.put("description", result.description());
                    existing.put("dueAt", result.dueAt() == null ? null : result.dueAt().toString());
                    existing.put("reminderEligible", result.dueAt() != null && java.time.Duration.between(OffsetDateTime.parse(source.get("sentAt").toString()), result.dueAt()).toSeconds() > 900);
                    existing.put("revision", 2);
                }
                tasks(state).stream().filter(t -> messageId.equals(t.get("sourceMessageId"))).findFirst().ifPresent(t -> t.put("extractionSource", "MODEL"));
            } else if (existing != null) tasks(state).remove(existing);
        }
        source.put("commitmentPending", false); source.put("commitmentAnalysis", status);
        save();
    }
    public synchronized List<Map<String, Object>> emotionJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Map<String, Object> state : customers.values()) {
            Map<String, Object> current = session(state);
            if ("BOT".equals(current.get("mode")) || "closed".equals(current.get("status")) || current.get("latestUserMessageId") == null
                    || Objects.equals(current.get("latestUserMessageId"), current.get("emotionAnalyzedMessageId"))) continue;
            jobs.add(mapper.convertValue(state, new TypeReference<Map<String, Object>>() {}));
        }
        return jobs;
    }
    public synchronized boolean isBotTurn(String id, String sessionId, String messageId) {
        Map<String, Object> current = session(customer(id));
        return "BOT".equals(current.get("mode")) && !"closed".equals(current.get("status"))
            && sessionId.equals(current.get("id")) && messageId.equals(current.get("latestUserMessageId"));
    }
    public synchronized void finishEmotion(String id, String sessionId, String messageId, AgentController.RiskWorkflowResponse risk) throws IOException {
        Map<String, Object> state = customer(id), current = session(state);
        if ("closed".equals(current.get("status")) || !sessionId.equals(current.get("id")) || !messageId.equals(current.get("latestUserMessageId"))) return;
        current.put("emotionScore", risk.emotionScore()); current.put("emotion", risk.emotion());
        current.put("riskScore", risk.riskScore()); current.put("riskEvidence", risk.evidence());
        current.put("handoffRequired", risk.handoffRequired());
        // 每条消息只保存一次最终分数，不用模型重新估计的历史曲线覆盖已确认记录。
        List<Map<String, Object>> trend = new ArrayList<>();
        Object previousTrend = current.get("emotionTrend");
        if (previousTrend instanceof List<?> saved) {
            for (Object point : saved) if (point instanceof Map<?, ?> entry && !messageId.equals(entry.get("messageId"))) {
                Map<String, Object> copy = new LinkedHashMap<>();
                entry.forEach((key, value) -> copy.put(key.toString(), value));
                trend.add(copy);
            }
        }
        String sourceTime = list(state, "messages").stream().filter(message -> messageId.equals(message.get("id")))
                .map(message -> Objects.toString(message.get("sentAt"), "")).findFirst().orElse(OffsetDateTime.now().toString());
        trend.add(new LinkedHashMap<>(Map.of("messageId", messageId, "time", sourceTime, "score", risk.emotionScore())));
        current.put("emotionTrend", new ArrayList<>(trend.subList(Math.max(0, trend.size() - 6), trend.size())));
        current.put("emotionAnalyzedMessageId", messageId); current.put("emotionAnalysisPending", false);
        current.put("emotionUpdatedAt", OffsetDateTime.now().toString());
        if ("BOT".equals(current.get("mode")) && (risk.emotionScore() >= 40 || risk.handoffRequired())) requestHuman(state);
        save();
    }
    public synchronized void finishBot(String id, String sessionId, String sourceId, int score, String draft, String error) throws IOException {
        Map<String, Object> state = customer(id), current = session(state);
        if (!"BOT".equals(current.get("mode")) || "closed".equals(current.get("status")) || !sessionId.equals(current.get("id")) || !sourceId.equals(current.get("latestUserMessageId"))) return;
        current.put("emotionScore", score); current.put("botPending", false);
        if (score >= 40 || error != null) requestHuman(state);
        else if (draft != null && !draft.isBlank()) {
            append(state, "agent", draft, "bot-reply");
            current.put("immediateTodo", false);
        }
        save();
    }
}
