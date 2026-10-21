package com.beautycare.personalization;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.fasterxml.jackson.core.type.TypeReference;

import com.beautycare.ai.ModelClient;
import com.beautycare.ai.ModelRouter;
import com.beautycare.personalization.CustomerProfileStore.Fact;
import com.beautycare.personalization.PersonalizationService.Recommendation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 测肤问卷（两个 Agent 共用）：
 * 1. AI 测肤小程序：用户通过链接完成整套问卷 → 肤质判定 + AI 测肤报告 + 个性化推荐；
 * 2. 对话内快捷提问：推荐时信息不足，按“最缺的维度”挑一题，在聊天里以选项按钮发送，用户点选即写入画像。
 * 问卷答案是用户的明确选择，写入偏好档案时记为 CONFIRMED（来源 QUIZ），优先级高于聊天推测。
 */
@Service
public class SkinQuizService {

    private final JsonNode quiz;
    private final Map<String, JsonNode> questions = new LinkedHashMap<>();
    private final CustomerProfileStore store;
    private final PersonalizationService personalization;
    private final ModelClient modelClient;
    private final ObjectMapper mapper;
    private final Path answersFile = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "quiz-answers.json");

    /** 每位用户已回答的题目与选项（对话内逐题回答时累积）。 */
    private final Map<String, Map<String, List<String>>> answers = new ConcurrentHashMap<>();

    public SkinQuizService(ObjectMapper mapper, CustomerProfileStore store,
                           PersonalizationService personalization, ModelClient modelClient) throws IOException {
        try (InputStream input = new ClassPathResource("quiz/skin-quiz.json").getInputStream()) {
            this.quiz = mapper.readTree(input);
        }
        quiz.path("questions").forEach(question -> questions.put(question.path("id").asText(), question));
        this.store = store;
        this.personalization = personalization;
        this.modelClient = modelClient;
        this.mapper = mapper;
        if (Files.exists(answersFile)) answers.putAll(mapper.readValue(answersFile.toFile(),
                new TypeReference<Map<String, Map<String, List<String>>>>() {}));
    }

    public JsonNode quiz() {
        return quiz;
    }

    /**
     * 对话内快捷提问：按缺失维度挑下一题。顺序：肤质干油 → 敏感 → 想改善的问题 → 不耐受成分。
     * 已回答过、或画像里已有对应维度的不再问；返回 null 表示信息已足够。
     */
    public synchronized JsonNode nextQuestion(String customerId) {
        validateCustomer(customerId);
        Map<String, List<String>> answered = answers.getOrDefault(customerId, Map.of());
        List<Fact> facts = store.get(customerId).facts().stream()
                .filter(fact -> !"REJECTED".equals(fact.status())).toList();
        boolean hasSkinType = facts.stream().anyMatch(fact -> PersonalizationService.SKIN_TYPE.equals(fact.dimension())
                && !"敏感肌".equals(fact.value()));
        boolean hasSensitivity = facts.stream().anyMatch(fact -> "敏感肌".equals(fact.value()))
                || answered.containsKey("reactivity");
        boolean hasConcern = facts.stream().anyMatch(fact -> PersonalizationService.CONCERN.equals(fact.dimension()));
        boolean hasAvoid = facts.stream().anyMatch(fact -> PersonalizationService.AVOID.equals(fact.dimension()))
                || answered.containsKey("intolerance");

        List<String> order = new ArrayList<>();
        if (!hasSkinType) order.addAll(List.of("cheek_tight", "noon_oil"));
        if (!hasSensitivity) order.add("reactivity");
        if (!hasConcern) order.add("goals");
        if (!hasAvoid) order.add("intolerance");
        if (facts.stream().noneMatch(fact -> PersonalizationService.BUDGET.equals(fact.dimension()))) order.add("budget");
        for (String id : order) {
            if (!answered.containsKey(id)) return questions.get(id);
        }
        return null;
    }

    /** 记录一道题的回答（对话内点选），立即把该题能确定的信息写入画像。 */
    public synchronized QuizResult answer(String customerId, String questionId, List<String> optionIds) {
        validateCustomer(customerId);
        validateAnswer(questionId, optionIds);
        answers.computeIfAbsent(customerId, key -> new ConcurrentHashMap<>()).put(questionId, List.copyOf(optionIds));
        saveAnswers();
        return apply(customerId, answers.get(customerId), false, null);
    }

    /** 小程序整套提交：判定肤质、写画像、给推荐，并生成测肤报告。 */
    public QuizResult submit(String customerId, Map<String, List<String>> all, String note) {
        validateCustomer(customerId);
        if (all == null || all.size() != questions.size() || !all.keySet().equals(questions.keySet()))
            throw new IllegalArgumentException("请完成全部问卷题目后再提交");
        all.forEach(this::validateAnswer);
        if (note != null && note.length() > 1000) throw new IllegalArgumentException("补充说明不能超过1000字");
        Map<String, List<String>> merged = new LinkedHashMap<>();
        all.forEach((id, options) -> merged.put(id, List.copyOf(options)));
        synchronized (this) { answers.put(customerId, merged); saveAnswers(); }
        return apply(customerId, merged, true, note);
    }

    private void validateCustomer(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("客户编号无效，请从咨询页面打开问卷");
    }

    private void validateAnswer(String id, List<String> selected) {
        JsonNode question = questions.get(id);
        if (question == null) throw new IllegalArgumentException("题目不存在");
        if (selected == null || selected.isEmpty() || new LinkedHashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("请选择有效选项");
        if ((!question.path("multi").asBoolean() && selected.size() != 1)
                || selected.size() > question.path("maxSelect").asInt(question.path("options").size()))
            throw new IllegalArgumentException("选项数量超过限制");
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode option : question.path("options")) {
            allowed.add(option.path("id").asText());
            if (selected.size() > 1 && selected.contains(option.path("id").asText()) && option.path("exclusive").asBoolean())
                throw new IllegalArgumentException("“都没有/不清楚”不能与其他选项同时选择");
        }
        if (!allowed.containsAll(selected)) throw new IllegalArgumentException("选项不存在");
    }

    private void saveAnswers() {
        try {
            Files.createDirectories(answersFile.getParent());
            Path temporary = answersFile.resolveSibling("quiz-answers.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), answers);
            Files.move(temporary, answersFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) { throw new IllegalStateException("问卷暂时无法保存，请稍后重试", exception); }
    }

    private QuizResult apply(String customerId, Map<String, List<String>> answered, boolean full, String note) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        Set<String> concerns = new LinkedHashSet<>();
        Set<String> avoid = new LinkedHashSet<>();
        Integer budget = null;
        for (Map.Entry<String, List<String>> entry : answered.entrySet()) {
            JsonNode question = questions.get(entry.getKey());
            if (question == null) continue;
            for (JsonNode option : question.path("options")) {
                if (!entry.getValue().contains(option.path("id").asText())) continue;
                option.path("scores").fields().forEachRemaining(score -> scores.merge(score.getKey(), score.getValue().asInt(), Integer::sum));
                option.path("concerns").forEach(item -> concerns.add(item.asText()));
                option.path("avoid").forEach(item -> avoid.add(item.asText()));
                if (option.has("budget")) budget = option.path("budget").asInt();
            }
        }

        String skinType = skinType(scores, answered);
        boolean sensitive = scores.getOrDefault("S", 0) >= quiz.path("scoring").path("sensitiveThreshold").asInt(3);
        // Remove only superseded QUIZ-owned facts; never override an agent's correction.
        synchronized (store) {
        Set<String> desired = new LinkedHashSet<>();
        if (skinType != null) desired.add("skinType:" + skinType);
        if (sensitive) desired.add("skinType:敏感肌");
        concerns.forEach(value -> desired.add("concern:" + value));
        avoid.forEach(value -> desired.add("avoid:" + value));
        if (budget != null) desired.add("budget:" + budget);
        store.get(customerId).facts().removeIf(fact -> "QUIZ".equals(fact.source()) && !"REJECTED".equals(fact.status())
                && !desired.contains(fact.dimension() + ":" + fact.value()));
        String snippet = "肤质问卷：" + answered.entrySet().stream().map(entry -> {
            JsonNode question = questions.get(entry.getKey());
            List<String> labels = new ArrayList<>();
            question.path("options").forEach(option -> { if (entry.getValue().contains(option.path("id").asText())) labels.add(option.path("text").asText()); });
            return question.path("text").asText() + String.join("、", labels);
        }).reduce((left, right) -> left + "；" + right).orElse("");
        snippet = snippet.substring(0, Math.min(350, snippet.length()));
        final String evidence = snippet;
        if (skinType != null) writeFact(customerId, PersonalizationService.SKIN_TYPE, skinType, snippet);
        if (sensitive) writeFact(customerId, PersonalizationService.SKIN_TYPE, "敏感肌", snippet);
        concerns.forEach(item -> writeFact(customerId, PersonalizationService.CONCERN, item, evidence));
        avoid.forEach(item -> writeFact(customerId, PersonalizationService.AVOID, item, evidence));
        if (budget != null) writeFact(customerId, PersonalizationService.BUDGET, String.valueOf(budget), snippet);
        store.save();
        }
        if (full && note != null && !note.isBlank()) {
            // 自由描述交给已有的偏好抽取（规则优先，必要时轻量模型）。
            personalization.updateProfile(customerId,
                    List.of(Map.of("id", "quiz-note-" + Integer.toHexString(note.hashCode()), "role", "user", "text", note)),
                    null, null);
        }

        // 测肤结果以护肤为主：用户在“最想改善”里没选彩妆问题时，只推荐护肤品类。
        Recommendation recommendation = full ? personalization.recommend(customerId, "", List.of("精华", "面霜", "水乳", "洁面", "防晒")) : null;
        ReportOutcome report = full ? report(skinType, sensitive, concerns, avoid, recommendation) : new ReportOutcome(null, "NONE");
        return new QuizResult(customerId, skinType, sensitive, List.copyOf(concerns), List.copyOf(avoid), budget,
                answered.size(), full ? null : nextQuestion(customerId), recommendation, report.text(), report.mode());
    }

    private String skinType(Map<String, Integer> s, Map<String, List<String>> answered) {
        boolean hasOilInfo = answered.containsKey("noon_oil") || answered.containsKey("after_wash") || answered.containsKey("pores");
        boolean hasDryInfo = answered.containsKey("cheek_tight") || answered.containsKey("after_wash");
        // 干油要两方面信息都有才下结论；只答了干燥题时先记为“干燥起皮”困扰，继续问出油情况。
        if (!hasOilInfo || !hasDryInfo) return null;
        int d = s.getOrDefault("D", 0);
        int o = s.getOrDefault("O", 0);
        int t = s.getOrDefault("T", 0);
        // 按区域判断：O 是全脸（含两颊）出油，T 只是 T 区出油，D 是两颊干燥。
        if (o >= 2 && d <= 1) return "油皮";
        if (t >= 2 && d >= 2) return "混干";
        if (t >= 2) return "混油";
        if (d >= 2) return "干皮";
        return "中性";
    }

    private void writeFact(String customerId, String dimension, String value, String snippet) {
        boolean corrected = store.get(customerId).facts().stream().anyMatch(fact -> "AGENT".equals(fact.source())
                && fact.dimension().equals(dimension) && (fact.value().equals(value)
                    || ("CONFIRMED".equals(fact.status()) && ("budget".equals(dimension)
                        || ("skinType".equals(dimension) && !"敏感肌".equals(value) && !"敏感肌".equals(fact.value()))))));
        if (!corrected) store.upsert(customerId, dimension, value, "CONFIRMED", "QUIZ", "quiz", snippet);
    }

    /** AI 测肤报告：有模型时用轻量档位生成，没有 Key 时退回模板，保证小程序始终可用。 */
    private record ReportOutcome(String text, String mode) {}

    private ReportOutcome report(String skinType, boolean sensitive, Set<String> concerns, Set<String> avoid,
                          Recommendation recommendation) {
        String template = "根据您的问卷回答，肤质倾向" + Objects.toString(skinType, "待确认")
                + (sensitive ? "，并有敏感倾向，建议优先考虑温和护理" : "")
                + (concerns.isEmpty() ? "" : "。目前最需要关注：" + String.join("、", concerns))
                + (avoid.isEmpty() ? "" : "。选购时请留意并规避：" + String.join("、", avoid))
                + (recommendation == null || recommendation.items().isEmpty() ? "。"
                    : "。推荐先从" + recommendation.items().get(0).name() + "开始。");
        if (!modelClient.configured()) return new ReportOutcome(template, "TEMPLATE");
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("skinType", Objects.toString(skinType, "待确认"));
            input.put("sensitive", sensitive);
            input.put("concerns", concerns);
            input.put("avoid", avoid);
            input.put("products", recommendation == null ? List.of()
                    : recommendation.items().stream().map(item -> Map.of("name", item.name(), "reasons", item.reasons())).toList());
            JsonNode result = modelClient.generateJson("quiz-report", """
                    你是品牌护肤顾问，根据用户自述问卷写一段简短护肤需求报告（120字以内，亲切自然，称呼“您”，不用分号）。
                    先用一句话说肤质特点，再给1-2条日常护理建议，最后一句说明为什么推荐products中的第一款。
                    不做医学诊断，不声称检测、确诊或已验证成分，不承诺功效，不提及products以外的商品。
                    商品成分未经核实时不得宣称不含酒精、香精或符合全部成分规避要求，提醒以包装成分表为准。
                    只返回JSON：{"report":"string"}
                    """, input, ModelRouter.LIGHT, null);
            String text = result.path("report").asText("");
            return text.isBlank() ? new ReportOutcome(template, "TEMPLATE") : new ReportOutcome(text.replace('；', '。'), "AI");
        } catch (IllegalStateException exception) {
            return new ReportOutcome(template, "TEMPLATE");
        }
    }

    public record QuizResult(
            String customerId,
            String skinType,
            boolean sensitive,
            List<String> concerns,
            List<String> avoid,
            Integer budget,
            int answeredCount,
            JsonNode nextQuestion,
            Recommendation recommendation,
            String report,
            String reportMode) {
    }
}
