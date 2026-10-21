package com.beautycare.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.beautycare.ai.ModelRouter;
import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.beautycare.api.AgentController.RiskWorkflowResponse;
import com.beautycare.workflow.AssistanceWorkflowAiService;
import com.beautycare.ai.TrainingRecorder;
import com.beautycare.skills.SkillMemory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LoRA 蒸馏数据：把教师模型（DeepSeek / 千问）的线上调用导出为 chat 格式 SFT 数据。
 * 回复草稿节点若有客服修改记录，用客服最终回复替换教师输出（人工纠正后的标签更好）。
 * eval/cases.json 是留出测试集，不进入训练数据。
 */
@RestController
@RequestMapping("/api/training")
public class TrainingController {

    private final TrainingRecorder recorder;
    private final SkillMemory skillMemory;
    private final ModelRouter router;
    private final ObjectMapper mapper;
    private final AssistanceWorkflowAiService workflowService;

    public TrainingController(TrainingRecorder recorder, SkillMemory skillMemory, ModelRouter router, ObjectMapper mapper,
                              AssistanceWorkflowAiService workflowService) {
        this.workflowService = workflowService;
        this.recorder = recorder;
        this.skillMemory = skillMemory;
        this.router = router;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public Map<String, Object> status() throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("router", router.describe());
        result.put("logDirectory", recorder.directory().toString());
        result.put("samplesByNode", countByNode());
        result.put("skillFeedback", skillMemory.all().size());
        return result;
    }

    /** 教师标注：跳过规则级联，强制由教师模型分析情绪风险，结果按线上格式记录为蒸馏样本。 */
    @PostMapping("/teacher/risk")
    public RiskWorkflowResponse teacherRisk(@org.springframework.web.bind.annotation.RequestBody AssistanceContextRequest request) {
        return workflowService.analyzeRiskWithModel(request);
    }

    @PostMapping("/export")
    public Map<String, Object> export() throws IOException {
        Path exportDir = recorder.directory().resolveSibling("export");
        Files.createDirectories(exportDir);

        Map<String, String> correctedDrafts = new HashMap<>();
        skillMemory.all().stream()
                .filter(item -> "EDITED".equals(item.outcome()) && item.draft() != null && item.finalReply() != null)
                .forEach(item -> correctedDrafts.put(stripEllipsis(item.draft()), item.finalReply()));

        Map<String, Integer> counts = new LinkedHashMap<>();
        int corrected = 0;
        if (Files.isDirectory(recorder.directory())) {
            try (Stream<Path> files = Files.list(recorder.directory())) {
                for (Path file : files.filter(path -> path.toString().endsWith(".jsonl")).toList()) {
                    String node = file.getFileName().toString().replace(".jsonl", "");
                    List<String> lines = new ArrayList<>();
                    for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (raw.isBlank()) continue;
                        JsonNode record = mapper.readTree(raw);
                        String assistant = record.path("assistant").asText();
                        if ("reply-draft".equals(node)) {
                            JsonNode output = mapper.readTree(assistant);
                            String human = correctedDrafts.get(output.path("draft").asText());
                            if (human != null && output instanceof ObjectNode objectNode) {
                                objectNode.put("draft", human);
                                assistant = mapper.writeValueAsString(objectNode);
                                corrected++;
                            }
                        }
                        Map<String, Object> sample = Map.of("messages", List.of(
                                Map.of("role", "system", "content", record.path("system").asText()),
                                Map.of("role", "user", "content", record.path("user").asText()),
                                Map.of("role", "assistant", "content", assistant)));
                        lines.add(mapper.writeValueAsString(sample));
                    }
                    Files.write(exportDir.resolve(node + ".sft.jsonl"), lines, StandardCharsets.UTF_8);
                    counts.put(node, lines.size());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exportDirectory", exportDir.toString());
        result.put("samplesByNode", counts);
        result.put("humanCorrectedReplies", corrected);
        result.put("next", "python training/lora_train.py --data " + exportDir + "/risk.sft.jsonl --output out/risk-lora");
        return result;
    }

    private Map<String, Long> countByNode() throws IOException {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (!Files.isDirectory(recorder.directory())) return counts;
        try (Stream<Path> files = Files.list(recorder.directory())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".jsonl")).toList()) {
                try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    counts.put(file.getFileName().toString().replace(".jsonl", ""), lines.filter(line -> !line.isBlank()).count());
                }
            }
        }
        return counts;
    }

    private String stripEllipsis(String value) {
        return value.endsWith("…") ? value.substring(0, value.length() - 1) : value;
    }
}
