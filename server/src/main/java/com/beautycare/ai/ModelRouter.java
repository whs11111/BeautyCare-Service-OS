package com.beautycare.ai;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型路由：业务节点只声明“档位”（standard / light / reply / vision），由这里决定走哪家服务、哪个模型。
 * 支持阿里云百炼（千问）、DeepSeek 以及任意 OpenAI 兼容的本地服务（如 vLLM / Ollama 部署的 Qwen LoRA 模型）。
 *
 * provider=auto 时：配置了 DeepSeek Key 用 DeepSeek，否则用千问；视觉档位只能用千问 VL（DeepSeek 无视觉模型）。
 * local-nodes 中列出的节点（如 risk,profile）改由本地 LoRA 小模型处理，用于蒸馏后的低成本运行。
 */
@Component
public class ModelRouter {

    public static final String STANDARD = "standard";
    public static final String LIGHT = "light";
    public static final String REPLY = "reply";
    public static final String VISION = "vision";

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final String textProvider;
    private final Set<String> localNodes;

    public ModelRouter(
            @Value("${ai.provider:auto}") String provider,
            @Value("${ai.qwen.base-url}") String qwenBaseUrl,
            @Value("${ai.qwen.api-key:}") String qwenKey,
            @Value("${ai.qwen.model:qwen-plus}") String qwenModel,
            @Value("${ai.qwen.light-model:qwen-turbo}") String qwenLight,
            @Value("${ai.qwen.reply-model:qwen-turbo}") String qwenReply,
            @Value("${ai.qwen.vision-model:qwen-vl-plus}") String qwenVision,
            @Value("${ai.deepseek.base-url:https://api.deepseek.com/v1}") String deepseekBaseUrl,
            @Value("${ai.deepseek.api-key:}") String deepseekKey,
            @Value("${ai.deepseek.model:deepseek-chat}") String deepseekModel,
            @Value("${ai.local.base-url:http://localhost:8000/v1}") String localBaseUrl,
            @Value("${ai.local.api-key:}") String localKey,
            @Value("${ai.local.model:beautycare-qwen-lora}") String localModel,
            @Value("${ai.local.nodes:}") String localNodes) {
        providers.put("qwen", new Provider("qwen", qwenBaseUrl, qwenKey,
                Map.of(STANDARD, qwenModel, LIGHT, qwenLight, REPLY, qwenReply, VISION, qwenVision)));
        // DeepSeek 只有一个通用对话模型，价格本身较低，三个文本档位共用 deepseek-chat。
        providers.put("deepseek", new Provider("deepseek", deepseekBaseUrl, deepseekKey,
                Map.of(STANDARD, deepseekModel, LIGHT, deepseekModel, REPLY, deepseekModel)));
        providers.put("local", new Provider("local", localBaseUrl, localKey.isBlank() ? "local" : localKey,
                Map.of(STANDARD, localModel, LIGHT, localModel, REPLY, localModel)));

        String normalized = provider == null ? "auto" : provider.trim().toLowerCase();
        if ("auto".equals(normalized)) {
            normalized = !deepseekKey.isBlank() ? "deepseek" : "qwen";
        }
        if (!providers.containsKey(normalized)) normalized = "qwen";
        this.textProvider = normalized;
        this.localNodes = Arrays.stream(localNodes.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toSet());
    }

    /** 按节点与档位解析实际调用端点；无可用 Key 时返回 null。 */
    public Endpoint resolve(String node, String tier) {
        Provider provider;
        if (VISION.equals(tier)) {
            provider = providers.get("qwen");
        } else if (localNodes.contains(node)) {
            provider = providers.get("local");
        } else {
            provider = providers.get(textProvider);
        }
        String model = provider.models().get(tier);
        if (model == null) {
            // 兼容旧调用：直接传入了具体模型名。
            model = tier;
        }
        if (provider.apiKey() == null || provider.apiKey().isBlank()) return null;
        return new Endpoint(provider.name(), provider.baseUrl(), provider.apiKey(), model);
    }

    public boolean textConfigured() {
        Provider provider = providers.get(textProvider);
        return provider.apiKey() != null && !provider.apiKey().isBlank();
    }

    public boolean visionConfigured() {
        Provider provider = providers.get("qwen");
        return provider.apiKey() != null && !provider.apiKey().isBlank();
    }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("textProvider", textProvider);
        result.put("textConfigured", textConfigured());
        result.put("visionProvider", "qwen");
        result.put("visionConfigured", visionConfigured());
        result.put("localNodes", List.copyOf(localNodes));
        Map<String, Object> models = new LinkedHashMap<>();
        for (String tier : List.of(STANDARD, LIGHT, REPLY)) {
            models.put(tier, providers.get(textProvider).models().get(tier));
        }
        models.put(VISION, providers.get("qwen").models().get(VISION));
        result.put("models", models);
        return result;
    }

    record Provider(String name, String baseUrl, String apiKey, Map<String, String> models) {
    }

    public record Endpoint(String provider, String baseUrl, String apiKey, String model) {
    }
}
