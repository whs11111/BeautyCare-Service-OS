package com.beautycare.workflow;

import java.time.OffsetDateTime;
import java.util.*;

import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.beautycare.api.AgentController.RiskWorkflowResponse;
import com.beautycare.personalization.*;
import com.beautycare.skills.*;
import com.beautycare.ai.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.client.RestClient;

/**
 * 工作流冒烟测试（独立 main，与 ReceptionSmokeTest 一致，不随 mvn test 执行）。
 * 无 Key 时验证规则层、级联降级、压缩、个性化、预警闭环、技能加载；
 * 配置 DEEPSEEK_API_KEY / DASHSCOPE_API_KEY 环境变量后额外验证真实模型调用。
 * 运行时会写入 user.home/.beautycare-service-os，建议加 -Duser.home=<临时目录>。
 */
public class WorkflowSmokeTest {
    static int failures = 0;
    static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        TokenMeter meter = new TokenMeter();
        String dsKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
        ModelRouter router = new ModelRouter("auto", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                System.getenv().getOrDefault("DASHSCOPE_API_KEY", ""), "qwen-plus", "qwen-turbo", "qwen-turbo", "qwen-vl-plus",
                "https://api.deepseek.com/v1", dsKey, "deepseek-chat",
                "http://localhost:8000/v1", "", "beautycare-qwen-lora", "");
        ModelClient client = new ModelClient(RestClient.builder(), mapper, meter, router,
                new TrainingRecorder(mapper, true), 500, 30, 60);
        boolean live = client.configured();
        System.out.println("     router=" + router.describe());
        ContextCompressor compressor = new ContextCompressor(mapper);
        AssistanceWorkflowAiService workflow = new AssistanceWorkflowAiService(client, compressor, meter, mapper, 6);
        IssueRadar radar = new IssueRadar(24, 3);
        AlertCenter alertCenter = new AlertCenter(mapper);
        SkillRegistry replyRegistry = new SkillRegistry(mapper);
        var skillRequest = new AssistanceContextRequest(999L, "瓶子破损漏液了", "",
                List.of(msg("skill1", "user", "瓶子破损漏液了")), Map.of(), Map.of(), List.of(), List.of());
        String skillInput = mapper.writeValueAsString(replyRegistry.replyInsights(skillRequest));
        check(skillInput.contains("damaged-leakage") && skillInput.contains("不得要求用户自行联系快递理赔"),
                "单独回复接口加载破损处理步骤和禁止事项");
        var neutralRequest = new AssistanceContextRequest(999L, "你好", "",
                List.of(msg("skill2", "user", "你好")), Map.of(), Map.of(), List.of(), List.of());
        check(replyRegistry.replyInsights(neutralRequest).isEmpty(), "普通问候不加载无关售后规范");
        check(DamagedReplyGuard.enforce(skillRequest, "抱歉给您带来困扰").contains("照片"), "漏液草稿不能只有道歉");
        check(DamagedReplyGuard.enforce(skillRequest, "马上为您退款").contains("核实"), "漏液未核实不能承诺退款");
        var photographed = new AssistanceContextRequest(999L, "瓶子漏液了", "", List.of(
                msg("g1", "user", "瓶子漏液了"),
                Map.of("role", "user", "text", "照片", "image", true),
                msg("g3", "user", "瓶子漏液了")), Map.of(), Map.of(), List.of(), List.of());
        check(!DamagedReplyGuard.enforce(photographed, "请提供照片").contains("请提供"), "已上传照片不重复索要");
        check(DamagedReplyGuard.enforce(neutralRequest, "您好").equals("您好"), "非破损场景不受校验影响");
        for (String skillId : List.of("adverse-reaction", "damaged-leakage", "wrong-item-resend",
                "refund-return", "complaint-escalation", "logistics", "skin-advice")) {
            Map<String, Object> skillInsights = Map.of("skills", List.of(Map.of("id", skillId)));
            String guarded = SkillReplyGuard.enforce(skillRequest, skillInsights, "抱歉给您带来困扰");
            check(!guarded.equals("抱歉给您带来困扰") && !guarded.isBlank(), "七类技能拒绝空泛回复：" + skillId);
            check(!SkillReplyGuard.enforce(skillRequest, skillInsights, "已经为您退款，保证今天到账")
                    .contains("保证今天到账"), "技能禁止无依据承诺：" + skillId);
        }
        check(SkillReplyGuard.enforce(neutralRequest, Map.of(), "您好").equals("您好"), "没有匹配技能保留普通回复");
        VisionInspectionService vision = new VisionInspectionService(client, meter, mapper, radar, 768);
        ProductCatalog catalog = new ProductCatalog(mapper, "catalog.json");
        CustomerProfileStore store = new CustomerProfileStore(mapper);
        PersonalizationService personalization = new PersonalizationService(catalog, store, client, meter);
        AssistanceOrchestrator orchestrator = new AssistanceOrchestrator(workflow, vision, personalization, radar,
                new SkillRegistry(mapper), new SkillMemory(mapper), alertCenter);

