package com.beautycare.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class EmotionConsistencyTest {
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); System.out.println("PASS " + name); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var controller = new ReceptionController(mapper);
        controller.bootstrap(List.of(Map.of("id", 1, "name", "情绪一致性测试")));
        var state = controller.send("1", null, Map.of("id", "emotion-u1", "from", "user", "text", "我要投诉"));
        var session = (Map<String, Object>)state.get("session");
        String sessionId = session.get("id").toString();
        check(((Number)session.get("emotionScore")).intValue() == 30, "New message does not publish provisional rule score");
        check(Boolean.TRUE.equals(session.get("emotionAnalysisPending")), "Pending is visible until model completes");
        var risk = mapper.convertValue(Map.of("conversationId", 1, "emotion", "critical", "emotionScore", 85,
                "emotionTrend", List.of(Map.of("time", "10:00", "score", 3)), "riskScore", 80,
                "evidence", List.of(), "handoffRequired", true), AgentController.RiskWorkflowResponse.class);
        controller.finishEmotion("1", sessionId, "emotion-u1", risk);
        var trend = (List<Map<String, Object>>)session.get("emotionTrend");
        check(((Number)trend.get(trend.size() - 1).get("score")).intValue() == 85, "Stored latest trend point equals final 85, not model's inconsistent 3");
        check(!Boolean.TRUE.equals(session.get("emotionAnalysisPending")), "Score and pending state update together");
        controller.finishEmotion("1", sessionId, "emotion-u1", risk);
        check(((List<?>)session.get("emotionTrend")).size() == 1, "Repeated completion does not duplicate message point");
        controller.send("1", null, Map.of("id", "emotion-u2", "from", "user", "text", "请尽快处理"));
        check(((Number)session.get("emotionScore")).intValue() == 85, "Next pending turn preserves last confirmed score internally");
        controller.finishEmotion("1", sessionId, "emotion-u1", risk);
        check(Boolean.TRUE.equals(session.get("emotionAnalysisPending")), "Stale result cannot finish a newer message");
    }
}
