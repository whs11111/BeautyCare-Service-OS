package com.beautycare.ai;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.beautycare.ai.ModelRouter.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI 兼容的大模型客户端（千问 / DeepSeek / 本地 vLLM、Ollama）。
 * 业务节点传入“档位”，由 {@link ModelRouter} 解析为具体服务与模型；同时负责结果缓存、Token 计量与一次瞬时错误重试。
 */
@Component
public class ModelClient {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final TokenMeter tokenMeter;
    private final ModelRouter router;
    private final TrainingRecorder trainingRecorder;
    private final ResponseCache cache;
    private final JdkClientHttpRequestFactory requestFactory;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public ModelClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            TokenMeter tokenMeter,
            ModelRouter router,
            TrainingRecorder trainingRecorder,
            @Value("${ai.cache.max-entries:500}") int cacheEntries,
            @Value("${ai.cache.ttl-minutes:30}") long cacheTtlMinutes,
            @Value("${ai.timeout-seconds:60}") long timeoutSeconds) {
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.tokenMeter = tokenMeter;
        this.router = router;
        this.trainingRecorder = trainingRecorder;
        this.cache = new ResponseCache(cacheEntries, cacheTtlMinutes * 60_000);
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        this.requestFactory = new JdkClientHttpRequestFactory(httpClient);
        this.requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
    }

    public boolean configured() {
        return router.textConfigured();
    }

    public boolean visionConfigured() {
        return router.visionConfigured();
    }

    public JsonNode generateJson(String systemPrompt, Object input) {
        return generateJson("default", systemPrompt, input, ModelRouter.STANDARD, null);
    }

    public JsonNode generateJson(String systemPrompt, Object input, String tier) {
        return generateJson("default", systemPrompt, input, tier, null);
    }

    /**
     * 带节点名的结构化调用。相同模型、提示词和输入在缓存有效期内直接复用结果，不重复计费。
     * usageSink 可选，用于工作流逐节点记录本次调用的模型与 Token。
     */
    public JsonNode generateJson(
            String node,
            String systemPrompt,
            Object input,
            String tier,
            Consumer<ModelUsage> usageSink) {
        Endpoint endpoint = require(node, tier);
        try {
            String userContent = objectMapper.writeValueAsString(input);
            String key = hash(endpoint.provider(), endpoint.model(), systemPrompt, userContent);
            ResponseCache.Entry cached = cache.get(key);
            if (cached != null) {
                tokenMeter.recordCacheHit(node, cached.promptTokens() + cached.completionTokens());
                emit(usageSink, new ModelUsage(node, endpoint.model(), 0, 0, 0, true));
                return cached.json();
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", endpoint.model());
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)));
            body.put("response_format", Map.of("type", "json_object"));
            body.put("temperature", 0.2);

            JsonNode json = call(node, key, endpoint, body, usageSink);
            trainingRecorder.record(node, endpoint.provider(), endpoint.model(), systemPrompt, userContent,
                    objectMapper.writeValueAsString(json));
            return json;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用大模型失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 多模态调用：图片以 data URL 传入视觉模型。图片在调用前已由调用方降采样以控制视觉 Token。
     */
    public JsonNode generateVisionJson(
            String node,
            String systemPrompt,
            String text,
            List<String> imageDataUrls,
            Consumer<ModelUsage> usageSink) {
        Endpoint endpoint = require(node, ModelRouter.VISION);
        try {
            String key = hash(endpoint.provider(), endpoint.model(), systemPrompt, text + "|" + String.join("|", imageDataUrls));
            ResponseCache.Entry cached = cache.get(key);
            if (cached != null) {
                tokenMeter.recordCacheHit(node, cached.promptTokens() + cached.completionTokens());
                emit(usageSink, new ModelUsage(node, endpoint.model(), 0, 0, 0, true));
                return cached.json();
            }

            List<Object> content = new ArrayList<>();
            for (String url : imageDataUrls) {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
            }
            content.add(Map.of("type", "text", "text", text));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", endpoint.model());
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", content)));
            body.put("temperature", 0.1);

            return call(node, key, endpoint, body, usageSink);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用视觉模型失败：" + exception.getMessage(), exception);
        }
    }

    private JsonNode call(
            String node,
            String cacheKey,
            Endpoint endpoint,
            Map<String, Object> body,
            Consumer<ModelUsage> usageSink) throws Exception {
        JsonNode response = post(endpoint, body);

        if (response == null || response.path("choices").isEmpty()) {
            throw new IllegalStateException("模型返回了空响应");
        }

        String content = response.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

        if (content.isBlank()) {
            throw new IllegalStateException("模型响应中没有可解析的内容");
        }

        JsonNode usage = response.path("usage");
        long promptTokens = usage.path("prompt_tokens").asLong(0);
        long completionTokens = usage.path("completion_tokens").asLong(0);
        // DeepSeek：prompt_cache_hit_tokens；千问：prompt_tokens_details.cached_tokens。命中部分按低价计费。
        long cachedPromptTokens = usage.has("prompt_cache_hit_tokens")
                ? usage.path("prompt_cache_hit_tokens").asLong(0)
                : usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        tokenMeter.recordCall(node, endpoint.provider() + ":" + endpoint.model(), promptTokens, completionTokens, cachedPromptTokens);
        emit(usageSink, new ModelUsage(node, endpoint.model(), promptTokens, completionTokens, cachedPromptTokens, false));

        JsonNode json = objectMapper.readTree(stripMarkdownCodeFence(content));
        cache.put(cacheKey, new ResponseCache.Entry(json, promptTokens, completionTokens, System.currentTimeMillis()));
        return json;
    }

    private JsonNode post(Endpoint endpoint, Map<String, Object> body) {
        RestClient client = clients.computeIfAbsent(endpoint.baseUrl(), baseUrl -> restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build());
        RestClientException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return client.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + endpoint.apiKey())
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpClientErrorException exception) {
                // 4xx 中只有限流值得重试；鉴权、参数错误直接抛出。
                if (exception.getStatusCode().value() != 429) {
                    throw new IllegalStateException(endpoint.provider() + " 返回 " + exception.getStatusCode().value()
                            + "：" + abbreviate(exception.getResponseBodyAsString()), exception);
                }
                last = exception;
            } catch (RestClientException exception) {
                last = exception;
            }
            try {
                Thread.sleep(800);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException(endpoint.provider() + " 调用失败：" + (last == null ? "" : last.getMessage()), last);
    }

    private Endpoint require(String node, String tier) {
        Endpoint endpoint = router.resolve(node, tier);
        if (endpoint == null) {
            throw new IllegalStateException(ModelRouter.VISION.equals(tier)
                    ? "未配置视觉模型 Key（需要 DASHSCOPE_API_KEY，DeepSeek 不提供视觉模型）"
                    : "未配置大模型 API Key，请设置 DEEPSEEK_API_KEY 或 DASHSCOPE_API_KEY");
        }
        return endpoint;
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    private void emit(Consumer<ModelUsage> sink, ModelUsage usage) {
        if (sink != null) sink.accept(usage);
    }

    private String hash(String... parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String stripMarkdownCodeFence(String content) {
        String value = content.trim();
        if (!value.startsWith("```")) {
            return value;
        }

        value = value.replaceFirst("^```(?:json)?\\s*", "");
        return value.replaceFirst("\\s*```$", "").trim();
    }

    public record ModelUsage(
            String node,
            String model,
            long promptTokens,
            long completionTokens,
            long cachedPromptTokens,
            boolean cached) {
    }

    /** 线程安全的 LRU + TTL 结果缓存。 */
    static final class ResponseCache {
        private final int maxEntries;
        private final long ttlMillis;
        private final LinkedHashMap<String, Entry> entries;

        ResponseCache(int maxEntries, long ttlMillis) {
            this.maxEntries = maxEntries;
            this.ttlMillis = ttlMillis;
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ResponseCache.Entry> eldest) {
                    return size() > ResponseCache.this.maxEntries;
                }
            };
        }

        synchronized Entry get(String key) {
            Entry entry = entries.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.storedAt() > ttlMillis) {
                entries.remove(key);
                return null;
            }
            return entry;
        }

        synchronized void put(String key, Entry entry) {
            entries.put(key, entry);
        }

        record Entry(JsonNode json, long promptTokens, long completionTokens, long storedAt) {
        }
    }
}
