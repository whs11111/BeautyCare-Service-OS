package com.beautycare.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 技能经验库：记录客服对 AI 草稿的处理结果（直接采纳 / 修改后发送 / 弃用）。
 * - 采纳或修改后的最终回复成为该技能的示例，按与当前用户消息的相似度检索 1~2 条作为 few-shot；
 * - 统计每个技能的采纳率与修改率，作为技能质量指标；
 * - 修改过的样本是“技能蒸馏”和 LoRA 回复微调的训练数据。
 */
@Component
public class SkillMemory {

    private static final int MAX_EXEMPLARS_PER_SKILL = 200;

    private final ObjectMapper mapper;
    private final Path file = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "skill-memory.json");
    private final Map<String, List<Exemplar>> exemplars = new LinkedHashMap<>();

    public SkillMemory(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        if (Files.exists(file)) {
            exemplars.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, List<Exemplar>>>() {}));
        }
    }

    public synchronized Exemplar record(String skillId, Long conversationId, String userMessage, String draft,
                                        String finalReply, String outcome) {
        String normalizedOutcome = outcome == null ? "ACCEPTED" : outcome.toUpperCase();
        double editRatio = draft == null || finalReply == null ? 0 : 1 - similarity(draft, finalReply);
        if ("ACCEPTED".equals(normalizedOutcome) && editRatio > 0.05) normalizedOutcome = "EDITED";
        Exemplar exemplar = new Exemplar(UUID.randomUUID().toString().substring(0, 8), skillId, conversationId,
                truncate(userMessage, 200), truncate(draft, 400), truncate(finalReply, 400), normalizedOutcome,
                Math.round(editRatio * 100) / 100.0, OffsetDateTime.now());
        List<Exemplar> list = exemplars.computeIfAbsent(skillId, key -> new ArrayList<>());
        list.add(exemplar);
        if (list.size() > MAX_EXEMPLARS_PER_SKILL) list.remove(0);
        save();
        return exemplar;
    }

    /** 检索与当前用户消息最相近、且被客服认可的示例。 */
    public synchronized List<Exemplar> retrieve(String skillId, String userMessage, int limit) {
        return exemplars.getOrDefault(skillId, List.of()).stream()
                .filter(item -> !"REJECTED".equals(item.outcome()) && item.finalReply() != null && !item.finalReply().isBlank())
                .sorted(Comparator.comparingDouble((Exemplar item) -> similarity(item.userMessage(), userMessage)).reversed())
                .limit(limit)
                .toList();
    }

    public synchronized List<Exemplar> list(String skillId) {
        return List.copyOf(exemplars.getOrDefault(skillId, List.of()));
    }

    public synchronized List<Exemplar> all() {
        return exemplars.values().stream().flatMap(List::stream).toList();
    }

    public synchronized SkillStats stats(String skillId) {
        List<Exemplar> list = exemplars.getOrDefault(skillId, List.of());
        long accepted = list.stream().filter(item -> "ACCEPTED".equals(item.outcome())).count();
        long edited = list.stream().filter(item -> "EDITED".equals(item.outcome())).count();
        long rejected = list.stream().filter(item -> "REJECTED".equals(item.outcome())).count();
        int total = list.size();
        return new SkillStats(skillId, total, accepted, edited, rejected,
                total == 0 ? 0 : Math.round(accepted * 1000.0 / total) / 1000.0,
                list.stream().mapToDouble(Exemplar::editRatio).average().orElse(0));
    }

    /** 字符二元组 Jaccard 相似度，中文短文本足够用，且零依赖、零 Token。 */
    public static double similarity(String a, String b) {
        Set<String> left = bigrams(a);
        Set<String> right = bigrams(b);
        if (left.isEmpty() && right.isEmpty()) return 1;
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        left.retainAll(right);
        return union.isEmpty() ? 0 : (double) left.size() / union.size();
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null) return grams;
        String value = text.replaceAll("[\\s，。！？,.!?、]", "");
        for (int i = 0; i + 1 < value.length(); i++) grams.add(value.substring(i, i + 2));
        return grams;
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("skill-memory.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), exemplars);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 演示环境写盘失败不影响主流程
        }
    }

    public record Exemplar(
            String id,
            String skillId,
            Long conversationId,
            String userMessage,
            String draft,
            String finalReply,
            String outcome,
            double editRatio,
            OffsetDateTime createdAt) {
    }

    public record SkillStats(
            String skillId,
            int feedbackCount,
            long accepted,
            long edited,
            long rejected,
            double acceptRate,
            double averageEditRatio) {
    }
}
