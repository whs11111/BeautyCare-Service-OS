package com.beautycare.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 零 Token 规则层：意图路由、词典情绪分与“是否需要大模型”判断。
 * 只有规则层无法给出可靠结论时，才把消息交给大模型（级联调用，节约 Token）。
 */
public final class RuleSignals {

    public static final String INTENT_LOGISTICS = "物流查询";
    public static final String INTENT_REFUND = "退款退货";
    public static final String INTENT_WRONG_ITEM = "错发补发";
    public static final String INTENT_DAMAGED = "破损漏液";
    public static final String INTENT_SAFETY = "过敏不良反应";
    public static final String INTENT_SKIN_ADVICE = "肤质咨询推荐";
    public static final String INTENT_INVOICE = "发票";
    public static final String INTENT_URGE = "催促进度";
    public static final String INTENT_COMPLAINT = "投诉";

    private static final Map<String, List<String>> INTENT_KEYWORDS = new LinkedHashMap<>();
    static {
        INTENT_KEYWORDS.put(INTENT_COMPLAINT, List.of("投诉", "12315", "曝光", "消协", "黑猫", "差评"));
        INTENT_KEYWORDS.put(INTENT_WRONG_ITEM, List.of("发错", "错发", "色号不对", "不是我买的", "不是我下单", "补发", "少发", "漏发", "货不对"));
        INTENT_KEYWORDS.put(INTENT_DAMAGED, List.of("破损", "碎了", "压坏", "漏液", "漏了", "瓶子裂", "断了", "撒了"));
        INTENT_KEYWORDS.put(INTENT_REFUND, List.of("退款", "退货", "退钱", "赔偿", "赔款", "仅退款", "换货"));
        INTENT_KEYWORDS.put(INTENT_LOGISTICS, List.of("物流", "快递", "发货", "到哪", "没收到", "签收", "派送", "单号"));
        INTENT_KEYWORDS.put(INTENT_INVOICE, List.of("发票", "开票", "抬头"));
        INTENT_KEYWORDS.put(INTENT_URGE, List.of("怎么还没", "到底", "多久", "催", "还要等", "一直没"));
        INTENT_KEYWORDS.put(INTENT_SKIN_ADVICE, List.of("推荐", "适合", "敏感肌", "干皮", "油皮", "混油", "混干", "肤质", "痘", "毛孔", "泛红", "卡粉", "哪款", "用什么", "屏障"));
    }

    private static final List<String> STRONG_NEGATIVE = List.of(
            "投诉", "12315", "曝光", "骗子", "垃圾", "气死", "忍无可忍", "欺骗", "再也不买", "差评", "恶心", "坑人", "消协", "避雷", "千万别买", "大家别买");
    private static final List<String> MILD_NEGATIVE = List.of(
            "怎么还没", "失望", "麻烦", "着急", "不满意", "还是没", "第三次", "多次", "又", "无语", "离谱", "敷衍", "没人管", "太慢", "过敏", "红肿", "踩雷", "翻车", "智商税", "后悔");
    private static final List<String> POSITIVE = List.of("谢谢", "好的", "满意", "辛苦", "棒", "感谢", "可以的", "没问题");

    /** 出现这些词时，规则层不足以判断语义，需要交给大模型。 */
    private static final List<String> AMBIGUOUS_CUES = List.of("呵呵", "行吧", "算了", "随便", "真行", "好家伙", "厉害了", "服了");

    private RuleSignals() {
    }

    private static final List<String> REACTION_WORDS = List.of(
            "红肿", "刺痛", "起疹", "疹子", "烂脸", "医院", "就医", "灼烧", "发痒", "脱皮", "肿了", "起包");
    private static final List<String> USAGE_WORDS = List.of("用了", "用完", "擦了", "涂了", "抹了", "敷了", "上脸", "之后", "以后");
    private static final Pattern INTOLERANCE = Pattern.compile("对.{0,8}过敏|怕.{0,4}过敏|过敏(体质|肌)|会不会过敏|容易过敏");

    /**
     * 安全事件：出现明确的皮肤反应词，或“使用后过敏”。
     * “我对酒精过敏”“会不会过敏”这类成分不耐受 / 咨询表述不算不良反应。
     */
    public static boolean isSafetyEvent(String text) {
        String value = Objects.toString(text, "");
        if (containsAny(value, REACTION_WORDS)) return true;
        if (!value.contains("过敏")) return false;
        if (INTOLERANCE.matcher(value).find() && !containsAny(value, USAGE_WORDS)) return false;
        return true;
    }

    public static List<String> intents(String text) {
        String value = Objects.toString(text, "");
        List<String> result = new ArrayList<>();
        if (isSafetyEvent(value)) result.add(INTENT_SAFETY);
        INTENT_KEYWORDS.forEach((intent, words) -> {
            if (words.stream().anyMatch(value::contains)) result.add(intent);
        });
        return result;
    }

    public static Set<String> intents(List<String> texts) {
        Set<String> result = new LinkedHashSet<>();
        texts.forEach(text -> result.addAll(intents(text)));
        return result;
    }

    /** 0-100，越高越负面。 */
    public static int emotionScore(String text) {
        String value = Objects.toString(text, "");
        int score = 30;
        for (String word : STRONG_NEGATIVE) if (value.contains(word)) score += 30;
        for (String word : MILD_NEGATIVE) if (value.contains(word)) score += 12;
        for (String word : POSITIVE) if (value.contains(word)) score -= 12;
        if (value.contains("！！") || value.contains("!!") || value.contains("？？") || value.contains("??")) score += 10;
        return Math.max(0, Math.min(100, score));
    }

    public static String emotionLevel(int score) {
        if (score >= 80) return "critical";
        if (score >= 55) return "negative";
        if (score <= 20) return "positive";
        return "neutral";
    }

    /**
     * 判断规则层结论是否足够可靠：出现负面词、反讽类模糊表达、或长文本时交给大模型。
     */
    public static boolean isSarcasm(String text) {
        String value = Objects.toString(text, "");
        return Pattern.compile("(下次|以后)(别|不要|可别)|托.{0,4}的福|多亏(了)?你们|真是(谢谢|感谢|太)|(真|可|太)(好|棒|行|快|高|专业|贴心).{0,12}(还没|没|别|不|呵呵|[?？])").matcher(value).find()
                || (containsAny(value, List.of("真好", "真棒", "真行", "真快", "真高", "太好了", "好极了", "厉害", "佩服", "666", "绝了", "可真", "感谢", "谢谢", "辛苦", "贴心", "专业", "周到", "效率", "服务好", "服务真"))
                && containsAny(value, List.of("下次别", "别再", "别这么", "再也不", "不会再", "还没", "没发", "没到", "没人", "终于有人", "终于回", "终于理", "第三次", "多次", "一个星期", "一周", "几天了", "等了", "呵呵", "吗？", "吗?", "呢？", "服了", "多亏", "托你们的福", "真是的")));
    }

    public static boolean needsSemanticModel(List<String> recentUserTexts) {
        for (String text : recentUserTexts) {
            String value = Objects.toString(text, "");
            if (isSarcasm(value)) return true;
            if (STRONG_NEGATIVE.stream().anyMatch(value::contains)) return true;
            if (MILD_NEGATIVE.stream().anyMatch(value::contains)) return true;
            if (AMBIGUOUS_CUES.stream().anyMatch(value::contains)) return true;
            if (value.length() > 80) return true;
        }
        return false;
    }

    public static boolean containsAny(String text, List<String> words) {
        String value = Objects.toString(text, "");
        return words.stream().anyMatch(value::contains);
    }
}
