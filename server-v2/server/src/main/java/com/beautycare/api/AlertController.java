package com.beautycare.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.beautycare.workflow.AlertCenter;
import com.beautycare.workflow.AlertCenter.AlertView;
import com.beautycare.workflow.RuleSignals;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 风险异常预警看板接口：预警列表、闭环状态流转、统计概览，以及外部舆情接入。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertCenter alertCenter;

    public AlertController(AlertCenter alertCenter) {
        this.alertCenter = alertCenter;
    }

    @GetMapping
    public List<AlertView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return alertCenter.list(status, type);
    }

    @GetMapping("/summary")
    public AlertCenter.Summary summary() {
        return alertCenter.summary();
    }

    // 状态流转：ACKNOWLEDGED（认领）/ IN_PROGRESS（处理中）/ RESOLVED（闭环，需填写处理结果）。
    @PostMapping("/{id}/transition")
    public AlertCenter.Alert transition(@PathVariable String id, @RequestBody TransitionRequest request) {
        try {
            return alertCenter.transition(id, request.status(), request.operator(), request.note());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    /**
     * 舆情接入：由舆情采集方（爬虫、平台开放接口或人工录入）推送社交平台内容。
     * 规则层零 Token 判断负面程度与风险类型：安全事件为 CRITICAL；强负面且传播量大为 HIGH；一般负面为 MEDIUM；其余只返回分析不生成预警。
     */
    @PostMapping("/public-opinion")
    public Map<String, Object> publicOpinion(@RequestBody OpinionRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少舆情内容");
        }
        int emotion = RuleSignals.emotionScore(request.text());
        List<String> intents = RuleSignals.intents(request.text());
        int reach = request.likes() == null ? 0 : request.likes();
        String level;
        if (intents.contains(RuleSignals.INTENT_SAFETY)) level = "CRITICAL";
        else if (emotion >= 60 && reach >= 100) level = "HIGH";
        else if (emotion >= 42 || intents.contains(RuleSignals.INTENT_COMPLAINT)) level = "MEDIUM";
        else level = "";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("emotionScore", emotion);
        result.put("emotion", RuleSignals.emotionLevel(emotion));
        result.put("intents", intents);
        result.put("level", level.isBlank() ? "NONE" : level);
        if (!level.isBlank()) {
            String key = "OPINION:" + (request.url() != null && !request.url().isBlank()
                    ? request.url()
                    : UUID.nameUUIDFromBytes(request.text().getBytes(StandardCharsets.UTF_8)).toString());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("platform", nullToEmpty(request.platform()));
            payload.put("url", nullToEmpty(request.url()));
            payload.put("sku", nullToEmpty(request.sku()));
            payload.put("likes", reach);
            List<String> labels = new ArrayList<>(intents);
            String title = nullToEmpty(request.platform()) + "负面舆情" + (labels.isEmpty() ? "" : "·" + String.join("、", labels));
            String detail = request.text().length() > 120 ? request.text().substring(0, 120) + "…" : request.text();
            result.put("alert", alertCenter.raise(AlertCenter.PUBLIC_OPINION, level, key, null, title, detail, payload));
        }
        return result;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record TransitionRequest(String status, String operator, String note) {
    }

    public record OpinionRequest(String platform, String text, String url, String sku, Integer likes) {
    }
}
