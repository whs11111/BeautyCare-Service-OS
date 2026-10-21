package com.beautycare.skills;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 技能库（Skill）：每个技能是一份按意图组织的服务 SOP（classpath:skills/*.md）。
 * 工作流按规则识别出的意图、图片核验结果和风险状态挑选最多 2 个技能注入回复草稿，
 * 只加载相关 SOP，而不是把所有规范塞进一个巨大的 system prompt —— 既提升准确性也节约 Token。
 *
 * 技能可学习：客服反馈经蒸馏形成“经验要点”（learnedTips），审核通过后写入 skill-overrides.json，后续调用即生效。
 */
@Component
public class SkillRegistry {

    private static final int MAX_SKILLS = 2;

    private final ObjectMapper mapper;
    private final Path overridesFile = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "skill-overrides.json");
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, List<String>> learnedTips = new LinkedHashMap<>();

    public SkillRegistry(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:skills/*.md");
        for (Resource resource : resources) {
            try (InputStream input = resource.getInputStream()) {
                Skill skill = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                if (skill != null) skills.put(skill.id(), skill);
            }
        }
        if (Files.exists(overridesFile)) {
            learnedTips.putAll(mapper.readValue(overridesFile.toFile(), new TypeReference<Map<String, List<String>>>() {}));
        }
    }

    public Collection<Skill> all() {
        return skills.values();
    }

    /** Lightweight skill routing for the standalone reply endpoint; no extra model call. */
    public Map<String, Object> replyInsights(com.beautycare.api.AgentController.AssistanceContextRequest request) {
        String latest = java.util.Objects.toString(request.latestMessage(), "");
        if (request.messages() != null) {
            for (Map<String, Object> message : request.messages()) {
                if ("USER".equalsIgnoreCase(java.util.Objects.toString(message.get("role"), ""))
                        && !Boolean.TRUE.equals(message.get("automatic"))) {
                    latest = com.beautycare.workflow.ContextCompressor.text(message);
                }
            }
        }
        Set<String> intents = new java.util.LinkedHashSet<>(com.beautycare.workflow.RuleSignals.intents(latest));
        List<Map<String, Object>> selected = select(intents, null,
                intents.contains(com.beautycare.workflow.RuleSignals.INTENT_SKIN_ADVICE),
                intents.contains(com.beautycare.workflow.RuleSignals.INTENT_SAFETY)
                        || intents.contains(com.beautycare.workflow.RuleSignals.INTENT_COMPLAINT))
                .stream().map(this::promptView).toList();
        return selected.isEmpty() ? Map.of() : Map.of("skills", selected);
    }

    public Optional<Skill> find(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public synchronized List<String> learnedTips(String skillId) {
        return List.copyOf(learnedTips.getOrDefault(skillId, List.of()));
    }

    public synchronized void setLearnedTips(String skillId, List<String> tips) throws IOException {
        learnedTips.put(skillId, List.copyOf(tips));
        Files.createDirectories(overridesFile.getParent());
        Path temp = overridesFile.resolveSibling("skill-overrides.json.tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), learnedTips);
        Files.move(temp, overridesFile, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 零 Token 技能选择：安全 > 售后 > 投诉 > 推荐 > 其他。 */
    public List<Skill> select(Set<String> intents, String visionIssueType, boolean recommendation, boolean handoff) {
        List<Skill> selected = new ArrayList<>();
        List<Skill> ordered = skills.values().stream()
                .sorted((a, b) -> Integer.compare(priority(a), priority(b)))
                .toList();
        for (Skill skill : ordered) {
            boolean match = skill.intents().stream().anyMatch(intents::contains)
                    || (visionIssueType != null && skill.vision().contains(visionIssueType))
                    || (recommendation && skill.recommendation())
                    || (handoff && skill.handoffRisk());
            if (match && !selected.contains(skill)) selected.add(skill);
            if (selected.size() >= MAX_SKILLS) break;
        }
        return selected;
    }

    /** 注入提示词的精简形式：SOP 正文 + 已审核的学习要点。 */
    public Map<String, Object> promptView(Skill skill) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", skill.id());
        view.put("name", skill.name());
        view.put("guidance", skill.body());
        List<String> tips = learnedTips(skill.id());
        if (!tips.isEmpty()) view.put("learnedTips", tips);
        return view;
    }

    private int priority(Skill skill) {
        return switch (skill.id()) {
            case "adverse-reaction" -> 0;
            case "wrong-item-resend", "damaged-leakage" -> 1;
            case "complaint-escalation" -> 2;
            case "skin-advice" -> 3;
            default -> 4;
        };
    }

    private Skill parse(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) return null;
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) return null;
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : normalized.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String id = meta.get("id");
        if (id == null || id.isBlank()) return null;
        return new Skill(
                id,
                meta.getOrDefault("name", id),
                list(meta.get("intents")),
                list(meta.get("vision")),
                "true".equalsIgnoreCase(meta.get("recommendation")),
                "handoff".equalsIgnoreCase(meta.get("risk")),
                normalized.substring(end + 5).trim(),
                OffsetDateTime.now());
    }

    private List<String> list(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.replace("[", "").replace("]", "").split("[,，]"))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    public record Skill(
            String id,
            String name,
            List<String> intents,
            List<String> vision,
            boolean recommendation,
            boolean handoffRisk,
            String body,
            OffsetDateTime loadedAt) {
    }
}
