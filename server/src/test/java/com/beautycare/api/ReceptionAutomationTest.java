package com.beautycare.api;

import com.beautycare.workflow.*;
import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

/** Offline integration tests; pass -Duser.home=<isolated task directory>. */
public class ReceptionAutomationTest {
    private static int checks;
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ReceptionController controller = new ReceptionController(new ObjectMapper());
        controller.bootstrap(List.of(Map.of("id", 1, "name", "自动化测试用户"), Map.of("id", 2, "name", "隔离用户")));
        String auth = "Bearer " + controller.login(Map.of("account", "agent1", "password", "123456")).get("token");
        controller.presence(auth, Map.of("online", true));
        Map<String, Object> state = controller.open("1");
        Map<String, Object> session = (Map<String, Object>)state.get("session");
        List<Map<String, Object>> messages = (List<Map<String, Object>>)state.get("messages");
        check("BOT".equals(session.get("mode")), "Opening starts with robot, not automatic assignment");
        controller.open("1"); check(messages.size() == 4, "Welcome and three demo promotion cards are idempotent");
        check(messages.get(1).get("text").toString().contains("虚构"), "Promotions explicitly labelled fictional");
        Map<String, Object> responseSnapshot = (Map<String, Object>)controller.responseSnapshot(state);
        controller.send("1", null, Map.of("id", "u1", "from", "user", "text", "想买保湿产品"));
        check(((List<?>)responseSnapshot.get("messages")).size() == 4, "Response snapshot isolated from concurrent message writes");
        check(controller.botJobs().size() == 1, "Neutral customer message queues robot job");
        String sid = session.get("id").toString();
        controller.finishBot("1", sid, "stale-id", 30, "过期回复", null);
        check(messages.stream().noneMatch(m -> "过期回复".equals(m.get("text"))), "Stale robot reply rejected");
        controller.finishBot("1", sid, "u1", 30, "请填写肤质问卷", null);
        check(messages.get(messages.size()-1).get("text").equals("请填写肤质问卷"), "Robot can send its reply directly");
        controller.send("1", null, Map.of("id", "u2", "from", "user", "text", "转人工"));
        check("HUMAN_PENDING".equals(session.get("mode")) && "agent1".equals(session.get("agentId")), "Explicit handoff assigns online agent");
        check(messages.stream().noneMatch(m -> "handoff".equals(m.get("kind"))), "Assignment alone does not announce agent takeover");
        controller.send("1", auth, Map.of("id", "a1", "from", "agent", "text", "您好，我会在2小时内给您反馈"));
        check("HUMAN".equals(session.get("mode")), "First manual reply completes takeover");
        check(messages.stream().filter(m -> "handoff".equals(m.get("kind"))).count() == 1, "Exactly one grey takeover notice");
        int count = messages.size(); controller.finishBot("1", sid, "u2", 30, "不应发送", null);
        check(messages.size() == count, "No late automatic reply after human takeover");
        List<Map<String, Object>> tasks = (List<Map<String, Object>>)state.get("commitments");
        check(tasks.size() == 1, "Explicit agent promise creates countdown");
        Map<String, Object> task = tasks.get(0);
        OffsetDateTime originalDue = OffsetDateTime.parse(task.get("dueAt").toString());
        controller.updateCommitment("1", task.get("id").toString(), auth, Map.of("action", "postpone", "revision", "1", "delayMinutes", "60", "reason", "需要仓库进一步核实"));
        check(OffsetDateTime.parse(task.get("dueAt").toString()).equals(originalDue.plusMinutes(60)), "Delay duration extends original promise exactly");
        controller.send("1", auth, Map.of("id", "short-promise", "from", "agent", "text", "我会在15分钟内给您反馈"));
        check(tasks.size() == 2 && Boolean.FALSE.equals(tasks.get(1).get("reminderEligible")), "Fifteen minute promise recorded without reminder");
        controller.commitment("1", auth, Map.of("description", "短承诺", "dueAt", OffsetDateTime.now().plusMinutes(10).toString()));
        check(tasks.size() == 3 && Boolean.FALSE.equals(tasks.get(2).get("reminderEligible")), "Short manual promise recorded without reminder");
        check(messages.stream().anyMatch(m -> m.get("text").toString().contains("需要仓库进一步核实")), "Postponing informs customer with reason");
        check(task.get("revision").equals(2), "Postpone revision persists");
        try { controller.updateCommitment("1", task.get("id").toString(), auth, Map.of("action", "end", "revision", "1")); throw new AssertionError("Stale write accepted"); }
        catch (org.springframework.web.server.ResponseStatusException expected) { check(expected.getStatusCode().value() == 409, "Concurrent task edits rejected"); }
        controller.updateCommitment("1", task.get("id").toString(), auth, Map.of("action", "end", "revision", "2"));
        check("ENDED".equals(task.get("status")), "End disables subsequent reminder");
        controller.commitment("1", auth, Map.of("description", "明日后续反馈", "dueAt", OffsetDateTime.now().plusDays(1).toString()));
        controller.analysis("1", auth, Map.of("messageId", "u2", "immediateTodo", true));
        messages.forEach(m -> m.put("sentAt", OffsetDateTime.now().minusMinutes(20).toString()));
        controller.sweep(); check(!"closed".equals(session.get("status")), "Immediate unresolved matter prevents auto-close");
        controller.analysis("1", auth, Map.of("messageId", "u2", "immediateTodo", false));
        controller.sweep(); check("closed".equals(session.get("status")), "Answered idle conversation closes after 15 minutes");
        check(messages.get(messages.size()-1).get("text").equals("亲，这边看到您没有回复，暂时结束对话了，后续有问题可以继续联系客服哦~"), "Auto-close sends requested customer notice");
        check("OPEN".equals(tasks.get(3).get("status")), "Future follow-up survives idle closure");
        controller.updateCommitment("1", tasks.get(3).get("id").toString(), auth, Map.of("action", "end", "revision", "1"));
        check("ENDED".equals(tasks.get(3).get("status")), "Owner can manage follow-up even after chat closes");
        controller.send("2", null, Map.of("id", "u3", "from", "user", "text", "投诉！太慢了"));
        Map<String, Object> other = controller.state(null).stream().filter(item -> item.get("id").equals(2)).findFirst().orElseThrow();
        check("HUMAN_PENDING".equals(((Map<?,?>)other.get("session")).get("mode")), "Negative emotion triggers human handoff");
        List<Map<String, Object>> otherMessages = (List<Map<String, Object>>)other.get("messages");
        otherMessages.forEach(m -> m.put("sentAt", OffsetDateTime.now().minusHours(1).toString()));
        controller.sweep(); check(!"closed".equals(((Map<?,?>)other.get("session")).get("status")), "Unanswered valid customer message never expires");
        check(ReceptionRules.deadline("我会在半小时内反馈", OffsetDateTime.now()).isPresent(), "Half-hour promise recognized");
        check(ReceptionRules.deadline("我会尽快处理", OffsetDateTime.now()).isEmpty(), "Vague time not fabricated");
        var request = new AssistanceContextRequest(1L, "我吃了过敏药就好了，没有就医凭证，但我有过敏照片", "", List.of(), Map.of(), Map.of(), List.of(), List.of());
        var insights = Map.<String,Object>of("skills", List.of(Map.of("id", "adverse-reaction")));
        String reply = SkillReplyGuard.enforce(request, insights, "很抱歉让您不舒服了，您提到的照片可以作为初步核实材料，请您发来后我们进一步确认处理方式。");
        check(reply.contains("初步核实材料") && !reply.contains("就医凭证"), "Natural contextual model reply preserved");
        reply = SkillReplyGuard.enforce(request, insights, "请提供就医凭证");
        check(reply.contains("没有就医凭证") && !reply.contains("请提供就医凭证"), "No-certificate statement respected");
        ReceptionController restored = new ReceptionController(new ObjectMapper());
        check(restored.state(null).size() == 2, "Modes and commitments survive restart");
        controller.open("1");
        try { controller.send("1", auth, Map.of("id", "empty", "from", "agent", "text", "")); throw new AssertionError("Empty takeover accepted"); }
        catch (org.springframework.web.server.ResponseStatusException expected) { check(expected.getStatusCode().value() == 400, "Empty message cannot take over robot"); }
        check("BOT".equals(((Map<?, ?>)state.get("session")).get("mode")), "Robot remains active before actual message");
        controller.send("1", null, Map.of("id", "neutral-new", "from", "user", "text", "你好"));
        String newSid = ((Map<?, ?>)state.get("session")).get("id").toString();
        controller.send("1", auth, Map.of("id", "implicit-human", "from", "agent", "text", "您好，请问有什么可以帮您？"));
        check("HUMAN".equals(((Map<?, ?>)state.get("session")).get("mode")), "Sending directly takes over BOT without claim button");
        int afterTakeover = messages.size();
        controller.finishBot("1", newSid, "neutral-new", 20, "late robot", null);
        check(messages.size() == afterTakeover, "In-flight robot result rejected after implicit takeover");
        System.out.println("ALL " + checks + " CHECKS PASSED");
    }
}