        // 1. 规则层
        check(RuleSignals.intents("收到的口红色号不对").contains(RuleSignals.INTENT_WRONG_ITEM), "错发意图");
        check(!RuleSignals.isSafetyEvent("我对酒精过敏，这款能用吗"), "成分不耐受不算不良反应");
        check(RuleSignals.isSafetyEvent("用了你们的精华之后过敏了"), "使用后过敏算不良反应");
        check(RuleSignals.isSafetyEvent("脸上红肿刺痛"), "红肿刺痛算不良反应");
        check(RuleSignals.emotionLevel(RuleSignals.emotionScore("再不解决我就去12315投诉，还要曝光")).equals("critical"), "投诉=critical");
        check(!RuleSignals.needsSemanticModel(List.of("你好，我想问下快递到哪了")), "平稳咨询不需要LLM");
        check(RuleSignals.needsSemanticModel(List.of("呵呵，你们真行")), "反讽需要LLM");

        // 2. 所有消息均尝试模型；无可用模型时才显式降级。
        List<Map<String, Object>> calm = List.of(
                msg("m1", "user", "你好，请问可以开发票吗"),
                msg("m2", "agent", "可以的，请提供抬头"));
        RiskWorkflowResponse risk = workflow.analyzeRisk(new AssistanceContextRequest(1L, "你好，请问可以开发票吗", null, calm, null, null, null, null));
        check((live ? "LLM" : "RULE_FALLBACK").equals(risk.analysisMode()), "平稳会话也尝试模型，仅不可用时降级：" + risk.analysisMode());
        if (live) {
            RiskWorkflowResponse angry = workflow.analyzeRisk(new AssistanceContextRequest(2L, "气死了，第三次联系了还没解决，我要去12315投诉", null,
                    List.of(msg("x1", "user", "气死了，第三次联系了还没解决，我要去12315投诉")), null, null, null, null));
            System.out.println("     LLM风险：" + angry.emotion() + " risk=" + angry.riskScore() + " handoff=" + angry.handoffRequired() + " " + angry.evidence());
            check("LLM".equals(angry.analysisMode()) && angry.handoffRequired(), "负面会话升级到LLM并建议转人工");
        } else {
            RiskWorkflowResponse fallback = workflow.analyzeRisk(new AssistanceContextRequest(2L, "用了精华脸上红肿，在医院", null,
                    List.of(msg("x1", "user", "用了精华脸上红肿，在医院")), null, null, null, null));
            check("RULE_FALLBACK".equals(fallback.analysisMode()) && fallback.handoffRequired(), "无Key时安全事件按规则降级并转人工");
        }

        // 3. 上下文压缩
        List<Map<String, Object>> noisy = new ArrayList<>();
        Map<String, Object> greeting = msg("g", "agent", "我是客服01，很高兴为您服务！");
        greeting.put("automatic", true); greeting.put("agentAvatar", "01");
        noisy.add(greeting);
        for (int i = 0; i < 30; i++) noisy.add(msg("n" + i, i % 2 == 0 ? "user" : "agent", "第" + i + "条消息内容，" + "很长".repeat(20)));
        ContextCompressor.Result compressed = compressor.compress(new AssistanceContextRequest(3L, "x", "s", noisy, Map.of("sku", "MU-401", "remark", ""), null, null, null), ContextCompressor.Profile.CONTEXT);
        check(compressed.request().messages().size() == 16, "前情分析保留最近16条");
        check(compressed.request().messages().stream().noneMatch(m -> m.containsKey("automatic")), "自动问候被移除");
        check(!compressed.request().order().containsKey("remark"), "空字段被移除");
        System.out.println("     压缩节省字符：" + compressed.savedChars());
        String longResolvedText = "完整闭环信息".repeat(100);
        noisy.add(msg("long", "agent", longResolvedText));
        ContextCompressor.Result full = compressor.compress(
                new AssistanceContextRequest(3L, longResolvedText, "s", noisy, null, null, null, null),
                ContextCompressor.Profile.RESOLVED_EVENTS);
        check(full.request().messages().size() == 31, "已处理事件保留超过16条的全部有效消息");
        check("n0".equals(full.request().messages().get(0).get("id")), "保留最早消息");
        check(longResolvedText.equals(full.request().messages().get(30).get("text")), "已处理事件文字不截断到300字");
        check(longResolvedText.equals(full.request().latestMessage()), "已处理事件最新消息不截断");

