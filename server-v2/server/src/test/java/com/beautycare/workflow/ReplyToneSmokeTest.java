package com.beautycare.workflow;

import java.util.List;
import java.util.Map;
import com.beautycare.api.AgentController.AssistanceContextRequest;

/** Offline tone checks for the deterministic customer-facing fallback replies. */
public class ReplyToneSmokeTest {
    public static void main(String[] args) {
        var request = new AssistanceContextRequest(999L, "瓶子漏液了", "",
                List.of(Map.of("role", "user", "text", "瓶子漏液了")),
                Map.of(), Map.of(), List.of(), List.of());
        for (String skill : List.of("adverse-reaction", "damaged-leakage", "wrong-item-resend",
                "refund-return", "complaint-escalation", "logistics", "skin-advice")) {
            String draft = SkillReplyGuard.enforce(request, Map.of("skills", List.of(Map.of("id", skill))), "抱歉");
            if (!draft.contains("您") || draft.contains("亲") || draft.contains("宝子")) {
                throw new AssertionError("Fallback tone: " + skill);
            }
            if (draft.contains("；")) throw new AssertionError("Semicolon in fallback: " + skill);
            System.out.println("PASS polite fallback: " + skill);
        }
        String cleaned = AssistanceWorkflowAiService.sanitizeCustomerFacingDraft("我帮您核实；核实后答复您;请您稍等。");
        if (cleaned.contains("；") || cleaned.contains(";")) throw new AssertionError("Semicolon in final draft");
        String url = "https://example.com/item;a=1";
        if (!AssistanceWorkflowAiService.sanitizeCustomerFacingDraft(url).equals(url)) throw new AssertionError("URL changed");
        System.out.println("ALL PASSED");
    }
}
