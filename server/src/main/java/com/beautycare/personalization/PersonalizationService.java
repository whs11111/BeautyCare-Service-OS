package com.beautycare.personalization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.beautycare.ai.ModelClient;
import com.beautycare.ai.ModelClient.ModelUsage;
import com.beautycare.ai.ModelRouter;
import com.beautycare.ai.TokenMeter;
import com.beautycare.personalization.CustomerProfileStore.Fact;
import com.beautycare.personalization.CustomerProfileStore.Profile;
import com.beautycare.personalization.ProductCatalog.Product;
import com.beautycare.workflow.ContextCompressor;
import com.beautycare.workflow.RuleSignals;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * 个性化推荐节点，解决“强调了三遍肤质困扰，平台却只推荐爆款”：
 * 1. 增量抽取：只处理新的用户消息；规则词典先抽取，规则抽不到但出现肤质线索时才调用轻量模型；
 * 2. 偏好档案：带证据、提及次数、确认状态，客服可确认或纠正；
 * 3. 规则推荐：硬过滤禁忌成分，再按困扰匹配度排序，爆款只作并列时的次要因素，并说明被排除的热门款原因。
 */
@Service
public class PersonalizationService {

    public static final String SKIN_TYPE = "skinType";
    public static final String CONCERN = "concern";
    public static final String AVOID = "avoid";
    public static final String FINISH = "finish";
    public static final String BUDGET = "budget";
    public static final String INGREDIENT = "ingredient";
    private static final Map<String, List<String>> INGREDIENT_WORDS = Map.of(
            "神经酰胺", List.of("神经酰胺"),
            "透明质酸", List.of("透明质酸", "玻尿酸"),
            "积雪草", List.of("积雪草"),
            "角鲨烷", List.of("角鲨烷"),
            "水杨酸", List.of("水杨酸"),
            "VC", List.of("VC", "维C", "维生素C"),
            "A醇", List.of("A醇", "视黄醇"),
            "氧化锌", List.of("氧化锌"));

    private static final Map<String, List<String>> SKIN_TYPE_WORDS = new LinkedHashMap<>();
    private static final Map<String, List<String>> CONCERN_WORDS = new LinkedHashMap<>();
    private static final Map<String, List<String>> AVOID_WORDS = new LinkedHashMap<>();
    private static final Map<String, List<String>> FINISH_WORDS = new LinkedHashMap<>();
    static {
        SKIN_TYPE_WORDS.put("敏感肌", List.of("敏感肌", "皮肤敏感", "容易敏感", "一用就红", "很敏感", "易过敏", "过敏体质"));
        SKIN_TYPE_WORDS.put("干皮", List.of("干皮", "皮肤干", "很干", "特别干", "干性"));
        SKIN_TYPE_WORDS.put("油皮", List.of("油皮", "大油田", "出油多", "很油", "油性"));
        SKIN_TYPE_WORDS.put("混油", List.of("混油", "T区油"));
        SKIN_TYPE_WORDS.put("混干", List.of("混干"));

        CONCERN_WORDS.put("泛红", List.of("泛红", "发红", "红血丝", "脸红", "一用就红"));
        CONCERN_WORDS.put("屏障受损", List.of("屏障", "烂脸", "刺痛", "换季敏感"));
        CONCERN_WORDS.put("干燥起皮", List.of("起皮", "紧绷", "干燥", "很干"));
        CONCERN_WORDS.put("出油", List.of("出油", "油光", "大油田"));
        CONCERN_WORDS.put("痘痘", List.of("痘", "爆痘", "痘痘"));
        CONCERN_WORDS.put("闭口", List.of("闭口"));
        CONCERN_WORDS.put("毛孔", List.of("毛孔"));
        CONCERN_WORDS.put("暗沉", List.of("暗沉", "暗黄", "没气色"));
        CONCERN_WORDS.put("细纹", List.of("细纹", "皱纹", "抗老"));
        CONCERN_WORDS.put("卡粉", List.of("卡粉", "卡纹"));
        CONCERN_WORDS.put("脱妆", List.of("脱妆", "掉妆", "斑驳"));
        CONCERN_WORDS.put("浮粉", List.of("浮粉"));
        CONCERN_WORDS.put("晒黑", List.of("晒黑", "防晒"));

        AVOID_WORDS.put("含酒精", List.of("酒精"));
        AVOID_WORDS.put("含香精", List.of("香精", "香味", "香料"));
        AVOID_WORDS.put("高浓度酸", List.of("刷酸", "酸类", "水杨酸", "果酸"));
        AVOID_WORDS.put("A醇", List.of("A醇", "视黄醇"));
        AVOID_WORDS.put("高浓度VC", List.of("VC", "维C"));

        FINISH_WORDS.put("雾面", List.of("雾面", "哑光"));
        FINISH_WORDS.put("光泽", List.of("水光", "光泽", "水润"));
        FINISH_WORDS.put("自然", List.of("自然", "伪素颜"));
    }
    private static final List<String> AVOID_CUES = List.of("过敏", "不能用", "不能", "怕", "不要", "不敢", "刺激", "受不了", "不耐受");
    private static final Pattern BUDGET_PATTERN = Pattern.compile("(?:预算|价格|价位)?\\s*(\\d{2,4})\\s*(?:元|块)?\\s*(?:以内|以下|左右|之内)");
    private static final List<String> PROFILE_CUES = List.of("皮肤", "肤质", "脸", "适合", "推荐", "用什么", "哪款", "敏感", "过敏");
    /** 敏感肌默认规避的成分风险标签。 */
    private static final Set<String> SENSITIVE_AVOID = Set.of("含酒精", "含香精", "高浓度酸", "A醇", "高浓度VC");

