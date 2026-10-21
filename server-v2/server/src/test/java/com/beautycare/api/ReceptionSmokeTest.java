package com.beautycare.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.server.ResponseStatusException;

/** Run against an isolated temporary home; never changes demo user data. */
public class ReceptionSmokeTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("reception-test-").toString());
        ReceptionController controller = new ReceptionController(new ObjectMapper());
        controller.bootstrap(List.of(Map.of("id", 1, "name", "测试用户")));
        String first = "Bearer " + controller.login(Map.of("account", "agent1", "password", "123456")).get("token");
        String second = "Bearer " + controller.login(Map.of("account", "agent2", "password", "123456")).get("token");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var results = executor.invokeAll(List.<Callable<Boolean>>of(
            () -> { try { controller.claim("1", first); return true; } catch (ResponseStatusException e) { return false; } },
            () -> { try { controller.claim("1", second); return true; } catch (ResponseStatusException e) { return false; } }
        ));
        executor.shutdown();
        if (results.stream().filter(result -> { try { return result.get(); } catch (Exception e) { throw new RuntimeException(e); } }).count() != 1) throw new AssertionError("Claim must have one winner");
        Map<String, Object> state = controller.state(null).iterator().next();
        Map<String, Object> session = (Map<String, Object>) state.get("session");
        String winner = "agent1".equals(session.get("agentId")) ? first : second;
        String loser = winner.equals(first) ? second : first;
        try { controller.send("1", loser, Map.of("id", "bad", "from", "agent", "text", "wrong")); throw new AssertionError("Non-owner can send"); }
        catch (ResponseStatusException expected) {}
        controller.send("1", winner, Map.of("id", "m1", "from", "agent", "text", "已处理"));
        String oldSession = session.get("id").toString();
        controller.end("1", winner);
        state = controller.send("1", null, Map.of("id", "m2", "from", "user", "text", "再次咨询"));
        session = (Map<String, Object>) state.get("session");
        if (!"bot".equals(session.get("status")) || oldSession.equals(session.get("id"))) throw new AssertionError("New consultation must reopen with robot in a new session");
        controller.claim("1", loser);
        controller.send("1", loser, Map.of("id", "m3", "from", "agent", "text", "由另一位客服接待"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) state.get("messages");
        Map<String, Object> firstReply = messages.stream().filter(item -> "m1".equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> secondReply = messages.stream().filter(item -> "m3".equals(item.get("id"))).findFirst().orElseThrow();
        if (firstReply.get("agentId").equals(secondReply.get("agentId"))) throw new AssertionError("Agent identity lost");
        if (messages.stream().filter(item -> "handoff".equals(item.get("kind"))).count() != 2) throw new AssertionError("Each first human reply needs one takeover notice");
        ReceptionController restored = new ReceptionController(new ObjectMapper());
        if (restored.state(null).size() != 1) throw new AssertionError("Persistence failed");
        System.setProperty("user.home", Files.createTempDirectory("reception-routing-test-").toString());
        ReceptionController routing = new ReceptionController(new ObjectMapper());
        routing.bootstrap(List.of(Map.of("id", 1, "name", "测试用户")));
        String token1 = "Bearer " + routing.login(Map.of("account", "agent1", "password", "123456")).get("token");
        String token2 = "Bearer " + routing.login(Map.of("account", "agent2", "password", "123456")).get("token");
        routing.presence(token1, Map.of("online", true)); routing.presence(token2, Map.of("online", true));
        state = routing.send("1", null, Map.of("id", "u1", "from", "user", "text", "转人工查询"));
        session = (Map<String, Object>) state.get("session");
        if (!"agent1".equals(session.get("agentId"))) throw new AssertionError("Automatic routing failed");
        routing.status("1", token1, Map.of("status", "processing"));
        routing.send("1", token1, Map.of("id", "a1", "from", "agent", "text", "您好"));
        routing.analysis("1", token1, Map.of("messageId", "u1", "immediateTodo", false));
        ((List<Map<String, Object>>)state.get("messages")).forEach(message -> message.put("sentAt", java.time.OffsetDateTime.now().minusHours(1).toString()));
        routing.sweep();
        if (!"processing".equals(session.get("status"))) throw new AssertionError("Processing must not expire");
        routing.status("1", token1, Map.of("status", "active"));
        ((List<Map<String, Object>>)state.get("messages")).forEach(message -> message.put("sentAt", java.time.OffsetDateTime.now().minusHours(1).toString()));
        routing.sweep();
        if (!"closed".equals(session.get("status"))) throw new AssertionError("Idle session must expire");
        try { routing.send("1", token1, Map.of("id", "blocked", "from", "agent", "text", "关闭后回复")); throw new AssertionError("Closed session allowed reply"); }
        catch (ResponseStatusException expected) {}
        routing.send("1", null, Map.of("id", "u2", "from", "user", "text", "转人工，再问一个问题"));
        session = (Map<String, Object>) state.get("session");
        if (!"agent1".equals(session.get("agentId"))) throw new AssertionError("Previous agent not preferred");
        routing.end("1", token1); routing.logout(token1);
        routing.send("1", null, Map.of("id", "u3", "from", "user", "text", "转人工重新咨询"));
        session = (Map<String, Object>) state.get("session");
        if (!"agent2".equals(session.get("agentId"))) throw new AssertionError("Offline fallback failed");
        routing.end("1", token2); routing.logout(token2);
        routing.send("1", null, Map.of("id", "u4", "from", "user", "text", "转人工，无人在线"));
        if (!"waiting".equals(((Map<String, Object>) state.get("session")).get("status"))) throw new AssertionError("No agents should queue");
        String back = "Bearer " + routing.login(Map.of("account", "agent2", "password", "123456")).get("token");
        routing.presence(back, Map.of("online", true));
        if (!"active".equals(((Map<String, Object>) state.get("session")).get("status"))) throw new AssertionError("Queued conversation not routed on heartbeat");
        System.out.println("PASS: atomic claim, ownership, new session, agent identities, persistence");
        System.out.println("PASS: auto routing, preferred agent, offline fallback, processing exemption, idle close, reply rejection, queued routing");
    }
}
