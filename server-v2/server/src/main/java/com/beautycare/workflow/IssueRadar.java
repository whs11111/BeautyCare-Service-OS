package com.beautycare.workflow;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 多 Case 共性异常雷达：短时间内多个会话针对同一商品报告同类问题（错发色号、破损、过敏等）时，
 * 关联为一条集中预警，提示可能存在批次或仓库问题。纯规则聚合，不消耗 Token。
 */
@Component
public class IssueRadar {

    private final List<IssueReport> reports = new CopyOnWriteArrayList<>();
    private final Duration window;
    private final int threshold;

    public IssueRadar(
            @Value("${radar.window-hours:24}") long windowHours,
            @Value("${radar.threshold:3}") int threshold) {
        this.window = Duration.ofHours(windowHours);
        this.threshold = threshold;
    }

    public void report(String sku, String productName, String issueType, Long conversationId, String source) {
        if (sku == null || sku.isBlank() || conversationId == null) return;
        boolean duplicate = reports.stream().anyMatch(item -> item.sku().equals(sku)
                && item.issueType().equals(issueType)
                && item.conversationId().equals(conversationId));
        if (duplicate) return;
        reports.add(new IssueReport(sku, Objects.toString(productName, ""), issueType, conversationId, source, OffsetDateTime.now()));
    }

    public List<ClusterAlert> alerts() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(window);
        reports.removeIf(item -> item.reportedAt().isBefore(cutoff));

        Map<String, List<IssueReport>> groups = new LinkedHashMap<>();
        for (IssueReport report : reports) {
            groups.computeIfAbsent(report.sku() + "|" + report.issueType(), key -> new ArrayList<>()).add(report);
        }

        List<ClusterAlert> alerts = new ArrayList<>();
        groups.values().forEach(group -> {
            Set<Long> conversations = new LinkedHashSet<>();
            group.forEach(item -> conversations.add(item.conversationId()));
            if (conversations.size() < threshold) return;
            IssueReport first = group.get(0);
            OffsetDateTime last = group.stream().map(IssueReport::reportedAt).max(Comparator.naturalOrder()).orElse(first.reportedAt());
            alerts.add(new ClusterAlert(
                    first.sku(),
                    first.productName(),
                    first.issueType(),
                    VisionInspectionService.label(first.issueType()),
                    conversations.size(),
                    List.copyOf(conversations),
                    first.reportedAt(),
                    last,
                    "SKIN_REACTION".equals(first.issueType()) || conversations.size() >= threshold * 2 ? "HIGH" : "MEDIUM",
                    suggestion(first.issueType())));
        });
        alerts.sort(Comparator.comparing(ClusterAlert::affectedConversations).reversed());
        return alerts;
    }

    public List<IssueReport> reports() {
        return List.copyOf(reports);
    }

    public void clear() {
        reports.clear();
    }

    private String suggestion(String issueType) {
        return switch (issueType) {
            case "WRONG_ITEM" -> "多位用户反馈同一商品错发，建议核查仓库拣货与该 SKU 批次，暂停相关库位出库";
            case "DAMAGED", "LEAKAGE" -> "同一商品集中破损/漏液，建议核查包材与物流线路";
            case "SKIN_REACTION" -> "同一商品集中出现皮肤不适反馈，建议立即上报品质与法务团队并评估下架";
            case "QUALITY" -> "同一商品集中反馈品质问题，建议抽检该批次";
            default -> "建议人工复核";
        };
    }

    @SuppressWarnings("unchecked")
    public static String skuOf(Map<String, Object> order) {
        if (order == null) return "";
        for (String key : List.of("sku", "skuId", "skuCode", "productSku", "orderedSku", "productId")) {
            Object value = order.get(key);
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        Object items = order.get("items");
        if (items instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return skuOf((Map<String, Object>) first);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    public static String productNameOf(Map<String, Object> order) {
        if (order == null) return "";
        for (String key : List.of("productName", "product", "name", "title", "goodsName")) {
            Object value = order.get(key);
            if (value instanceof String text && !text.isBlank()) return text;
        }
        Object items = order.get("items");
        if (items instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return productNameOf((Map<String, Object>) first);
        }
        return "";
    }

    public record IssueReport(
            String sku,
            String productName,
            String issueType,
            Long conversationId,
            String source,
            OffsetDateTime reportedAt) {
    }

    public record ClusterAlert(
            String sku,
            String productName,
            String issueType,
            String issueLabel,
            int affectedConversations,
            List<Long> conversationIds,
            OffsetDateTime firstReportedAt,
            OffsetDateTime lastReportedAt,
            String level,
            String suggestion) {
    }
}
