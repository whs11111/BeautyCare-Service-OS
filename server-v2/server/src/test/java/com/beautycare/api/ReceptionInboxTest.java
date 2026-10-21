package com.beautycare.api;

import com.beautycare.api.AgentController.*;
import com.beautycare.workflow.*;
import com.beautycare.skills.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.server.ResponseStatusException;

/** No model keys/network. Run with an isolated -Duser.home. */
public class ReceptionInboxTest {
    private static int checks;
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    private static String login(ReceptionController reception, String agent) { return "Bearer " + reception.login(Map.of("account", agent, "password", "123456")).get("token"); }
    private static Map<String,Object> state(ReceptionController reception, String auth, int id) throws Exception {
        return reception.state(auth).stream().filter(item -> item.get("id").equals(id)).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReceptionController reception = new ReceptionController(mapper);
        List<Map<String,Object>> customers = new ArrayList<>();
        for (int id = 1; id <= 13; id++) customers.add(Map.of("id", id, "name", "收件箱测试" + id));
        reception.bootstrap(customers);
        String auth = login(reception, "agent1"), second = login(reception, "agent2");
        reception.presence(auth, Map.of("online", true));
        for (int id = 1; id <= 10; id++) reception.pin(String.valueOf(id), auth, Map.of("pinned", true));
        check(reception.pins(auth).size() == 10, "Ten pins accepted");
        try { reception.pin("11", auth, Map.of("pinned", true)); throw new AssertionError("Eleventh pin accepted"); }
        catch (ResponseStatusException expected) { check(expected.getStatusCode().value() == 409, "Eleventh pin rejected server-side"); }
        reception.pin("1", second, Map.of("pinned", true));
        check(reception.pins(second).size() == 1 && reception.pins(auth).size() == 10, "Pins isolated by agent");
        reception.pin("10", auth, Map.of("pinned", false));
        ExecutorService parallel = Executors.newFixedThreadPool(2);
        var attempts = parallel.invokeAll(List.<Callable<Boolean>>of(
            () -> { try { reception.pin("11", auth, Map.of("pinned", true)); return true; } catch (ResponseStatusException e) { return false; } },
            () -> { try { reception.pin("12", auth, Map.of("pinned", true)); return true; } catch (ResponseStatusException e) { return false; } }
        ));
        parallel.shutdown();
        check(attempts.stream().filter(result -> { try { return result.get(); } catch(Exception e) { throw new RuntimeException(e); } }).count() == 1 && reception.pins(auth).size() == 10, "Concurrent pin requests cannot exceed cap");
        reception.open("1");
        reception.send("1", null, Map.of("id", "u1", "from", "user", "text", "订单还没发货"));
        reception.send("1", null, Map.of("id", "u2", "from", "user", "text", "请问什么时候发货"));
        check(state(reception, auth, 1).get("unreadCount").equals(2), "Unread counts all unseen customer messages");
        reception.read("1", auth, Map.of("messageId", "u1"));
        check(state(reception, auth, 1).get("unreadCount").equals(1), "Reading old cursor does not swallow newer message");
        reception.read("1", auth, Map.of("messageId", "u2"));
        reception.read("1", auth, Map.of("messageId", "u1"));
        check(state(reception, auth, 1).get("unreadCount").equals(0), "Read cursor cannot move backwards");
        check(state(reception, second, 1).get("unreadCount").equals(2), "Read receipt does not clear another agent's unread");
        reception.claim("1", auth);
        check(reception.emotionJobs().size() == 1, "Unselected human conversation is automatically queued for emotion analysis");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AssistanceWorkflowAiService ai = new AssistanceWorkflowAiService(null, null, null, mapper, 6) {
            @Override public RiskWorkflowResponse analyzeRisk(AssistanceContextRequest request) {
                calls.incrementAndGet(); started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Release missing"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return new RiskWorkflowResponse(request.conversationId(), "critical", 90,
                    List.of(new EmotionTrendPoint("10:00", 90)), 80, 900, List.of("对进度明显不满"), true, "FORCED_HANDOFF", "", OffsetDateTime.now().plusMinutes(10), "TEST");
            }
        };
        ReceptionBot worker = new ReceptionBot(reception, ai, new SkillRegistry(mapper));
        try {
            worker.tick(); check(started.await(3, TimeUnit.SECONDS), "Background analysis starts without a UI click");
            reception.send("1", auth, Map.of("id", "a1", "from", "agent", "text", "请您稍等，我帮您核实"));
            release.countDown();
            for (int i = 0; i < 100 && reception.emotionJobs().size() != 0; i++) Thread.sleep(10);
            Map<String,Object> session = (Map<String,Object>)state(reception, auth, 1).get("session");
            check(session.get("emotionScore").equals(90) && session.get("emotionAnalyzedMessageId").equals("u2"), "Agent reply during analysis does not discard customer's score");
            check(Boolean.FALSE.equals(session.get("emotionAnalysisPending")), "Background completion is published for row colors");
            worker.tick(); check(calls.get() == 1, "No duplicate risk request after turn completed");
            reception.send("1", null, Map.of("id", "u3", "from", "user", "text", "还是没有发货"));
            reception.finishEmotion("1", session.get("id").toString(), "u2", new RiskWorkflowResponse(1L, "neutral", 10, List.of(), 0, 0, List.of(), false, "NONE", "", OffsetDateTime.now(), "TEST"));
            check(!((Map<?,?>)state(reception, auth, 1).get("session")).get("emotionScore").equals(10), "Stale score cannot overwrite a newer customer message");
            check(reception.emotionJobs().size() == 1, "Next incoming message automatically schedules fresh analysis");
        } finally { release.countDown(); worker.close(); }
        ReceptionController restored = new ReceptionController(mapper);
        String restoredAuth = login(restored, "agent1");
        check(restored.pins(restoredAuth).size() == 10, "Pins survive backend restart");
        check(state(restored, restoredAuth, 1).get("unreadCount").equals(1), "Read cursor survives restart while newer message stays unread");
        System.out.println("ALL " + checks + " INBOX CHECKS PASSED");
    }
}
