package com.beautycare.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import com.beautycare.api.AgentController.AssistanceContextRequest;

/** Keep shipment stages separate instead of reciting every branch in the logistics SOP. */
public final class LogisticsReplyGuard {
    private static final Pattern NOT_SHIPPED = Pattern.compile("未发货|没发货|不发货|未出库|没出库|还没发|迟迟不发|尚未发");
    private static final Pattern APOLOGY_FIRST = Pattern.compile("^(?:.{1,8}(?:女士|先生)[，,]\\s*)?(?:您好[，,]\\s*)?(?:很抱歉|非常抱歉|实在抱歉|抱歉|对不起)");
    private LogisticsReplyGuard() {}

    public static String enforce(AssistanceContextRequest request, String draft, boolean unsafe) {
        String latest = Objects.toString(request.latestMessage(), "");
        if (latest.isBlank() && request.messages() != null) {
            for (Map<String, Object> message : request.messages()) {
                if ("user".equalsIgnoreCase(ContextCompressor.role(message)) && !ContextCompressor.isPlatformNotice(message))
                    latest = ContextCompressor.text(message);
            }
        }
        String value = Objects.toString(draft, "");
        boolean notShipped = NOT_SHIPPED.matcher(latest).find();
        boolean signedMissing = !notShipped && latest.contains("签收")
                && contains(latest, "未收到", "没收到", "没拿到", "没有收到", "没见到");
        boolean verifiedAction = contains(value, "核实", "核对", "查询");
        boolean inventedResult = value.matches("(?s).*(已到|正在派送|明天|今天|预计送达|保证|已经查|查到|已核实|已催促|已催单).*" );
        if (notShipped) {
            boolean wrongStage = contains(value, "签收", "代收", "丢件", "派送", "在途", "运单", "物流节点", "到货", "送达");
            if (!unsafe && !inventedResult && !wrongStage && verifiedAction
                    && contains(value, "发货", "出库") && APOLOGY_FIRST.matcher(value).find()
                    && !contains(value, "完全理解", "认真对待")) return value;
            return "很抱歉，让您久等了。我先帮您核实为什么还没有发货，确认后再向您说明发货安排。";
        }
        if (signedMissing) {
            if (!unsafe && !inventedResult && verifiedAction && contains(value, "签收", "代收")
                    && !NOT_SHIPPED.matcher(value).find()) return value;
            return "很抱歉，商品显示签收却没收到，让您担心了。麻烦您确认一下是否有人代收，我也会帮您核实签收记录，再确认该怎么处理。";
        }
        // Without an explicit signed-but-missing report, do not introduce that hypothetical branch.
        if (!unsafe && !inventedResult && verifiedAction && contains(value, "物流", "运单", "快递")
                && !contains(value, "签收", "代收", "丢件") && !NOT_SHIPPED.matcher(value).find()) return value;
        return "我会先帮您核对这笔订单的发货及物流进度，再根据实际记录为您说明当前情况。具体送达时间需要核实后才能确认。";
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