    private final ProductCatalog catalog;
    private final CustomerProfileStore store;
    private final ModelClient modelClient;
    private final TokenMeter tokenMeter;

    public PersonalizationService(
            ProductCatalog catalog,
            CustomerProfileStore store,
            ModelClient modelClient,
            TokenMeter tokenMeter) {
        this.catalog = catalog;
        this.store = store;
        this.modelClient = modelClient;
        this.tokenMeter = tokenMeter;
    }

    /** 增量更新档案：已处理过的消息不会再次抽取。 */
    public Profile updateProfile(
            String customerId,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> workOrders,
            Consumer<ModelUsage> usageSink) {
        List<Map<String, Object>> fresh = new ArrayList<>();
        if (messages != null) {
            for (Map<String, Object> message : messages) {
                if (!"user".equalsIgnoreCase(ContextCompressor.role(message))) continue;
                String id = Objects.toString(message.get("id"), null);
                if (id == null) id = "h" + Integer.toHexString(ContextCompressor.text(message).hashCode());
                if (ContextCompressor.text(message).matches("(?s).*(男士|男生|男性|男朋友|老公|丈夫|爸爸|父亲).*"))
                    store.upsert(customerId, "audience", "男士", "STATED", "CHAT", id, ContextCompressor.text(message));
                if (store.processed(customerId, id)) {
                    // 补提旧版本遗漏的期望成分；upsert按消息id去重并保留人工纠正。
                    extractIngredients(customerId, id, ContextCompressor.text(message));
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>(message);
                copy.put("id", id);
                fresh.add(copy);
            }
        }

        List<String> processedIds = new ArrayList<>();
        List<Map<String, Object>> needModel = new ArrayList<>();
        int ruleExtracted = 0;
        for (Map<String, Object> message : fresh) {
            String id = message.get("id").toString();
            String text = ContextCompressor.text(message);
            int extracted = extractByRules(customerId, id, text);
            if (extracted == 0 && RuleSignals.containsAny(text, PROFILE_CUES)) needModel.add(message);
            if (extracted > 0) ruleExtracted++;
            processedIds.add(id);
        }

        if (!needModel.isEmpty() && modelClient.configured()) {
            extractByModel(customerId, needModel, usageSink);
        }
        // 只有规则层已抽到偏好的消息才计为“省下的模型调用”，无偏好内容的消息本来就不需要模型。
        if (ruleExtracted > 0) tokenMeter.recordSkip("profile", 150L * ruleExtracted);

        inferFromWorkOrders(customerId, workOrders);
        store.markProcessed(customerId, processedIds);
        store.save();
        return store.get(customerId);
    }

    private int extractByRules(String customerId, String messageId, String text) {
        int count = extractIngredients(customerId, messageId, text);
        String snippet = text.length() > 40 ? text.substring(0, 40) + "…" : text;
        for (Map.Entry<String, List<String>> entry : SKIN_TYPE_WORDS.entrySet()) {
            if (RuleSignals.containsAny(text, entry.getValue())) {
                store.upsert(customerId, SKIN_TYPE, entry.getKey(), "STATED", "CHAT", messageId, snippet);
                count++;
            }
        }
        for (Map.Entry<String, List<String>> entry : CONCERN_WORDS.entrySet()) {
            if (RuleSignals.containsAny(text, entry.getValue())) {
                store.upsert(customerId, CONCERN, entry.getKey(), "STATED", "CHAT", messageId, snippet);
                count++;
            }
        }
        if (RuleSignals.containsAny(text, AVOID_CUES)) {
            for (Map.Entry<String, List<String>> entry : AVOID_WORDS.entrySet()) {
                if (RuleSignals.containsAny(text, entry.getValue())) {
                    store.upsert(customerId, AVOID, entry.getKey(), "STATED", "CHAT", messageId, snippet);
                    count++;
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : FINISH_WORDS.entrySet()) {
            if (RuleSignals.containsAny(text, entry.getValue()) && (text.contains("喜欢") || text.contains("想要") || text.contains("偏好"))) {
                store.upsert(customerId, FINISH, entry.getKey(), "STATED", "CHAT", messageId, snippet);
                count++;
            }
        }
        Matcher budget = BUDGET_PATTERN.matcher(text);
        if (budget.find()) {
            store.upsert(customerId, BUDGET, budget.group(1), "STATED", "CHAT", messageId, snippet);
            count++;
        }
        return count;
    }

    private int extractIngredients(String customerId, String messageId, String text) {
        int count = 0;
        for (var entry : INGREDIENT_WORDS.entrySet()) {
            boolean wanted = false;
            for (String clause : text.split("[，,。；;！!？?]|但是|不过|但")) {
                if (RuleSignals.containsAny(clause, List.of("不要", "不想", "不喜欢", "不需要", "不能", "不含", "无", "过敏"))) continue;
                if (entry.getValue().stream().anyMatch(word -> Pattern.compile(
                        "(?:想要|希望|需要|要|喜欢|偏好).{0,16}" + Pattern.quote(word), Pattern.CASE_INSENSITIVE)
                        .matcher(clause).find())) {
                    wanted = true;
                    break;
                }
            }
            if (wanted) {
                store.upsert(customerId, INGREDIENT, entry.getKey(), "STATED", "CHAT", messageId,
                        text.length() > 80 ? text.substring(0, 80) + "…" : text);
                count++;
            }
        }
        return count;
    }

    private void extractByModel(String customerId, List<Map<String, Object>> messages, Consumer<ModelUsage> usageSink) {
        List<Map<String, String>> input = messages.stream()
                .map(message -> Map.of("id", message.get("id").toString(), "text", ContextCompressor.text(message)))
                .toList();
        try {
            JsonNode result = modelClient.generateJson("profile", """
                    你是美妆客服的用户偏好抽取节点。只从用户消息中抽取明确表达的护肤与彩妆偏好，不推测、不诊断。
                    dimension只能是skinType、concern、avoid、finish、budget之一。
                    skinType取值只能是敏感肌、干皮、油皮、混油、混干、中性；
                    concern取值只能是泛红、屏障受损、干燥起皮、出油、痘痘、闭口、毛孔、暗沉、细纹、卡粉、脱妆、浮粉、晒黑；
                    avoid取值只能是含酒精、含香精、高浓度酸、A醇、高浓度VC；finish取值只能是雾面、光泽、自然；budget填写数字。
                    sourceMessageId必须原样复制消息id。没有可抽取内容时返回空数组。
                    只返回JSON：{"facts":[{"dimension":"","value":"","sourceMessageId":""}]}
                    """, Map.of("messages", input), ModelRouter.LIGHT, usageSink);
            result.path("facts").forEach(item -> {
                String dimension = item.path("dimension").asText("");
                String value = item.path("value").asText("");
                if (List.of(SKIN_TYPE, CONCERN, AVOID, FINISH, BUDGET).contains(dimension) && !value.isBlank()) {
                    // 模型抽取的结果记为 INFERRED，需要客服确认后才升级。
                    store.upsert(customerId, dimension, value, "INFERRED", "MODEL",
                            item.path("sourceMessageId").asText(null), null);
                }
            });
        } catch (IllegalStateException ignored) {
            // 抽取失败不阻塞推荐，规则结果仍然可用。
        }
    }

    private void inferFromWorkOrders(String customerId, List<Map<String, Object>> workOrders) {
        if (workOrders == null) return;
        for (Map<String, Object> order : workOrders) {
            String text = order.values().stream().map(String::valueOf).reduce("", String::concat);
            if (RuleSignals.containsAny(text, List.of("过敏", "红肿", "刺痛", "不良反应"))) {
                store.upsert(customerId, SKIN_TYPE, "敏感肌", "INFERRED", "WORK_ORDER",
                        "wo-" + Objects.toString(order.getOrDefault("id", Integer.toHexString(text.hashCode()))), "历史售后含不良反应");
            }
        }
    }

    public Recommendation recommend(String customerId, String latestMessage) {
        return recommend(customerId, latestMessage, null);
    }

    public Recommendation recommend(String customerId, String latestMessage, List<String> categoryFilter) {
        Profile profile = store.get(customerId);
        List<Fact> active = profile.facts().stream().filter(fact -> !"REJECTED".equals(fact.status())).toList();

        List<String> skinTypes = values(active, SKIN_TYPE);
        Map<String, Integer> concerns = new LinkedHashMap<>();
        active.stream().filter(fact -> CONCERN.equals(fact.dimension()))
                .forEach(fact -> concerns.merge(fact.value(), fact.mentions(), Integer::sum));
        List<String> explicitAvoid = active.stream().filter(fact -> AVOID.equals(fact.dimension()) && !"INFERRED".equals(fact.status()))
                .map(Fact::value).distinct().toList();
        List<String> avoid = new ArrayList<>(values(active, AVOID));
        if (skinTypes.contains("敏感肌")) {
            SENSITIVE_AVOID.stream().filter(flag -> !avoid.contains(flag)).forEach(avoid::add);
        }
        List<String> finishes = values(active, FINISH);
        List<String> ingredients = values(active, INGREDIENT);
        Integer budget = values(active, BUDGET).stream().findFirst().map(value -> {
            try { return Integer.valueOf(value); } catch (NumberFormatException exception) { return null; }
        }).orElse(null);

        List<String> categories = categoryFilter == null ? categoriesFor(latestMessage, concerns.keySet()) : List.copyOf(categoryFilter);

        List<RecommendedItem> items = new ArrayList<>();
        List<ExcludedItem> excluded = new ArrayList<>();
        String evidenceText = Objects.toString(latestMessage, "") + active.stream().filter(fact -> "CHAT".equals(fact.source())).map(fact -> Objects.toString(fact.lastSnippet(), "")).collect(java.util.stream.Collectors.joining(" "));
        boolean menRequested = evidenceText.matches("(?s).*(男士|男生|男性|男朋友|老公|丈夫|爸爸|父亲).*" );
        for (Product product : catalog.all()) {
            if (!categories.contains(product.category())) continue;
            if (product.name().contains("男士") && !menRequested) continue;
            String productDetails = String.join("、", product.highlights());
            // 正向标签不是穷尽清单：没写某成分/肤质/功效，不等于不含或不适用。
            List<String> matchedIngredients = ingredients.stream().filter(ingredient ->
                    INGREDIENT_WORDS.getOrDefault(ingredient, List.of(ingredient)).stream().anyMatch(productDetails::contains)).toList();
            List<String> conflicts = product.flags().stream().filter(avoid::contains).toList();
            if (!conflicts.isEmpty()) {
                if (product.hot()) {
                    excluded.add(new ExcludedItem(product.sku(), product.name(), true,
                            "热门款，但" + String.join("、", conflicts) + "，与该用户" + reasonSubject(skinTypes, conflicts) + "冲突"));
                }
                continue;
            }

            double score = 0;
            List<String> reasons = new ArrayList<>();
            if (!matchedIngredients.isEmpty()) {
                score += 3 * matchedIngredients.size();
                reasons.add((product.verifiedIngredients() ? "含" : "商品资料提及") + String.join("、", matchedIngredients));
            }
            for (Map.Entry<String, Integer> concern : concerns.entrySet()) {
                if (product.concerns().contains(concern.getKey())) {
                    score += 3 + Math.min(3, concern.getValue() - 1);
                    reasons.add("针对" + concern.getKey() + (concern.getValue() > 1 ? "（用户提及" + concern.getValue() + "次）" : ""));
                }
            }
            List<String> matchedSkin = skinTypes.stream().filter(product.skinTypes()::contains).toList();
            if (!matchedSkin.isEmpty()) {
                score += 2;
                reasons.add((product.verifiedIngredients() ? "适合" : "商品定位：") + String.join("/", matchedSkin));
            }
            if (!finishes.isEmpty() && finishes.contains(product.finish())) {
                score += 1;
                reasons.add(product.finish() + "妆效符合偏好");
            }
            if (budget != null && product.price() > 0 && product.price() > budget) {
                score -= 2;
                reasons.add("略超预算" + budget + "元");
            }
            if (product.hot()) score += 0.3;
            Set<String> ingredientClaims = new LinkedHashSet<>();
            if (product.verifiedIngredients()) avoid.stream().map(flag -> "无" + (flag.startsWith("含") ? flag.substring(1) : flag))
                    .forEach(ingredientClaims::add);
            List<String> highlights = new ArrayList<>();
            for (String highlight : product.highlights()) {
                // “无香精无酒精”等合并亮点拆开核对，避免与规避理由重复。
                for (String part : highlight.split("[、，,；;]|(?=无)")) {
                    String text = part.trim();
                    if (text.isEmpty()) continue;
                    if (ingredients.stream().anyMatch(ingredient -> INGREDIENT_WORDS.getOrDefault(ingredient, List.of(ingredient))
                            .stream().anyMatch(text::contains))) continue;
                    if (text.startsWith("无") && product.verifiedIngredients()) ingredientClaims.add(text);
                    else if (text.startsWith("无")) continue;
                    else if (!highlights.contains(text)) highlights.add(text);
                }
            }
            if (!ingredientClaims.isEmpty()) reasons.add(String.join("，", ingredientClaims));
            if (!highlights.isEmpty()) reasons.add(String.join("、", highlights));
            List<String> unknownIngredients = ingredients.stream().filter(ingredient -> !matchedIngredients.contains(ingredient)).toList();
            if (!unknownIngredients.isEmpty()) reasons.add("所需成分" + String.join("、", unknownIngredients) + "尚未核实，不作为已匹配条件");
            if (skinTypes.contains("敏感肌") && !product.skinTypes().contains("敏感肌")) {
                reasons.add("敏感肌适用性尚未核实，本推荐仅按其他需求匹配，使用前请先局部试用");
            }
            // 默认敏感肌避用条件仅用于筛选，不冒充用户明确提出的需求展示。
            if (!product.verifiedIngredients()) reasons.add(explicitAvoid.isEmpty() ? "成分未核实，请以包装成分表为准" : "尚未核实是否含" + explicitAvoid.stream().map(flag -> flag.startsWith("含") ? flag.substring(1) : flag).collect(java.util.stream.Collectors.joining("、")) + "，请以包装成分表为准");
            if (score <= 0) reasons.add("候选参考：暂未确认符合您的具体需求，请先核实再选购");
            items.add(new RecommendedItem(product.sku(), product.name(), product.category(), product.price(),
                    product.hot(), Math.round(score * 10) / 10.0, reasons.stream().distinct().toList(), product.brand(), product.source(), product.verifiedIngredients()));
        }
        items.sort(Comparator.comparingDouble(RecommendedItem::score).reversed());
        // 没有已知匹配时保留候选参考，不把未知当作明确不符合。
        List<RecommendedItem> top = items.stream().limit(3).toList();

        List<RepeatedConcern> repeated = concerns.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .map(entry -> new RepeatedConcern(entry.getKey(), entry.getValue()))
                .toList();

        boolean enoughEvidence = !skinTypes.isEmpty() || !concerns.isEmpty() || !ingredients.isEmpty();
        return new Recommendation(
                customerId,
                summarize(skinTypes, concerns, avoid, budget),
                repeated,
                enoughEvidence ? top : List.of(),
                enoughEvidence ? excluded.stream().limit(3).toList() : List.of(),
                enoughEvidence ? List.of() : List.of("用户的肤质类型", "最在意的皮肤困扰", "是否有不耐受成分"),
                agentHint(repeated, enoughEvidence ? top : List.of(), excluded),
                active);
    }

    private String reasonSubject(List<String> skinTypes, List<String> conflicts) {
        return skinTypes.contains("敏感肌") ? "敏感肌需规避的成分" : "明确不耐受的成分";
    }

    private List<String> categoriesFor(String text, Set<String> concerns) {
        String value = Objects.toString(text, "");
        if (RuleSignals.containsAny(value, List.of("粉底", "底妆", "气垫", "遮瑕", "卡粉", "脱妆", "浮粉"))) {
            return List.of("妆前", "粉底", "气垫", "散粉");
        }
        if (RuleSignals.containsAny(value, List.of("口红", "唇釉", "唇膏", "唇"))) return List.of("唇妆");
        if (RuleSignals.containsAny(value, List.of("防晒", "隔离"))) return List.of("防晒");
        if (RuleSignals.containsAny(value, List.of("洗面奶", "洁面"))) return List.of("洁面");
        if (RuleSignals.containsAny(value, List.of("精华"))) return List.of("精华");
        if (RuleSignals.containsAny(value, List.of("面霜", "乳液", "水乳", "爽肤水"))) return List.of("面霜", "水乳");
        if (concerns.stream().anyMatch(List.of("卡粉", "脱妆", "浮粉")::contains)) {
            return List.of("妆前", "粉底", "气垫", "散粉");
        }
        return List.of("精华", "面霜", "水乳", "洁面", "防晒");
    }

    private String summarize(List<String> skinTypes, Map<String, Integer> concerns, List<String> avoid, Integer budget) {
        List<String> parts = new ArrayList<>();
        if (!skinTypes.isEmpty()) parts.add(String.join("/", skinTypes));
        if (!concerns.isEmpty()) parts.add("在意" + String.join("、", concerns.keySet()));
        if (!avoid.isEmpty()) parts.add("规避" + String.join("、", avoid));
        if (budget != null) parts.add("预算" + budget + "元内");
        return parts.isEmpty() ? "暂无足够偏好信息" : String.join("；", parts);
    }

    private String agentHint(List<RepeatedConcern> repeated, List<RecommendedItem> items, List<ExcludedItem> excluded) {
        if (items.isEmpty()) return "偏好信息不足，建议先确认用户肤质、主要困扰和不耐受成分，再给推荐。";
        StringBuilder hint = new StringBuilder();
        if (!repeated.isEmpty()) {
            RepeatedConcern top = repeated.get(0);
            hint.append("用户已").append(top.mentions()).append("次提到“").append(top.concern()).append("”，先回应这个困扰；");
        }
        hint.append("优先推荐").append(items.get(0).name());
        if (!excluded.isEmpty()) hint.append("；热门款").append(excluded.get(0).name()).append("不建议推荐");
        return hint.append("。").toString();
    }

    private List<String> values(List<Fact> facts, String dimension) {
        return facts.stream().filter(fact -> dimension.equals(fact.dimension())).map(Fact::value).distinct().toList();
    }

    public record RepeatedConcern(String concern, int mentions) {
    }

    public record RecommendedItem(
            String sku,
            String name,
            String category,
            int price,
            boolean hot,
            double score,
            List<String> reasons,
            String brand,
            String source,
            boolean ingredientsVerified) {
    }

    public record ExcludedItem(String sku, String name, boolean hot, String reason) {
    }

    public record Recommendation(
            String customerId,
            String profileSummary,
            List<RepeatedConcern> repeatedConcerns,
            List<RecommendedItem> items,
            List<ExcludedItem> excludedHotItems,
            List<String> missingInfo,
            String agentHint,
            List<Fact> facts) {
    }
}
