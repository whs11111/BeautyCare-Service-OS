package com.beautycare.workflow;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.beautycare.ai.ModelClient.ModelUsage;
import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.beautycare.api.AgentController.ContextWorkflowResponse;
import com.beautycare.api.AgentController.ReplyDraftResponse;
import com.beautycare.api.AgentController.RiskWorkflowResponse;
import com.beautycare.personalization.PersonalizationService;
import com.beautycare.personalization.PersonalizationService.Recommendation;
import com.beautycare.skills.SkillMemory;
import com.beautycare.skills.SkillRegistry;
import com.beautycare.skills.SkillRegistry.Skill;
import com.beautycare.workflow.VisionInspectionService.VisionResult;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * 客服辅助 Agent 工作流编排：
 *
 * <pre>
 * ① 预处理/意图路由(规则) ─┬─ ② 前情分析(LLM，事实无变化则复用)
 *                          ├─ ③ 情绪风险(规则→LLM 级联)
 *                          ├─ ④ 图片核验(VLM，仅有图片时)
 *                          └─ ⑤ 个性化推荐(规则抽取→必要时轻量模型)
 *                                   ↓
 *              ⑥a 技能选择(规则：按意图加载 SOP + 检索客服认可的示例)
 *                     ⑥ 回复草稿(轻量模型，引用上游结论与技能)
 *                                   ↓
 *                     ⑦ 行动建议(规则：工单/升级/跟进/集中预警)
 * </pre>
 *
 * 每个节点记录执行模式（RULE/LLM/VLM/CACHED/SKIPPED）、模型与 Token，供前端展示和成本评估。
 */
@Service
public class AssistanceOrchestrator {

    private static final Set<String> RADAR_INTENTS = Set.of(
            RuleSignals.INTENT_WRONG_ITEM, RuleSignals.INTENT_DAMAGED, RuleSignals.INTENT_SAFETY);

