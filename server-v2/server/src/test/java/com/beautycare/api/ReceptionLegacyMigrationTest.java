package com.beautycare.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Migrates synthetic old reception files; never reads the real user's home. */
public class ReceptionLegacyMigrationTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReceptionController original = new ReceptionController(mapper);
        original.bootstrap(List.of(Map.of("id", 1, "name", "自动分配未回复"), Map.of("id", 2, "name", "已人工回复")));
        List<Map<String,Object>> rows = new ArrayList<>(original.state(null));
        for (Map<String,Object> row : rows) {
            Map<String,Object> session = (Map<String,Object>)row.get("session");
            session.remove("mode"); session.put("status", "active"); session.put("agentId", "agent2"); session.put("agentName", "客服02");
            List<Map<String,Object>> messages = (List<Map<String,Object>>)row.get("messages");
            messages.add(new LinkedHashMap<>(Map.of("id", "old-welcome-" + row.get("id"), "from", "agent", "text", "我是客服02，很高兴为您服务！", "automatic", true, "sessionId", session.get("id"), "sentAt", "2026-10-17T20:00:00+08:00")));
            if (row.get("id").equals(2)) messages.add(new LinkedHashMap<>(Map.of("id", "actual-human", "from", "agent", "text", "请您发来照片，我帮您核实", "sessionId", session.get("id"), "sentAt", "2026-10-17T20:01:00+08:00")));
        }
        Map<String,Map<String,Object>> data = new LinkedHashMap<>();
        rows.forEach(row -> data.put(row.get("id").toString(), row));
        mapper.writeValue(Path.of(System.getProperty("user.home"), ".beautycare-service-os", "reception.json").toFile(), data);
        ReceptionController migrated = new ReceptionController(mapper);
        Map<String,Object> robot = migrated.open("1");
        if (!"BOT".equals(((Map<?,?>)robot.get("session")).get("mode"))) throw new AssertionError("Unseen legacy assignment must become robot");
        List<Map<String,Object>> messages = (List<Map<String,Object>>)robot.get("messages");
        if (messages.stream().filter(message -> "demo-promotion".equals(message.get("kind")) && message.get("card") instanceof Map).count() != 3) throw new AssertionError("Three automatic promotion cards required");
        if (messages.stream().noneMatch(message -> "bot-welcome".equals(message.get("kind")) && "客服机器人".equals(message.get("agentName")))) throw new AssertionError("Robot welcome must identify sender");
        int size = messages.size(); migrated.open("1");
        if (messages.size() != size) throw new AssertionError("Refresh must not duplicate promotion messages");
        Map<String,Object> human = migrated.open("2");
        if (!"HUMAN".equals(((Map<?,?>)human.get("session")).get("mode"))) throw new AssertionError("Actual human session must remain human");
        if (((List<?>)human.get("messages")).size() != 2) throw new AssertionError("Actual human session must not receive robot promotion spam");
        System.out.println("PASS unseen legacy assignment becomes BOT, robot welcome and three cards, idempotent open, actual human session preserved");
    }
}
