package com.beautycare.api;

import java.util.List;
import java.util.Map;

import com.beautycare.ai.ModelRouter;
import com.beautycare.ai.TokenMeter;
import com.beautycare.personalization.CustomerProfileStore;
import com.beautycare.personalization.CustomerProfileStore.Fact;
import com.beautycare.personalization.CustomerProfileStore.Profile;
import com.beautycare.personalization.PersonalizationService;
import com.beautycare.personalization.PersonalizationService.Recommendation;
import com.beautycare.workflow.AssistanceOrchestrator;
import com.beautycare.workflow.AssistanceOrchestrator.RunRequest;
import com.beautycare.workflow.AssistanceOrchestrator.RunResult;
import com.beautycare.workflow.IssueRadar;
import com.beautycare.workflow.IssueRadar.ClusterAlert;
import com.beautycare.workflow.IssueRadar.IssueReport;
import com.beautycare.workflow.VisionInspectionService;
import com.beautycare.workflow.VisionInspectionService.VisionResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final AssistanceOrchestrator orchestrator;
    private final VisionInspectionService visionService;
    private final PersonalizationService personalizationService;
    private final CustomerProfileStore profileStore;
    private final IssueRadar issueRadar;
    private final TokenMeter tokenMeter;
    private final ModelRouter modelRouter;

    public WorkflowController(
            AssistanceOrchestrator orchestrator,
            VisionInspectionService visionService,
            PersonalizationService personalizationService,
            CustomerProfileStore profileStore,
            IssueRadar issueRadar,
            TokenMeter tokenMeter,
            ModelRouter modelRouter) {
        this.orchestrator = orchestrator;
        this.visionService = visionService;
        this.personalizationService = personalizationService;
        this.profileStore = profileStore;
        this.issueRadar = issueRadar;
        this.tokenMeter = tokenMeter;
        this.modelRouter = modelRouter;
    }

    // 当前模型配置（文本/视觉服务商、各档位模型、是否已配置 Key），供前端展示与排障。
    @GetMapping("/workflows/config")
    public Map<String, Object> config() {
        return modelRouter.describe();
    }

    // 一次调用跑完整条辅助链路，返回各节点结果与执行轨迹。
    @PostMapping("/workflows/assistance/run")
    public RunResult run(@RequestBody RunRequest request) {
        return orchestrator.run(request);
    }

    // 多模态：单独核验图片附件。
    @PostMapping("/workflows/assistance/vision")
    public VisionResult inspect(@RequestBody VisionRequest request) {
        List<String> urls = request.imageUrls() != null && !request.imageUrls().isEmpty()
                ? request.imageUrls()
                : visionService.findImageUrls(request.messages());
        return visionService.inspect(request.conversationId(), urls, request.order(), request.latestMessage(), null);
    }

    // 个性化推荐：增量更新偏好档案后给出推荐与被排除的热门款。
    @PostMapping("/workflows/assistance/recommend")
    public Recommendation recommend(@RequestBody RecommendRequest request) {
        personalizationService.updateProfile(request.customerId(), request.messages(), request.workOrders(), null);
        return personalizationService.recommend(request.customerId(), request.latestMessage());
    }

    @GetMapping("/personalization/profiles/{customerId}")
    public Profile profile(@PathVariable String customerId) {
        return profileStore.get(customerId);
    }

    // 客服确认或纠正一条偏好：CONFIRMED / REJECTED / STATED。
    @PostMapping("/personalization/profiles/{customerId}/facts/{factId}")
    public Fact updateFact(
            @PathVariable String customerId,
            @PathVariable String factId,
            @RequestBody Map<String, String> body) {
        String status = body.getOrDefault("status", "");
        if (!List.of("CONFIRMED", "REJECTED", "STATED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只能是 CONFIRMED、REJECTED 或 STATED");
        }
        return profileStore.setStatus(customerId, factId, status)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "偏好记录不存在"));
    }

    // 风险看板：多 Case 共性异常。
    @GetMapping("/workflows/alerts/clusters")
    public List<ClusterAlert> clusters() {
        return issueRadar.alerts();
    }

    @GetMapping("/workflows/alerts/reports")
    public List<IssueReport> reports() {
        return issueRadar.reports();
    }

    // 演示注入：模拟其他会话的同类反馈。
    @PostMapping("/workflows/alerts/reports")
    public List<ClusterAlert> addReport(@RequestBody IssueReportRequest request) {
        issueRadar.report(request.sku(), request.productName(), request.issueType(), request.conversationId(), "人工录入");
        return issueRadar.alerts();
    }

    // 成本看板：模型调用、缓存命中、规则短路与 Token 节省。
    @GetMapping("/workflows/metrics/tokens")
    public TokenMeter.Snapshot tokens() {
        return tokenMeter.snapshot();
    }

    @PostMapping("/workflows/metrics/tokens/reset")
    public TokenMeter.Snapshot resetTokens() {
        tokenMeter.reset();
        return tokenMeter.snapshot();
    }

    public record VisionRequest(
            Long conversationId,
            List<String> imageUrls,
            List<Map<String, Object>> messages,
            Map<String, Object> order,
            String latestMessage) {
    }

    public record RecommendRequest(
            String customerId,
            String latestMessage,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> workOrders) {
    }

    public record IssueReportRequest(
            String sku,
            String productName,
            String issueType,
            Long conversationId) {
    }
}
