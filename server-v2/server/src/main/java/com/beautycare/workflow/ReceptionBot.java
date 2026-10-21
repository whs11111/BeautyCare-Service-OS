package com.beautycare.workflow;

import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.beautycare.api.ReceptionController;
import com.beautycare.skills.SkillRegistry;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single server-owned auto sender. Network work never blocks the reception state lock. */
@Component
public class ReceptionBot {
    private final ReceptionController reception;
    private final AssistanceWorkflowAiService ai;
    private final SkillRegistry skills;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.AbortPolicy());
    public ReceptionBot(ReceptionController reception, AssistanceWorkflowAiService ai, SkillRegistry skills) {
        this.reception = reception; this.ai = ai; this.skills = skills;
    }
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        List<Map<String, Object>> jobs = new ArrayList<>(reception.botJobs());
        jobs.addAll(reception.emotionJobs());
        for (Map<String, Object> state : jobs) {
            String id = state.get("id").toString();
            if (retryAfter.getOrDefault(id, 0L) > System.currentTimeMillis()) continue;
            if (!running.add(id)) continue;
            try { executor.submit(() -> { try { answer(state); } finally { running.remove(id); } }); }
            catch (RejectedExecutionException ignored) { running.remove(id); }
        }
    }
    @SuppressWarnings("unchecked")
    private void answer(Map<String, Object> state) {
        Map<String, Object> session = (Map<String, Object>)state.get("session");
        String source = session.get("latestUserMessageId").toString();
        List<Map<String, Object>> messages = ((List<Map<String, Object>>)state.get("messages")).stream()
                .filter(message -> session.get("id").equals(message.get("sessionId")) && !"system".equals(message.get("from")))
                .map(message -> { Map<String, Object> result = new LinkedHashMap<>(message); result.put("role", message.get("from")); return result; }).toList();
        String text = messages.stream().filter(message -> source.equals(message.get("id"))).findFirst().map(message -> Objects.toString(message.get("text"), "")).orElse("");
        int score = RuleSignals.emotionScore(text);
        String draft = null, failure = null;
        boolean bot = "BOT".equals(session.get("mode"));
        try {
            AssistanceContextRequest request = new AssistanceContextRequest(Long.valueOf(state.get("id").toString()), text, "",
                    messages.subList(Math.max(0, messages.size() - 16), messages.size()), Map.of(), Map.of("name", state.get("name")), List.of(), List.of());
            // Every user-message emotion analysis goes through the configured risk model; draft routing stays independent.
            var risk = ai.analyzeRisk(request);
            score = risk.emotionScore();
            reception.finishEmotion(state.get("id").toString(), session.get("id").toString(), source, risk);
            retryAfter.remove(state.get("id").toString());
            if (bot && reception.isBotTurn(state.get("id").toString(), session.get("id").toString(), source) && score < 40) {
                if (text.matches("(?s).*(推荐|自测|测肤|肤质问卷).*"))
                    draft = "为了更好地了解您的肤质和护肤需求，请您点击聊天框上方的“产品推荐·自测”，填写一分钟问卷，完成后我再为您推荐合适的产品。";
                else if (text.matches("(?s).*(最近上新|新品|上新).*"))
                    draft = "目前收录的巴黎欧莱雅商品有第四代黑精华（参考价¥309）、小蜜罐面霜4.0滋润版（参考价¥339）。这些是商品资料快照，暂不能确认是否为近期上新，价格和库存请以官网页面为准。";
                else draft = ai.generateReplyDraft(request, skills.replyInsights(request), null).draft();
            }
        } catch (Exception e) {
            failure = "MODEL_UNAVAILABLE";
            retryAfter.put(state.get("id").toString(), System.currentTimeMillis() + 10000);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Automatic reception analysis unavailable for conversation {}", state.get("id"));
        }
        if (!bot) return;
        try { reception.finishBot(state.get("id").toString(), session.get("id").toString(), source, score, draft, failure); }
        catch (java.io.IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Robot response could not be persisted", e); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
