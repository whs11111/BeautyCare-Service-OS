package com.beautycare.workflow;

import java.util.Map;
import java.util.Objects;
import com.beautycare.api.AgentController.AssistanceContextRequest;

/** Deterministic minimum next-step validation for a current damage complaint. */
public final class DamagedReplyGuard {
    private DamagedReplyGuard() {}

    public static String enforce(AssistanceContextRequest request, String draft) {
        String latest = Objects.toString(request.latestMessage(), "");
        if (request.messages() != null) {
            for (Map<String, Object> message : request.messages()) {
                if ("user".equalsIgnoreCase(ContextCompressor.role(message))) latest = ContextCompressor.text(message);
            }
        }
        if (!RuleSignals.intents(latest).contains(RuleSignals.INTENT_DAMAGED)) return draft;
        boolean currentIssue = false;
        boolean received = false;
        boolean requested = false;
        if (request.messages() != null) {
            for (Map<String, Object> message : request.messages()) {
                if (ContextCompressor.isPlatformNotice(message)) continue;
                String text = ContextCompressor.text(message);
                boolean user = "user".equalsIgnoreCase(ContextCompressor.role(message));
                if (user && RuleSignals.intents(text).contains(RuleSignals.INTENT_DAMAGED)) currentIssue = true;
                if (!currentIssue) continue;
                if (user && (Boolean.TRUE.equals(message.get("image")) || imageAttachment(message.get("attachment")))) received = true;
                if (!user && text.matches("(?s).*(提供|发|拍|上传).*(照片|图片).*")) requested = true;
            }
        }
        String value = Objects.toString(draft, "");
        boolean photo = value.contains("照片") || value.contains("图片");
        boolean verify = value.contains("核实") || value.contains("核查");
        boolean promises = value.matches("(?s).*(已为您|已经|马上|立即|尽快).*(退款|补发).*" );
        if (received) {
            if (verify && !value.matches("(?s).*(提供|拍|上传).*(照片|图片).*" ) && !promises) return draft;
            return "您提供的图片可以作为核实依据，我会结合漏液情况和订单，为您确认可用的处理方案。";
        }
        if (photo && verify && !promises) return draft;
        return requested
                ? "很抱歉，还需要麻烦您补充一下瓶身漏液处和外包装照片。收到后我会帮您核实，再确认该怎么处理。"
                : "很抱歉您收到的商品出现漏液或破损，给您添麻烦了。麻烦您提供瓶身异常处和外包装照片，核实后再为您确认可用的处理方案。";
    }

    private static boolean imageAttachment(Object attachment) {
        if (!(attachment instanceof Map<?, ?> map)) return false;
        return Objects.toString(map.get("type"), "").startsWith("image/");
    }
}
