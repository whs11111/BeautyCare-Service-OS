package com.beautycare.api;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.beautycare.ai.TokenMeter;
import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.beautycare.api.AgentController.RiskWorkflowResponse;
import com.beautycare.workflow.AssistanceWorkflowAiService;
import com.beautycare.workflow.RuleSignals;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 量化评估：用标注样本（classpath:eval/cases.json）评估意图识别、情绪分级、转人工判断与 Token 成本。
 * mode=rule 只跑规则层（零 Token）；mode=cascade 跑实际的“规则→大模型”级联；mode=llm 强制全部走大模型作对照。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final ObjectMapper objectMapper;
    private final AssistanceWorkflowAiService workflowService;
    private final TokenMeter tokenMeter;

    public EvalController(ObjectMapper objectMapper, AssistanceWorkflowAiService workflowService, TokenMeter tokenMeter) {
        this.objectMapper = objectMapper;
        this.workflowService = workflowService;
        this.tokenMeter = tokenMeter;
    }

    @GetMapping("/cases")
    public List<EvalCase> cases() throws IOException {
        try (InputStream input = new ClassPathResource("eval/cases.json").getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<List<EvalCase>>() {});
        }
    }

    @PostMapping("/run")
    public EvalReport run(@RequestParam(defaultValue = "rule") String mode) throws IOException {
        List<EvalCase> cases = cases();
        List<CaseResult> results = new ArrayList<>();
        TokenMeter.Snapshot before = tokenMeter.snapshot();
        int intentHit = 0;
        int emotionHit = 0;
        int handoffHit = 0;
        int llmCalls = 0;

        for (int index = 0; index < cases.size(); index++) {
            EvalCase item = cases.get(index);
            List<String> texts = new ArrayList<>(item.history());
            texts.add(item.text());

            Set<String> predictedIntents = RuleSignals.intents(List.of(item.text()));
            boolean intentOk = predictedIntents.equals(new HashSet<>(item.intents()));

            String emotion;
            boolean handoff;
            String analysisMode;
            if ("rule".equals(mode)) {
                emotion = RuleSignals.emotionLevel(RuleSignals.emotionScore(item.text()));
                handoff = RuleSignals.containsAny(String.join(" ", texts), List.of("12315", "投诉", "曝光"))
                        && RuleSignals.containsAny(String.join(" ", texts), List.of("第三次", "多次联系", "没解决", "一直没"))
                        || texts.stream().anyMatch(RuleSignals::isSafetyEvent);
                analysisMode = "RULE";
            } else {
                List<Map<String, Object>> messages = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    messages.add(Map.of("id", item.id() + "-" + i, "role", "user", "text", texts.get(i),
                            "time", OffsetDateTime.now().minusMinutes(texts.size() - i).toString()));
                }
                // 为避免缓存/指纹复用影响对照，每次评估使用独立会话号。
                long conversationId = 900_000L + index + System.nanoTime() % 100_000 * 100;
                AssistanceContextRequest request = new AssistanceContextRequest(
                        conversationId, item.text(), null, messages, null, null, null, null);
                RiskWorkflowResponse risk = "llm".equals(mode)
                        ? workflowService.analyzeRiskWithModel(request)
                        : workflowService.analyzeRisk(request);
                emotion = risk.emotion();
                handoff = risk.handoffRequired();
                analysisMode = risk.analysisMode();
                if ("LLM".equals(analysisMode)) llmCalls++;
            }

            boolean emotionOk = emotion.equals(item.emotion());
            boolean handoffOk = handoff == item.handoff();
            if (intentOk) intentHit++;
            if (emotionOk) emotionHit++;
            if (handoffOk) handoffHit++;
            results.add(new CaseResult(item.id(), item.text(), List.copyOf(predictedIntents), item.intents(), intentOk,
                    emotion, item.emotion(), emotionOk, handoff, item.handoff(), handoffOk, analysisMode));
        }

        TokenMeter.Snapshot after = tokenMeter.snapshot();
        int total = cases.size();
        long tokens = (after.promptTokens() + after.completionTokens()) - (before.promptTokens() + before.completionTokens());
        return new EvalReport(
                mode,
                total,
                ratio(intentHit, total),
                ratio(emotionHit, total),
                ratio(handoffHit, total),
                llmCalls,
                ratio(llmCalls, total),
                tokens,
                total == 0 ? 0 : tokens / total,
                results);
    }

    private double ratio(int hit, int total) {
        return total == 0 ? 0 : Math.round(hit * 1000.0 / total) / 1000.0;
    }

    public record EvalCase(
            String id,
            List<String> history,
            String text,
            List<String> intents,
            String emotion,
            boolean handoff) {
    }

    public record CaseResult(
            String id,
            String text,
            List<String> predictedIntents,
            List<String> expectedIntents,
            boolean intentCorrect,
            String predictedEmotion,
            String expectedEmotion,
            boolean emotionCorrect,
            boolean predictedHandoff,
            boolean expectedHandoff,
            boolean handoffCorrect,
            String analysisMode) {
    }

    public record EvalReport(
            String mode,
            int cases,
            double intentAccuracy,
            double emotionAccuracy,
            double handoffAccuracy,
            int llmCalls,
            double llmCallRate,
            long tokensUsed,
            long tokensPerCase,
            List<CaseResult> results) {
    }
}
