package com.beautycare.workflow;

import java.util.List;
import java.util.Map;
import com.beautycare.api.AgentController.AssistanceContextRequest;

/** Offline regression: do not confuse dispatch delays with missing signed parcels. */
public class LogisticsReplySmokeTest {
    private static AssistanceContextRequest request(String text) {
        return new AssistanceContextRequest(999L, text, "", List.of(Map.of("role", "user", "text", text)),
                Map.of(), Map.of(), List.of(), List.of());
    }
    private static String reply(AssistanceContextRequest request, String draft) {
        return SkillReplyGuard.enforce(request, Map.of("skills", List.of(Map.of("id", "logistics"))), draft);
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); System.out.println("PASS " + name); }
    public static void main(String[] args) {
        var unshipped = request("已经三天了还没发货");
        String corrected = reply(unshipped, "我会帮您核实物流记录；如果签收未收到，请您确认代收点。");
        check(corrected.contains("发货安排") && corrected.contains("抱歉") && !corrected.contains("；") && !corrected.contains("签收")
                && !corrected.contains("代收"), "screenshot regression: dispatch delay is not signed-but-missing");
        String suitable = "很抱歉，让您久等了。我会帮您核实发货安排和延迟原因。";
        check(reply(unshipped, suitable).equals(suitable), "keep relevant empathetic model wording");
        String awkward = "孙女士，让您等了三天还没有发货，反复催促也没有得到明确答复，实在抱歉，您的不满我完全理解。我这就帮您核实发货情况，请您稍等。";
        String natural = reply(unshipped, awkward);
        check(natural.startsWith("很抱歉") && !natural.contains("完全理解") && !natural.contains("反复催促"),
                "apology first, no stitched empathy slogans");
        check(!reply(request("发货三天了，快递一直没有更新"), "抱歉").contains("代收"), "in transit does not introduce collection point");
        check(reply(request("显示签收了，但是我没收到"), "抱歉").contains("签收记录"), "signed-but-missing has its own next step");
        check(!reply(request("快递到哪儿了"), "如果签收未收到，请您核实代收点").contains("代收"), "generic tracking does not assume signed");
        check(!reply(unshipped, "已经为您安排发货，保证明天送达").contains("明天"), "no fabricated dispatch promises");
        var historical = new AssistanceContextRequest(999L, "已经三天了还没发货", "", List.of(
                Map.of("role", "user", "text", "上一笔显示签收没收到"),
                Map.of("role", "user", "text", "这一笔已经三天了还没发货")), Map.of(), Map.of(), List.of(), List.of());
        check(!reply(historical, "抱歉").contains("签收"), "old signed order cannot contaminate current dispatch issue");
        System.out.println("ALL PASSED");
    }
}
