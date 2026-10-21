package com.beautycare.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 蒸馏数据记录：把大模型（教师）每次成功的文本调用按节点追加到 JSONL，
 * 格式与线上提示词完全一致，因此用它 LoRA 微调出的 Qwen 小模型可以直接替换对应节点（见 ai.local.nodes）。
 * 数据含真实聊天内容，默认开启仅用于演示，生产环境需脱敏或关闭（ai.training.record=false）。
 */
@Component
public class TrainingRecorder {

    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Path directory = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "training", "logs");

    public TrainingRecorder(ObjectMapper mapper, @Value("${ai.training.record:true}") boolean enabled) {
        this.mapper = mapper;
        this.enabled = enabled;
    }

    public synchronized void record(String node, String provider, String model, String systemPrompt, String userContent, String assistantContent) {
        // 本地小模型（学生）的输出不回流为训练数据。
        if (!enabled || "local".equals(provider) || node == null || "default".equals(node)) return;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("node", node);
        line.put("teacher", provider + ":" + model);
        line.put("recordedAt", OffsetDateTime.now().toString());
        line.put("system", systemPrompt);
        line.put("user", userContent);
        line.put("assistant", assistantContent);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(node + ".jsonl"),
                    mapper.writeValueAsString(line) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 记录失败不影响线上调用
        }
    }

    public Path directory() {
        return directory;
    }
}
