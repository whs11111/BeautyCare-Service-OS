package com.beautycare.workflow;

import java.util.*;
import com.beautycare.ai.*;
import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.client.RestClient;

/** Live LoRA check using synthetic chat only. Does not invoke any cloud API. */
public class LocalLoraSmokeTest {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); System.out.println("PASS " + label); }
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TokenMeter meter = new TokenMeter();
        String baseUrl = args.length == 0 ? "http://127.0.0.1:18000/v1" : args[0];
        ModelRouter router = new ModelRouter("deepseek", "https://invalid.example", "", "unused", "unused", "unused", "unused",
                "https://invalid.example", "test-key-never-sent", "deepseek-chat", baseUrl, "", "beautycare-qwen-lora", "risk");
        check(router.resolve("risk", ModelRouter.STANDARD).provider().equals("local"), "risk routes to LoRA");
        check(router.resolve("reply-draft", ModelRouter.REPLY).provider().equals("deepseek")
                && router.resolve("context", ModelRouter.STANDARD).provider().equals("deepseek"), "reply/context stay on DeepSeek");
        ContextCompressor compressor = new ContextCompressor(mapper);
        check(RuleSignals.needsSemanticModel(List.of("你们服务真好呀，下次别这么服务了")), "sarcasm enters semantic analysis");
        check(!RuleSignals.isSarcasm("谢谢，服务真好"), "genuine thanks is not sarcasm");
        ModelClient client = new ModelClient(RestClient.builder(), mapper, meter, router, new TrainingRecorder(mapper, false), 20, 1, 45);
        var workflow = new AssistanceWorkflowAiService(client, compressor, meter, mapper, 6);
        var sarcasm = new AssistanceContextRequest(99998L, "你们服务真好呀，下次别这么服务了", "",
                List.of(Map.of("id", "test-user-2", "role", "user", "text", "你们服务真好呀，下次别这么服务了", "time", "10:01")),
                Map.of(), Map.of(), List.of(), List.of());
        var risk = workflow.analyzeRisk(sarcasm);
        check(risk.analysisMode().equals("LLM"), "live risk does not silently fall back");
        check(risk.emotionScore() >= 0 && risk.emotionScore() <= 100, "live risk score in range");
        System.out.println("ALL PASSED");
    }
}
