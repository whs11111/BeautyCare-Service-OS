package com.beautycare.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.beautycare.workflow.AssistanceWorkflowAiService;
import com.beautycare.skills.SkillRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final AssistanceWorkflowAiService assistanceWorkflowAiService;
    private final SkillRegistry skillRegistry;

    public AgentController(AssistanceWorkflowAiService assistanceWorkflowAiService, SkillRegistry skillRegistry) {
        this.assistanceWorkflowAiService = assistanceWorkflowAiService;
        this.skillRegistry = skillRegistry;
    }

    // Agent 1：客服 Agent，负责当前会话的完整处理回合。
    @PostMapping("/agents/customer-service/turn")
    public CustomerServiceTurnResponse runCustomerServiceTurn(
            @RequestBody CustomerServiceTurnRequest request) {
        return new CustomerServiceTurnResponse(
                request.conversationId(),
                "ACTION_PENDING_CONFIRMATION",
                "RESEND_WRONG_SKU",
                "已核实订单商品与实收商品不一致，建议创建优先补发工单。",
                "很抱歉让您多次为同一问题联系我们。我已核实错发情况，并为您准备了优先补发申请。",
                List.of(
                        new ToolCall("order.query", "SUCCEEDED", "已读取订单商品 #01"),
                        new ToolCall("vision.sku-match", "SUCCEEDED", "图片识别为商品 #05"),
                        new ToolCall("policy.search", "SUCCEEDED", "符合 CS-04 错发补发政策")),
                new ActionProposal(
                        "CREATE_RESEND_WORK_ORDER",
                        true,
                        Map.of("orderedSku", "01", "receivedSku", "05", "priority", "URGENT")),
                false,
                null,
                null);
    }

    // 客服 Agent 主动转人工，携带上下文与处理摘要。
    @PostMapping("/agents/customer-service/handoff")
    public HandoffResponse handoffToHuman(@RequestBody HandoffRequest request) {
        return new HandoffResponse(
                request.conversationId(),
                true,
                request.trigger(),
                request.level(),
                "HUMAN_L3",
                request.summary(),
                OffsetDateTime.now());
    }

    // Agent 2：创建长程跟进任务。
    @PostMapping("/agents/follow-up/tasks")
    public FollowUpTaskResponse createFollowUpTask(
            @RequestBody FollowUpTaskRequest request) {
        return new FollowUpTaskResponse(
                "FOLLOW-UP-88271-01",
                request.conversationId(),
                request.taskType(),
                request.goal(),
                "IN_PROGRESS",
                request.dueAt(),
                request.dueAt().minusMinutes(30),
                false,
                List.of("监听业务状态变化", "到期前30分钟检查", "超时后提醒人工并安排回访"));
    }

    // 查询长程任务当前进度，供客服或用户主动询问时调用。
    @GetMapping("/agents/follow-up/tasks/{taskId}")
    public FollowUpTaskResponse getFollowUpTask(@PathVariable String taskId) {
        return new FollowUpTaskResponse(
                taskId,
                1L,
                "RESEND",
                "完成错发商品补发并通知用户",
                "IN_PROGRESS",
                OffsetDateTime.now().plusHours(2),
                OffsetDateTime.now().plusMinutes(30),
                false,
                List.of("补发工单已创建", "等待仓库出库", "出库后发送物流通知"));
    }

    // 业务状态变化、定时轮询或人工操作通过事件推进长程任务。
    @PostMapping("/agents/follow-up/tasks/{taskId}/events")
    public FollowUpTaskResponse pushFollowUpEvent(
            @PathVariable String taskId,
            @RequestBody FollowUpEventRequest request) {
        boolean completed = "COMPLETED".equalsIgnoreCase(request.eventType());
        return new FollowUpTaskResponse(
                taskId,
                request.conversationId(),
                request.taskType(),
                request.description(),
                completed ? "CLOSED" : "IN_PROGRESS",
                request.occurredAt().plusHours(2),
                completed ? null : request.occurredAt().plusMinutes(30),
                false,
                List.of("事件已写入任务轨迹", completed ? "任务已满足关闭条件" : "继续监听后续状态"));
    }

    // 辅助工作流 ②：聚合用户轨迹、画像和前情摘要。
    @PostMapping("/workflows/assistance/context")
    public ContextWorkflowResponse buildContext(@RequestBody AssistanceContextRequest request,
            @RequestParam(defaultValue = "false") boolean fullHistory) {
        return fullHistory ? assistanceWorkflowAiService.analyzeResolvedEvents(request)
                : assistanceWorkflowAiService.analyzeContext(request);
    }

    // 辅助工作流 ③：情绪、风险、强制转人工与会话排序依据。
    @PostMapping("/workflows/assistance/risk")
    public RiskWorkflowResponse analyzeRisk(@RequestBody AssistanceContextRequest request) {
        return assistanceWorkflowAiService.analyzeRisk(request);
    }

    @GetMapping("/workflows/assistance/conversations/ranked")
    public List<RankedConversation> rankConversations(
            @RequestParam(defaultValue = "all") String status) {
        return List.of(
                new RankedConversation(1L, "陈女士", 920, "critical", true, 82),
                new RankedConversation(2L, "周女士", 880, "critical", true, 74),
                new RankedConversation(5L, "孙女士", 510, "negative", false, 35),
                new RankedConversation(3L, "刘女士", 260, "neutral", false, 23),
                new RankedConversation(4L, "王先生", 180, "positive", false, 18));
    }

    // 辅助工作流 ④：生成客服回复草稿，不直接发送。
    @PostMapping("/workflows/assistance/reply-draft")
    public ReplyDraftResponse generateReplyDraft(@RequestBody AssistanceContextRequest request) {
        return assistanceWorkflowAiService.generateReplyDraft(request, skillRegistry.replyInsights(request), null);
    }

    // 辅助工作流 ⑤：生成需要客服确认的操作卡片。
    @PostMapping("/workflows/assistance/action-card")
    public ActionCardResponse generateActionCard(@RequestBody AssistanceContextRequest request) {
        return new ActionCardResponse(
                request.conversationId(),
                "CREATE_RESEND_WORK_ORDER",
                "优先补发工单已填好",
                List.of("订单与实收 SKU 不一致", "符合 CS-04 政策", "历史承诺已超时18小时"),
                Map.of("orderedSku", "01", "receivedSku", "05", "priority", "URGENT"),
                true,
                "WAITING_FOR_AGENT_CONFIRMATION");
    }

    public record CustomerServiceTurnRequest(
            Long conversationId,
            String latestQuery,
            List<Map<String, Object>> messages,
            Map<String, Object> order,
            Map<String, Object> customer,
            List<Map<String, Object>> openTasks) {
    }

    public record CustomerServiceTurnResponse(
            Long conversationId,
            String outcome,
            String intent,
            String summary,
            String reply,
            List<ToolCall> toolCalls,
            ActionProposal proposedAction,
            boolean handoffRequired,
            String handoffReason,
            FollowUpTaskRequest followUpTask) {
    }

    public record ToolCall(String tool, String status, String summary) {
    }

    public record ActionProposal(
            String actionType,
            boolean confirmationRequired,
            Map<String, Object> payload) {
    }

    public record HandoffRequest(
            Long conversationId,
            String trigger,
            String level,
            String summary,
            List<String> evidence) {
    }

    public record HandoffResponse(
            Long conversationId,
            boolean accepted,
            String trigger,
            String level,
            String queue,
            String transferSummary,
            OffsetDateTime transferredAt) {
    }

    public record FollowUpTaskRequest(
            Long conversationId,
            String taskType,
            String goal,
            OffsetDateTime dueAt,
            Map<String, Object> taskPayload,
            Map<String, Object> notificationPolicy) {
    }

    public record FollowUpEventRequest(
            Long conversationId,
            String taskType,
            String eventType,
            String description,
            OffsetDateTime occurredAt,
            Map<String, Object> payload) {
    }

    public record FollowUpTaskResponse(
            String taskId,
            Long conversationId,
            String taskType,
            String goal,
            String status,
            OffsetDateTime dueAt,
            OffsetDateTime nextCheckAt,
            boolean overdue,
            List<String> progress) {
    }

    public record AssistanceContextRequest(
            Long conversationId,
            String latestMessage,
            String previousSummary,
            List<Map<String, Object>> messages,
            Map<String, Object> order,
            Map<String, Object> customer,
            List<Map<String, Object>> workOrders,
            List<Map<String, Object>> commitments) {
    }

    public record ContextWorkflowResponse(
            Long conversationId,
            String contextSummary,
            List<String> intents,
            List<String> customerTags,
            List<ResolvedEvent> resolvedEvents,
            List<UnresolvedEvent> unresolvedEvents,
            String workflowStatus,
            List<TimelineNode> timeline,
            Boolean immediateHandlingRequired) {
        public ContextWorkflowResponse(Long conversationId, String contextSummary, List<String> intents,
                List<String> customerTags, List<ResolvedEvent> resolvedEvents, List<UnresolvedEvent> unresolvedEvents,
                String workflowStatus, List<TimelineNode> timeline) {
            this(conversationId, contextSummary, intents, customerTags, resolvedEvents, unresolvedEvents, workflowStatus, timeline, null);
        }
    }

    public record ResolvedEvent(
            String userRequest,
            String serviceResolution,
            String completedAt) {
    }

    public record UnresolvedEvent(
            String description,
            String sourceMessageId) {
    }

    public record TimelineNode(
            String type,
            String label,
            String occurredAt,
            boolean risk) {
    }

    public record RiskWorkflowResponse(
            Long conversationId,
            String emotion,
            int emotionScore,
            List<EmotionTrendPoint> emotionTrend,
            int riskScore,
            int queueScore,
            List<String> evidence,
            boolean handoffRequired,
            String handoffMode,
            String handoffSummary,
            OffsetDateTime expiresAt,
            String analysisMode) {
    }

    public record EmotionTrendPoint(String time, int score) {
    }

    public record RankedConversation(
            Long conversationId,
            String customerName,
            int queueScore,
            String emotion,
            boolean handoffRequired,
            int riskScore) {
    }

    public record ReplyDraftResponse(
            Long conversationId,
            String draft,
            String tone,
            List<String> basedOn,
            boolean humanReviewRequired,
            OffsetDateTime expiresAt) {
    }

    public record ActionCardResponse(
            Long conversationId,
            String actionType,
            String title,
            List<String> reasons,
            Map<String, Object> payload,
            boolean confirmationRequired,
            String status) {
    }
}