        // 4. 个性化：强调三遍敏感泛红
        List<Map<String, Object>> chat = List.of(
                msg("u1", "user", "我是敏感肌，脸上老是泛红"),
                msg("a1", "agent", "了解，我帮您看看"),
                msg("u2", "user", "之前用别的精华也泛红，我对酒精过敏"),
                msg("a2", "agent", "这款是我们的爆款VC精华"),
                msg("u3", "user", "我说了好几次了，容易泛红！有没有适合我的精华，别再推爆款了"));
        personalization.updateProfile("chen", chat, null, null);
        PersonalizationService.Recommendation rec = personalization.recommend("chen", "有没有适合我的精华，别再推爆款了");
        System.out.println("     画像：" + rec.profileSummary());
        rec.items().forEach(item -> System.out.println("     推荐 " + item.sku() + " " + item.name() + " score=" + item.score() + " " + item.reasons()));
        rec.excludedHotItems().forEach(item -> System.out.println("     排除 " + item.name() + "：" + item.reason()));
        System.out.println("     提示：" + rec.agentHint());
        check(rec.repeatedConcerns().stream().anyMatch(c -> c.concern().equals("泛红") && c.mentions() == 3), "识别泛红被提及3次");
        check(!rec.items().isEmpty() && rec.items().stream().noneMatch(i -> i.sku().equals("SK-202")), "推荐不含含酒精爆款VC精华");
        check(rec.excludedHotItems().stream().anyMatch(i -> i.sku().equals("SK-202")), "说明排除爆款的原因");
        for (String category : List.of("洁面", "精华", "面霜", "水乳", "防晒", "粉底", "唇膏")) {
            var wording = personalization.recommend("chen", category);
            for (var item : wording.items()) {
                String text = String.join("；", item.reasons());
                for (String claim : List.of("无香精", "无酒精", "无A醇", "无高浓度酸", "无高浓度VC")) {
                    check(text.indexOf(claim) == text.lastIndexOf(claim), item.sku() + "不重复描述" + claim);
                }
            }
        }
        // 增量：同样消息再处理不会重复计数
        personalization.updateProfile("chen", chat, null, null);
        check(personalization.recommend("chen", "精华").repeatedConcerns().stream().anyMatch(c -> c.mentions() == 3), "重复处理不重复计数");
        // 客服纠正：去掉敏感肌
        CustomerProfileStore.Fact sensitive = store.get("chen").facts().stream().filter(f -> f.value().equals("敏感肌")).findFirst().orElseThrow();
        store.setStatus("chen", sensitive.id(), "REJECTED");
        check(!personalization.recommend("chen", "精华").profileSummary().contains("敏感肌"), "纠正后不再使用该偏好");

        // 5. 共性异常雷达
        var ingredientChat = List.of(msg("ing1", "user", "要含有神经酰胺的"));
        store.markProcessed("ingredient-test", List.of("ing1"));
        personalization.updateProfile("ingredient-test", ingredientChat, null, null);
        var ingredientFact = store.get("ingredient-test").facts().stream()
                .filter(f -> f.dimension().equals("ingredient") && f.value().equals("神经酰胺")).findFirst().orElseThrow();
        check(ingredientFact.mentions() == 1, "历史消息补提神经酰胺偏好");
        var ingredientRec = personalization.recommend("ingredient-test", "要含有神经酰胺的");
        check(!ingredientRec.items().isEmpty() && ingredientRec.items().stream()
                .limit(2).allMatch(i -> List.of("SK-201", "SK-301").contains(i.sku())), "已确认期望成分的商品优先，未知商品不硬排除");
        personalization.updateProfile("ingredient-test", ingredientChat, null, null);
        check(store.get("ingredient-test").facts().get(0).mentions() == 1, "补提不重复计数");
        store.setStatus("ingredient-test", ingredientFact.id(), "REJECTED");
        personalization.updateProfile("ingredient-test", ingredientChat, null, null);
        check(store.get("ingredient-test").facts().get(0).status().equals("REJECTED"), "刷新不覆盖成分人工排除");
        personalization.updateProfile("ingredient-negative", List.of(msg("ing2", "user", "不要含神经酰胺的")), null, null);
        check(store.get("ingredient-negative").facts().stream().noneMatch(f -> f.dimension().equals("ingredient")), "不要成分不误识别为想要");
        for (long id = 10; id < 13; id++) radar.report("MU-401", "丝绒雾面唇釉 #05", "WRONG_ITEM", id, "test");
        check(radar.alerts().size() == 1 && radar.alerts().get(0).affectedConversations() == 3, "3个会话同SKU错发→集中预警");

