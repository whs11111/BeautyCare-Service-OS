package com.beautycare.workflow;

import com.beautycare.ai.*;
import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.client.RestClient;

/** Local stub only: verifies actual HTTP routing without cloud calls or real credentials. */
public class AllMessageEmotionTest {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var calls = new AtomicInteger();
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", exchange -> {
            try {
                var request = mapper.readTree(exchange.getRequestBody());
                if (!"beautycare-qwen-lora".equals(request.path("model").asText())) throw new AssertionError("Wrong model");
                calls.incrementAndGet();
                String content = "{\"emotion\":\"neutral\",\"emotionScore\":23,\"emotionTrend\":[],\"riskScore\":0,\"evidence\":[]}";
                byte[] body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content))),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        stub.start();
        try {
            String baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            var router = new ModelRouter("deepseek", "https://invalid.example", "", "unused", "unused", "unused", "unused",
                    "https://invalid.example", "test-key-never-sent", "deepseek-chat", baseUrl, "", "beautycare-qwen-lora", "risk");
            if (!"local".equals(router.resolve("risk", ModelRouter.STANDARD).provider())) throw new AssertionError("risk must route to LoRA");
            var meter = new TokenMeter();
            var client = new ModelClient(RestClient.builder(), mapper, meter, router, new TrainingRecorder(mapper, false), 20, 1, 5);
            var workflow = new AssistanceWorkflowAiService(client, new ContextCompressor(mapper), meter, mapper, 6);
            int index = 0;
            for (String text : List.of("你好", "好的，谢谢", "服务很好", "我想查一下订单", "等了三天还没发货，我很不满意")) {
                var input = new AssistanceContextRequest(99000L + index, text, "",
                        List.of(Map.of("id", "all-msg-" + index, "role", "user", "text", text, "time", "10:00")),
                        Map.of(), Map.of(), List.of(), List.of());
                var result = workflow.analyzeRisk(input);
                if (!"LLM".equals(result.analysisMode())) throw new AssertionError("Message skipped LoRA: " + text);
                if (calls.get() != ++index) throw new AssertionError("Every distinct message must reach local model");
            }
            System.out.println("PASS all 5 greeting, confirmation, positive, neutral and negative messages reach LoRA HTTP endpoint");
            if (!"deepseek".equals(router.resolve("reply-draft", ModelRouter.REPLY).provider())) throw new AssertionError("Reply routing changed");
            System.out.println("PASS replies remain separately routed, no cloud request made");
        } finally { stub.stop(0); }
    }
}