    private final AssistanceWorkflowAiService workflowService;
    private final VisionInspectionService visionService;
    private final PersonalizationService personalizationService;
    private final IssueRadar issueRadar;
    private final SkillRegistry skillRegistry;
    private final SkillMemory skillMemory;
    private final AlertCenter alertCenter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AssistanceOrchestrator(
            AssistanceWorkflowAiService workflowService,
            VisionInspectionService visionService,
            PersonalizationService personalizationService,
            IssueRadar issueRadar,
            SkillRegistry skillRegistry,
            SkillMemory skillMemory,
            AlertCenter alertCenter) {
        this.alertCenter = alertCenter;
        this.workflowService = workflowService;
        this.skillRegistry = skillRegistry;
        this.skillMemory = skillMemory;
        this.visionService = visionService;
        this.personalizationService = personalizationService;
        this.issueRadar = issueRadar;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public RunResult run(RunRequest input) {
        long started = System.currentTimeMillis();
        List<NodeTrace> traces = new CopyOnWriteArrayList<>();

        // ① 预处理与意图路由：纯规则，零 Token。
        long t0 = System.currentTimeMillis();
        String latest = latestUserMessage(input);
        List<String> recentUserTexts = recentUserTexts(input.messages(), latest);
        Set<String> intents = RuleSignals.intents(recentUserTexts);
        List<String> imageUrls = input.imageUrls() != null && !input.imageUrls().isEmpty()
                ? input.imageUrls()
                : visionService.findImageUrls(lastUserTurn(input.messages()));
        String customerId = customerId(input);
        String sku = IssueRadar.skuOf(input.order());
        for (String intent : intents) {
            if (RADAR_INTENTS.contains(intent)) {
                issueRadar.report(sku, IssueRadar.productNameOf(input.order()), radarIssueType(intent),
                        input.conversationId(), "文本意图");
            }
        }
        traces.add(new NodeTrace("preprocess", "预处理·意图路由", "DONE", "RULE", "", 0, 0,
                System.currentTimeMillis() - t0,
                "意图：" + (intents.isEmpty() ? "未识别" : String.join("、", intents))
                        + (imageUrls.isEmpty() ? "" : "；图片" + imageUrls.size() + "张")));

        AssistanceContextRequest request = new AssistanceContextRequest(
                input.conversationId(), latest, input.previousSummary(), input.messages(),
                input.order(), input.customer(), input.workOrders(), input.commitments());

        CompletableFuture<ContextWorkflowResponse> context = Boolean.FALSE.equals(input.includeContext())
                ? skipped(traces, "context", "前情分析", "调用方未请求")
                : node(traces, "context", "前情分析", sink -> workflowService.analyzeContext(request, sink));

        CompletableFuture<RiskWorkflowResponse> risk =
                node(traces, "risk", "情绪风险", sink -> workflowService.analyzeRisk(request, sink));

        CompletableFuture<VisionResult> vision = imageUrls.isEmpty()
                ? skipped(traces, "vision", "图片核验", "本轮没有图片")
                : node(traces, "vision", "图片核验",
                        sink -> visionService.inspect(input.conversationId(), imageUrls, input.order(), latest, sink));

        boolean wantsAdvice = intents.contains(RuleSignals.INTENT_SKIN_ADVICE) || Boolean.TRUE.equals(input.forceRecommendation());
        CompletableFuture<Recommendation> recommendation = node(traces, "personalization", "个性化推荐", sink -> {
            personalizationService.updateProfile(customerId, input.messages(), input.workOrders(), sink);
            return wantsAdvice ? personalizationService.recommend(customerId, latest) : null;
        });

        CompletableFuture.allOf(context, risk, vision, recommendation).join();
        ContextWorkflowResponse contextResult = context.join();
        RiskWorkflowResponse riskResult = risk.join();
        VisionResult visionResult = vision.join();
        Recommendation recommendationResult = recommendation.join();

        // ⑥ 回复草稿：引用上游节点结论，使用轻量模型。
        Map<String, Object> insights = new LinkedHashMap<>();
        if (visionResult != null && !"SKIPPED".equals(visionResult.mode())) insights.put("vision", visionResult);
        if (recommendationResult != null) insights.put("recommendation", Map.of(
                "profileSummary", recommendationResult.profileSummary(),
                "repeatedConcerns", recommendationResult.repeatedConcerns(),
                "items", recommendationResult.items(),
                "missingInfo", recommendationResult.missingInfo()));
        if (riskResult != null) insights.put("risk", Map.of(
                "emotion", riskResult.emotion(),
                "handoffRequired", riskResult.handoffRequired()));

        // ⑥a 技能选择：零 Token，按意图/图片/风险挑选 SOP，并检索客服认可过的相似回复作为示例。
        long tSkill = System.currentTimeMillis();
        List<Skill> skills = skillRegistry.select(intents,
                visionResult == null ? null : visionResult.issueType(),
                recommendationResult != null,
                riskResult != null && riskResult.handoffRequired());
        List<Map<String, Object>> skillViews = new ArrayList<>();
        int exampleCount = 0;
        for (Skill skill : skills) {
            Map<String, Object> view = new LinkedHashMap<>(skillRegistry.promptView(skill));
            List<String> examples = skillMemory.retrieve(skill.id(), latest, 1).stream()
                    .map(SkillMemory.Exemplar::finalReply).toList();
            if (!examples.isEmpty()) {
                view.put("approvedExamples", examples);
                exampleCount += examples.size();
            }
            skillViews.add(view);
        }
        if (!skillViews.isEmpty()) insights.put("skills", skillViews);
        traces.add(new NodeTrace("skills", "技能加载", "DONE", "RULE", "", 0, 0, System.currentTimeMillis() - tSkill,
                skills.isEmpty() ? "无匹配技能" : skills.stream().map(Skill::name).reduce((a, b) -> a + "、" + b).orElse("")
                        + (exampleCount > 0 ? "；示例" + exampleCount + "条" : "")));

        ReplyDraftResponse draft = Boolean.FALSE.equals(input.includeDraft())
                ? this.<ReplyDraftResponse>skipped(traces, "reply-draft", "回复草稿", "调用方未请求").join()
                : node(traces, "reply-draft", "回复草稿",
                        sink -> workflowService.generateReplyDraft(request, insights, sink)).join();

        // ⑦ 行动建议：规则生成，需要客服确认。
        long t6 = System.currentTimeMillis();
        List<ActionSuggestion> actions = actions(input, intents, riskResult, visionResult, recommendationResult, sku);
        List<String> alertIds = raiseAlerts(input, actions, riskResult);
        traces.add(new NodeTrace("actions", "行动建议·预警", "DONE", "RULE", "", 0, 0,
                System.currentTimeMillis() - t6, actions.size() + "条待确认操作"
                        + (alertIds.isEmpty() ? "" : "；推送预警" + alertIds.size() + "条")));

        List<NodeTrace> ordered = order(traces);
        long prompt = ordered.stream().mapToLong(NodeTrace::promptTokens).sum();
        long completion = ordered.stream().mapToLong(NodeTrace::completionTokens).sum();
        long modelCalls = ordered.stream().filter(trace -> List.of("LLM", "VLM").contains(trace.mode())).count();
        return new RunResult(
                input.conversationId(),
                customerId,
                List.copyOf(intents),
                contextResult,
                riskResult,
                visionResult,
                recommendationResult,
                draft,
                skills.stream().map(skill -> new SkillRef(skill.id(), skill.name())).toList(),
                actions,
                alertIds,
                ordered,
                new RunCost(modelCalls, ordered.size() - modelCalls, prompt, completion),
                System.currentTimeMillis() - started);
    }

    private <T> CompletableFuture<T> node(
            List<NodeTrace> traces,
            String id,
            String name,
            java.util.function.Function<Consumer<ModelUsage>, T> body) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            List<ModelUsage> usages = new CopyOnWriteArrayList<>();
            try {
                T result = body.apply(usages::add);
                traces.add(trace(id, name, "DONE", usages, result, System.currentTimeMillis() - start, ""));
                return result;
            } catch (RuntimeException exception) {
                traces.add(trace(id, name, "FAILED", usages, null, System.currentTimeMillis() - start,
                        Objects.toString(exception.getMessage(), exception.getClass().getSimpleName())));
                return null;
            }
        }, executor);
    }

    private <T> CompletableFuture<T> skipped(List<NodeTrace> traces, String id, String name, String reason) {
        traces.add(new NodeTrace(id, name, "SKIPPED", "SKIPPED", "", 0, 0, 0, reason));
        return CompletableFuture.completedFuture(null);
    }

    private NodeTrace trace(String id, String name, String status, List<ModelUsage> usages, Object result,
                            long duration, String note) {
        long prompt = usages.stream().mapToLong(ModelUsage::promptTokens).sum();
        long completion = usages.stream().mapToLong(ModelUsage::completionTokens).sum();
        String model = usages.stream().map(ModelUsage::model).filter(m -> !m.isBlank()).findFirst().orElse("");
        String mode;
        if (usages.isEmpty()) {
            mode = "RULE";
        } else if (usages.stream().allMatch(ModelUsage::cached)) {
            mode = "CACHED";
        } else if ("rule".equals(model)) {
            mode = "RULE";
        } else {
            mode = id.equals("vision") ? "VLM" : "LLM";
        }
        if (result instanceof RiskWorkflowResponse risk && risk.analysisMode().startsWith("RULE")) mode = "RULE";
        if (result instanceof VisionResult vision && !"VLM".equals(vision.mode())) mode = "SKIPPED";
        if ("FAILED".equals(status)) mode = "FAILED";
        if (note.isBlank()) note = noteFor(id, mode, result);
        return new NodeTrace(id, name, status, mode, "rule".equals(model) ? "" : model, prompt, completion, duration, note);
    }

    private String noteFor(String id, String mode, Object result) {
        if ("CACHED".equals(mode)) return "输入未变化，复用上次结果";
        if (result instanceof RiskWorkflowResponse risk) {
            String how = switch (risk.analysisMode()) {
                case "RULE" -> "无负面信号，规则判定";
                case "RULE_FALLBACK" -> "模型不可用，按规则降级判定";
                default -> "语义分析";
            };
            return how + "：" + risk.emotion() + (risk.handoffRequired() ? "，建议转人工" : "");
        }
        if (result instanceof VisionResult vision) return vision.issueLabel();
        if (result instanceof Recommendation recommendation) {
            return recommendation.items().isEmpty() ? "偏好不足，建议先追问" : "推荐" + recommendation.items().size() + "款";
        }
        if ("personalization".equals(id)) return "已更新偏好档案，本轮无推荐诉求";
        return "";
    }

    private List<NodeTrace> order(List<NodeTrace> traces) {
        List<String> sequence = List.of("preprocess", "context", "risk", "vision", "personalization", "skills", "reply-draft", "actions");
        return traces.stream().sorted(java.util.Comparator.comparingInt(trace -> sequence.indexOf(trace.id()))).toList();
    }

    private List<ActionSuggestion> actions(
            RunRequest input,
            Set<String> intents,
            RiskWorkflowResponse risk,
            VisionResult vision,
            Recommendation recommendation,
            String sku) {
        List<ActionSuggestion> actions = new ArrayList<>();
        boolean safety = intents.contains(RuleSignals.INTENT_SAFETY)
                || (vision != null && "SKIN_REACTION".equals(vision.issueType()));
        if (safety) {
            actions.add(new ActionSuggestion("SAFETY_ESCALATION", "安全事件·转专人跟进",
                    "用户反馈疑似不良反应，需引导停用并保留产品与照片，转品质专员",
                    Map.of("priority", "URGENT", "sku", sku), true));
        }
        if (vision != null && "WRONG_ITEM".equals(vision.issueType()) && !"YES".equals(vision.matchesOrder())) {
            actions.add(new ActionSuggestion("CREATE_RESEND_WORK_ORDER", "创建错发补发工单",
                    "图片识别为“" + vision.observedProduct() + "”，与订单商品不符",
                    Map.of("orderedSku", sku, "observed", vision.observedProduct(), "priority", "HIGH"), true));
        } else if (vision != null && List.of("DAMAGED", "LEAKAGE", "QUALITY").contains(vision.issueType())) {
            actions.add(new ActionSuggestion("CREATE_AFTER_SALE_WORK_ORDER", "创建售后工单",
                    "图片核验：" + vision.issueLabel(), Map.of("sku", sku, "issueType", vision.issueType()), true));
        }
        if (risk != null && risk.handoffRequired() && !safety) {
            actions.add(new ActionSuggestion("ESCALATE_SUPERVISOR", "升级主管处理",
                    "风险分" + risk.riskScore() + "，" + String.join("、", risk.evidence()),
                    Map.of("riskScore", risk.riskScore()), true));
        }
        actions.addAll(commitmentReminders(input.commitments()));
        issueRadar.alerts().stream()
                .filter(alert -> !sku.isBlank() && alert.sku().equals(sku))
                .findFirst()
                .ifPresent(alert -> actions.add(new ActionSuggestion("QUALITY_CLUSTER_ALERT", "集中异常预警",
                        alert.affectedConversations() + "个会话反馈同一商品" + alert.issueLabel() + "，" + alert.suggestion(),
                        Map.of("sku", alert.sku(), "conversations", alert.conversationIds()), false)));
        if (recommendation != null && !recommendation.items().isEmpty()) {
            actions.add(new ActionSuggestion("SEND_RECOMMENDATION_CARD", "发送个性化推荐卡片",
                    recommendation.agentHint(),
                    Map.of("skus", recommendation.items().stream().map(PersonalizationService.RecommendedItem::sku).toList()), true));
        } else if (recommendation != null && !recommendation.missingInfo().isEmpty()) {
            actions.add(new ActionSuggestion("ASK_PROFILE", "先确认肤质信息",
                    "推荐前需要确认：" + String.join("、", recommendation.missingInfo()), Map.of(), false));
        }
        return actions;
    }

    /** 把需要主管关注的行动同步到预警中心，进入闭环跟踪。 */
    private List<String> raiseAlerts(RunRequest input, List<ActionSuggestion> actions, RiskWorkflowResponse risk) {
        List<String> ids = new ArrayList<>();
        Long conversationId = input.conversationId();
        for (ActionSuggestion action : actions) {
            AlertCenter.Alert alert = switch (action.type()) {
                case "SAFETY_ESCALATION" -> alertCenter.raise(AlertCenter.SAFETY, "CRITICAL",
                        "SAFETY:" + conversationId, conversationId, "疑似不良反应", action.reason(), action.payload());
                case "ESCALATE_SUPERVISOR" -> alertCenter.raise(AlertCenter.EMOTION_ESCALATION, "HIGH",
                        "EMOTION:" + conversationId, conversationId, "情绪升级·建议主管介入", action.reason(),
                        Map.of("riskScore", risk == null ? 0 : risk.riskScore(),
                                "emotion", risk == null ? "" : risk.emotion()));
                case "COMMITMENT_OVERDUE" -> alertCenter.raise(AlertCenter.COMMITMENT_OVERDUE, "HIGH",
                        "COMMITMENT:" + conversationId + ":" + action.payload().get("dueAt"), conversationId,
                        "服务承诺超时", action.reason(), action.payload());
                case "QUALITY_CLUSTER_ALERT" -> alertCenter.raise(AlertCenter.QUALITY_CLUSTER, "HIGH",
                        "CLUSTER:" + action.payload().get("sku"), conversationId, "多会话集中异常", action.reason(), action.payload());
                default -> null;
            };
            if (alert != null) ids.add(alert.id());
        }
        return ids;
    }

    /** 单 Case 承诺跟进：到期前 2 小时提醒，超时标红，客服在用户再次进线前即可知道卡点。 */
    private List<ActionSuggestion> commitmentReminders(List<Map<String, Object>> commitments) {
        List<ActionSuggestion> reminders = new ArrayList<>();
        if (commitments == null) return reminders;
        OffsetDateTime now = OffsetDateTime.now();
        for (Map<String, Object> commitment : commitments) {
            String status = Objects.toString(commitment.getOrDefault("status", ""), "").toUpperCase();
            if (List.of("DONE", "COMPLETED", "CLOSED", "已完成").contains(status)) continue;
            OffsetDateTime due = parseTime(commitment.getOrDefault("dueAt", commitment.get("deadline")));
            if (due == null) continue;
            String description = Objects.toString(commitment.getOrDefault("description", commitment.getOrDefault("title", "服务承诺")), "服务承诺");
            long minutes = ChronoUnit.MINUTES.between(now, due);
            if (minutes < 0) {
                reminders.add(new ActionSuggestion("COMMITMENT_OVERDUE", "承诺已超时",
                        "“" + description + "”已超时" + formatDuration(-minutes) + "，建议立即催办并主动告知用户进度",
                        Map.of("dueAt", due.toString()), false));
            } else if (minutes <= 120) {
                reminders.add(new ActionSuggestion("COMMITMENT_DUE_SOON", "承诺即将到期",
                        "“" + description + "”将在" + formatDuration(minutes) + "后到期，建议核对工单进度",
                        Map.of("dueAt", due.toString()), false));
            }
        }
        return reminders;
    }

    private String formatDuration(long minutes) {
        return minutes >= 60 ? (minutes / 60) + "小时" + (minutes % 60 == 0 ? "" : (minutes % 60) + "分钟") : minutes + "分钟";
    }

    private OffsetDateTime parseTime(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        try {
            return OffsetDateTime.parse(text);
        } catch (Exception ignored) {
            // 继续尝试本地时间格式
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String radarIssueType(String intent) {
        return switch (intent) {
            case RuleSignals.INTENT_WRONG_ITEM -> "WRONG_ITEM";
            case RuleSignals.INTENT_DAMAGED -> "DAMAGED";
            default -> "SKIN_REACTION";
        };
    }

    private String customerId(RunRequest input) {
        if (input.customerId() != null && !input.customerId().isBlank()) return input.customerId();
        if (input.customer() != null && input.customer().get("id") != null) return input.customer().get("id").toString();
        return "conversation-" + input.conversationId();
    }

    private String latestUserMessage(RunRequest input) {
        if (input.latestMessage() != null && !input.latestMessage().isBlank()) return input.latestMessage();
        if (input.messages() == null) return "";
        for (int i = input.messages().size() - 1; i >= 0; i--) {
            Map<String, Object> message = input.messages().get(i);
            if ("user".equalsIgnoreCase(ContextCompressor.role(message))) return ContextCompressor.text(message);
        }
        return "";
    }

    private List<String> recentUserTexts(List<Map<String, Object>> messages, String latest) {
        List<String> texts = new ArrayList<>();
        for (Map<String, Object> message : lastUserTurn(messages)) {
            if ("user".equalsIgnoreCase(ContextCompressor.role(message))) texts.add(ContextCompressor.text(message));
        }
        if (latest != null && !latest.isBlank() && !texts.contains(latest)) texts.add(latest);
        return texts;
    }

    /** 最近一轮用户发言（最后一条客服消息之后的用户消息；若不足则取最近 3 条用户消息）。 */
    private List<Map<String, Object>> lastUserTurn(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<Map<String, Object>> turn = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            if (ContextCompressor.isPlatformNotice(message)) continue;
            boolean user = "user".equalsIgnoreCase(ContextCompressor.role(message));
            if (!user && !turn.isEmpty() && turn.size() >= 3) break;
            if (user) turn.add(0, message);
            if (turn.size() >= 6) break;
        }
        return turn;
    }

    public record RunRequest(
            Long conversationId,
            String customerId,
            String latestMessage,
            String previousSummary,
            List<Map<String, Object>> messages,
            Map<String, Object> order,
            Map<String, Object> customer,
            List<Map<String, Object>> workOrders,
            List<Map<String, Object>> commitments,
            List<String> imageUrls,
            Boolean includeContext,
            Boolean includeDraft,
            Boolean forceRecommendation) {
    }

    public record NodeTrace(
            String id,
            String name,
            String status,
            String mode,
            String model,
            long promptTokens,
            long completionTokens,
            long durationMs,
            String note) {
    }

    public record ActionSuggestion(
            String type,
            String title,
            String reason,
            Map<String, Object> payload,
            boolean confirmationRequired) {
    }

    public record SkillRef(String id, String name) {
    }

    public record RunCost(long modelCalls, long ruleOrSkippedNodes, long promptTokens, long completionTokens) {
    }

    public record RunResult(
            Long conversationId,
            String customerId,
            List<String> intents,
            ContextWorkflowResponse context,
            RiskWorkflowResponse risk,
            VisionResult vision,
            Recommendation recommendation,
            ReplyDraftResponse draft,
            List<SkillRef> skills,
            List<ActionSuggestion> actions,
            List<String> alertIds,
            List<NodeTrace> nodes,
            RunCost cost,
            long durationMs) {
    }
}