        // 6. 编排：无 Key 时 LLM 节点失败但链路不中断
        List<Map<String, Object>> commitments = List.of(Map.of("description", "24小时内补发", "dueAt", OffsetDateTime.now().minusHours(18).toString()));
        List<Map<String, Object>> runChat = new ArrayList<>(List.of(
                msg("r1", "user", "收到的唇釉色号不对，我买的是01，发来的是05"),
                msg("r2", "agent", "抱歉，我们24小时内为您补发"),
                msg("r3", "user", "好的谢谢")));
        AssistanceOrchestrator.RunResult result = orchestrator.run(new AssistanceOrchestrator.RunRequest(
                20L, "sun", null, null, runChat, Map.of("sku", "MU-401", "productName", "丝绒雾面唇釉 #05"), null, null,
                commitments,
                null, null, null, null));
        result.nodes().forEach(n -> System.out.println("     节点 " + n.name() + " " + n.status() + " " + n.mode() + " " + n.note()));
        result.actions().forEach(a -> System.out.println("     行动 " + a.title() + "：" + a.reason()));
        check(result.nodes().size() == 8, "8个节点均有轨迹");
        check(result.skills().stream().anyMatch(sk -> sk.id().equals("wrong-item-resend")), "加载错发补发技能");
        if (live) {
            check(result.draft() != null && !result.draft().draft().isBlank(), "生成回复草稿：" + (result.draft() == null ? "" : result.draft().draft()));
            check(result.context() != null, "前情分析：" + (result.context() == null ? "" : result.context().contextSummary()));
            AssistanceOrchestrator.RunResult again = orchestrator.run(new AssistanceOrchestrator.RunRequest(
                    20L, "sun", null, null, runChat, Map.of("sku", "MU-401", "productName", "丝绒雾面唇釉 #05"), null, null,
                    commitments,
                    null, null, null, null));
            again.nodes().forEach(n -> System.out.println("     二次 " + n.name() + " " + n.mode() + " tokens=" + (n.promptTokens() + n.completionTokens())));
            check(again.nodes().stream().anyMatch(n -> n.id().equals("context") && n.mode().equals("CACHED")), "二次运行前情分析复用");

            List<Map<String, Object>> adviceChat = new ArrayList<>(chat);
            AssistanceOrchestrator.RunResult advice = orchestrator.run(new AssistanceOrchestrator.RunRequest(
                    30L, "chen2", null, null, adviceChat, null, null, null, null, null, null, null, null));
            System.out.println("     推荐草稿：" + (advice.draft() == null ? "" : advice.draft().draft()));
            advice.nodes().forEach(n -> System.out.println("     推荐 " + n.name() + " " + n.status() + " " + n.mode() + " " + n.note()));
            check(advice.recommendation() != null && !advice.recommendation().items().isEmpty(), "推荐场景给出清单");
        }
        check(result.actions().stream().anyMatch(a -> a.type().equals("COMMITMENT_OVERDUE")), "承诺超时提醒");
        check(result.actions().stream().anyMatch(a -> a.type().equals("QUALITY_CLUSTER_ALERT")), "关联集中异常预警");
        check(result.risk() != null && (live ? "LLM" : "RULE_FALLBACK").equals(result.risk().analysisMode()), "平稳收尾也走模型，不可用时显式降级");
        check(result.alertIds().size() == 2, "承诺超时+集中异常推送到预警中心");
        String alertId = result.alertIds().get(0);
        alertCenter.transition(alertId, "ACKNOWLEDGED", "主管A", "已认领");
        boolean blocked = false;
        try { alertCenter.transition(alertId, "RESOLVED", "主管A", ""); } catch (IllegalArgumentException e) { blocked = true; }
        check(blocked, "闭环必须填写处理结果");
        alertCenter.transition(alertId, "RESOLVED", "主管A", "已催仓库并通知用户");
        // 同一会话再次触发：已闭环的那条重新开新预警，未闭环的那条只累加次数
        AssistanceOrchestrator.RunResult rerun = orchestrator.run(new AssistanceOrchestrator.RunRequest(
                20L, "sun", null, null, runChat, Map.of("sku", "MU-401", "productName", "丝绒雾面唇釉 #05"), null, null,
                commitments,
                null, null, null, null));
        check(!rerun.alertIds().contains(alertId), "已闭环预警再次触发会新开一条");
        AlertCenter.Summary summary = alertCenter.summary();
        System.out.println("     预警概览：" + summary);
        check(summary.byStatus().get("RESOLVED") == 1 && summary.total() == 3 && !summary.openByType().containsKey("SAFETY"), "预警统计正确（成分不耐受不触发安全预警）");
        System.out.println("     草稿：" + (result.draft() == null ? "(无Key)" : result.draft().draft()));

        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meter.snapshot()));
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Map<String, Object> msg(String id, String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("role", role); m.put("text", text);
        m.put("time", OffsetDateTime.now().toString());
        return m;
    }
}
