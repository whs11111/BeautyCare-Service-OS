package com.beautycare.workflow;

import java.util.*;
import com.beautycare.api.AgentController.AssistanceContextRequest;

/** Explicit gates for the seven built-in skills, not a claim to validate arbitrary prose SOPs. */
public final class SkillReplyGuard {
    private SkillReplyGuard() {}

    public static String enforce(AssistanceContextRequest request, Map<String, Object> insights, String draft) {
        if (insights == null || !(insights.get("skills") instanceof List<?> skills) || skills.isEmpty()) return draft;
        Set<String> ids = new LinkedHashSet<>();
        for (Object value : skills) if (value instanceof Map<?, ?> skill) ids.add(Objects.toString(skill.get("id"), ""));
        String value = Objects.toString(draft, "");
        if (value.matches("[很非常实在]*抱歉[，,]?给您带来[了的]*困扰[。！!]*")
                || value.matches("(?s).*(保证|肯定|百分之百|已经为您退款|已为您退款|正常现象|您肤质问题|不是产品问题).*")) value = "";
        if (ids.contains("adverse-reaction")) {
            String latest = Objects.toString(request.latestMessage(), "");
            String context = latest;
            if (request.messages() != null) context = request.messages().stream()
                .filter(message -> "user".equalsIgnoreCase(ContextCompressor.role(message)))
                .map(ContextCompressor::text).reduce("", (a,b) -> a + "\n" + b) + "\n" + latest;
            boolean noCertificate = context.matches("(?s).*(没有|无|没|未).{0,8}(就医|医院|诊断|医疗).{0,5}(凭证|证明|记录|单据).*" )
                && !latest.matches("(?s).*(现在有|已经有|拿到|补了|补齐).{0,10}(就医|医院|诊断|医疗).{0,6}(凭证|证明|记录|单据).*" );
            boolean asksCertificate = value.matches("(?s).*(请|麻烦|需要|须|必须).{0,18}(就医|医院|诊断|医疗).{0,6}(凭证|证明|记录|单据).*" );
            boolean dangerous = value.matches("(?s).*(肯定不过敏|正常现象|继续使用|加大用量|不用就医|服用.{0,8}(药|片)|涂抹.{0,8}药|确诊|一定是您|不是产品问题).*" );
            // Preserve contextual generation, replacing only unsafe or contradictory output.
            if (!dangerous && !(noCertificate && asksCertificate) && !value.isBlank())
                return contains(value, "抱歉", "对不起") ? value : "很抱歉让您遇到这样的使用体验。" + value;
            if (noCertificate)
                return "很抱歉让您经历了这些不适。您没有就医凭证的情况我了解了，可以先提供您提到的过敏照片，供我们核实使用情况和后续处理方式。如不适再次出现或持续，请及时就医。";
            return "很抱歉给您带来了不适。请先停用这款产品，方便的话，请您说明目前的情况，我会根据您已有的材料帮您核实。若症状明显或持续，请及时就医。";
        }
        boolean unsafe = value.matches("(?s).*(保证|肯定|一定|百分之百|正常现象|您肤质问题|系统问题|自己联系快递|自行联系快递|别投诉|不要投诉|自己拿错).*" )
                || value.matches("(?s).*(已经|已为您|马上|立即|尽快).*(退款|补发|赔偿|到账|创建|安排|转接).*" )
                || value.matches("(?s).*([0-9一二三四五六七八九十两]+)(分钟|小时|天|工作日).*(到账|到货|送达|退款).*" );
        if (ids.contains("damaged-leakage")) {
            if (!unsafe && !value.isBlank()) return value;
            String guarded = DamagedReplyGuard.enforce(request, unsafe ? "" : value);
            if (!guarded.isBlank()) return guarded;
        }
        if (ids.contains("wrong-item-resend")) {
            if (!unsafe && !value.isBlank()) return value;
            return "很抱歉您收到的商品与预期不符。我会先为您核对订单商品、规格和实收情况，核实后再按售后政策确认补发及寄回方案。具体时效以实际工单结果为准。";
        }
        if (ids.contains("refund-return")) {
            if (!unsafe && !value.isBlank()) return value;
            return "我会先帮您核对订单状态、退换原因或已有退款申请的进度，再为您确认适用的处理方式。到账时间以实际退款结果为准。";
        }
        if (ids.contains("complaint-escalation")) {
            if (!unsafe && !value.isBlank()) return value;
            return "很抱歉，这件事还没处理好。我会先看一下之前的沟通记录，帮您核实还没解决的问题，再确认可以怎么处理。如果需要主管跟进，我会申请转交，并带上已有信息，避免您重复说明。";
        }
        if (ids.contains("logistics")) {
            return LogisticsReplyGuard.enforce(request, value, unsafe);
        }
        if (ids.contains("skin-advice")) {
            if (!unsafe && !value.isBlank()) return value;
            // A standalone draft has no validated product list; do not invent recommendations.
            if (!(insights.get("recommendation") instanceof Map<?, ?> recommendation)
                    || !(recommendation.get("items") instanceof List<?> items) || items.isEmpty()) {
                return "我会先按您已经说明的肤质和成分偏好筛选，暂不推荐尚未核对的商品。您目前最想改善的一个问题是什么？";
            }
            List<String> names = items.stream().filter(item -> item instanceof Map<?, ?>)
                    .map(item -> Objects.toString(((Map<?, ?>) item).get("name"), ""))
                    .filter(name -> !name.isBlank()).limit(2).toList();
            return names.isEmpty() ? "暂时没有已核对的推荐商品，麻烦您补充一下选购需求，便于进一步筛选。"
                    : "按您已说明的肤质和偏好，推荐清单中的可选商品是：" + String.join("、", names)
                            + "。请您使用前留意自身成分不耐受情况，任何产品都不能保证一定不过敏。";
        }
        return unsafe ? "我会先为您核实当前信息和适用的处理权限，再确认下一步方案。" : value;
    }

    private static boolean contains(String value, String... words) {
        return Arrays.stream(words).anyMatch(value::contains);
    }
}
