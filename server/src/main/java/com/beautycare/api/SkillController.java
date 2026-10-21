package com.beautycare.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.beautycare.ai.ModelClient;
import com.beautycare.ai.ModelRouter;
import com.beautycare.skills.SkillMemory;
import com.beautycare.skills.SkillMemory.Exemplar;
import com.beautycare.skills.SkillRegistry;
import com.beautycare.skills.SkillRegistry.Skill;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 可学习技能：
 * 1. 客服发送消息时回传 AI 草稿与最终回复 → 记录为技能经验（采纳 / 修改 / 弃用）；
 * 2. 相似场景检索认可过的回复作为示例；
 * 3. 定期“蒸馏”：让模型对比草稿与客服修改，总结成经验要点 → 人工审核后写入技能，后续调用生效。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry registry;
    private final SkillMemory memory;
    private final ModelClient modelClient;

    public SkillController(SkillRegistry registry, SkillMemory memory, ModelClient modelClient) {
        this.registry = registry;
        this.memory = memory;
        this.modelClient = modelClient;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Skill skill : registry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", skill.id());
            item.put("name", skill.name());
            item.put("intents", skill.intents());
            item.put("vision", skill.vision());
            item.put("guidance", skill.body());
            item.put("learnedTips", registry.learnedTips(skill.id()));
            item.put("stats", memory.stats(skill.id()));
            result.add(item);
        }
        return result;
    }

    @PostMapping("/feedback")
    public List<Exemplar> feedback(@RequestBody FeedbackRequest request) {
        if (request.skillIds() == null || request.skillIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 skillIds");
        }
        List<Exemplar> saved = new ArrayList<>();
        for (String skillId : request.skillIds()) {
            if (registry.find(skillId).isEmpty()) continue;
            saved.add(memory.record(skillId, request.conversationId(), request.userMessage(),
                    request.draft(), request.finalReply(), request.outcome()));
        }
        return saved;
    }

    @GetMapping("/{skillId}/exemplars")
    public List<Exemplar> exemplars(@PathVariable String skillId) {
        return memory.list(skillId);
    }

    /** 生成经验要点提案，不直接生效。 */
    @PostMapping("/{skillId}/distill")
    public DistillProposal distill(@PathVariable String skillId) {
        Skill skill = registry.find(skillId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在"));
        List<Map<String, String>> samples = memory.list(skillId).stream()
                .filter(item -> !"ACCEPTED".equals(item.outcome()))
                .skip(Math.max(0, memory.list(skillId).size() - 30))
                .map(item -> Map.of(
                        "userMessage", nullToEmpty(item.userMessage()),
                        "aiDraft", nullToEmpty(item.draft()),
                        "agentFinal", nullToEmpty(item.finalReply()),
                        "outcome", item.outcome()))
                .toList();
        if (samples.isEmpty()) {
            return new DistillProposal(skillId, registry.learnedTips(skillId), List.of(), 0, "暂无被修改或弃用的样本，无需更新");
        }
        JsonNode result = modelClient.generateJson("skill-distill", """
                你是客服质检专家。对比AI草稿(aiDraft)与客服实际发送的回复(agentFinal)，
                总结客服反复做出的修改规律，形成不超过5条可执行的经验要点，用于改进该技能的后续回复。
                要点必须具体（例如“错发场景先说明无需寄回再给补发时间”），不得包含具体订单号、人名或金额，
                不得与技能SOP的禁止事项冲突；与已有要点重复的不要再写。
                只返回JSON：{"tips":["string"],"rationale":"一句话说明依据"}
                """, Map.of("skill", registry.promptView(skill), "existingTips", registry.learnedTips(skillId), "samples", samples),
                ModelRouter.STANDARD, null);
        List<String> tips = new ArrayList<>();
        result.path("tips").forEach(item -> {
            if (!item.asText("").isBlank() && tips.size() < 5) tips.add(item.asText());
        });
        return new DistillProposal(skillId, registry.learnedTips(skillId), tips, samples.size(),
                result.path("rationale").asText(""));
    }

    /** 人工审核后写入技能（可整体替换，也可传空数组清空）。 */
    @PutMapping("/{skillId}/tips")
    public Map<String, Object> applyTips(@PathVariable String skillId, @RequestBody Map<String, List<String>> body) throws IOException {
        if (registry.find(skillId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "技能不存在");
        List<String> tips = body.getOrDefault("tips", List.of()).stream().filter(tip -> !tip.isBlank()).limit(10).toList();
        registry.setLearnedTips(skillId, tips);
        return Map.of("skillId", skillId, "learnedTips", tips);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record FeedbackRequest(
            Long conversationId,
            List<String> skillIds,
            String userMessage,
            String draft,
            String finalReply,
            String outcome) {
    }

    public record DistillProposal(
            String skillId,
            List<String> currentTips,
            List<String> proposedTips,
            int samplesUsed,
            String rationale) {
    }
}
