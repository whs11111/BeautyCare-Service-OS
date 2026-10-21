package com.beautycare.workflow;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Deterministic clock/acknowledgement rules; uncertain deadlines require an agent to set them. */
public final class ReceptionRules {
    private ReceptionRules() {}
    public static boolean meaningful(Map<String, Object> message) {
        if (message.get("attachment") != null) return true;
        String text = Objects.toString(message.get("text"), "").replaceAll("[\\s，,。.!！？?~～]", "");
        return !text.isBlank() && !text.matches("(?i)(你好|您好|在吗|谢谢您|谢谢|好的|好|嗯|哦|收到|ok|拜拜|再见)+");
    }
    public static boolean humanRequested(String text) {
        return text.matches("(?s).*(转人工|人工客服|真人客服|找人工|联系人工|找客服).*" );
    }
    public static String band(int score) { return score >= 70 ? "red" : score >= 40 ? "yellow" : "green"; }
    public static int emotionScore(String text) { return RuleSignals.emotionScore(text); }
    public static Optional<OffsetDateTime> deadline(String text, OffsetDateTime sentAt) {
        if (!text.matches("(?s).*(我|我们|这边|帮您|为您|承诺|保证|会在|将在).*")) return Optional.empty();
        if (!text.matches("(?s).*(反馈|回复|处理|完成|核实|答复|联系|告知|更新|解决|发货|补发).*")) return Optional.empty();
        Matcher duration = Pattern.compile("([0-9]+|半|一|两|二|三|四|五|六|七|八|九|十)(分钟|小时|天)(内|后|之内)").matcher(text);
        if (duration.find()) {
            String n = duration.group(1);
            double amount = n.equals("半") ? .5 : switch(n) {
                case "一" -> 1; case "两", "二" -> 2; case "三" -> 3; case "四" -> 4;
                case "五" -> 5; case "六" -> 6; case "七" -> 7; case "八" -> 8;
                case "九" -> 9; case "十" -> 10; default -> Double.parseDouble(n);
            };
            long seconds = (long)(amount * (duration.group(2).equals("分钟") ? 60 : duration.group(2).equals("小时") ? 3600 : 86400));
            if (seconds > 0 && seconds <= 90L * 86400) return Optional.of(sentAt.plusSeconds(seconds));
        }
        Matcher clock = Pattern.compile("(今天|明天|后天)?\\s*(上午|下午|晚上)?\\s*([01]?\\d|2[0-3])[:：点]([0-5]\\d)?(?:分)?(?:前|之前|左右|回复|反馈|完成|处理)").matcher(text);
        if (clock.find()) {
            int hour = Integer.parseInt(clock.group(3));
            if (List.of("下午", "晚上").contains(Objects.toString(clock.group(2), "")) && hour < 12) hour += 12;
            int days = "明天".equals(clock.group(1)) ? 1 : "后天".equals(clock.group(1)) ? 2 : 0;
            OffsetDateTime due = sentAt.plusDays(days).withHour(hour).withMinute(clock.group(4) == null ? 0 : Integer.parseInt(clock.group(4))).withSecond(0).withNano(0);
            if (due.isAfter(sentAt)) return Optional.of(due);
        }
        return Optional.empty();
    }
}
