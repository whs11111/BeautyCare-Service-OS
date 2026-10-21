package com.beautycare.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 风险预警中心：统一管理各类服务异常的预警与闭环状态。
 * 来源：情绪升级/强制转人工、安全事件、承诺超时、多 Case 集中异常、外部舆情。
 * 状态流转：OPEN（待处理）→ ACKNOWLEDGED（已认领）→ IN_PROGRESS（处理中）→ RESOLVED（已闭环），
 * 每次流转记录处理人、时间和备注；按级别设置 SLA，超时未闭环的预警在看板中标红。
 * 同一来源键（类型 + 会话 / SKU）在未闭环期间只保留一条预警，重复触发只累加次数，避免刷屏。
 */
@Component
public class AlertCenter {

    public static final String EMOTION_ESCALATION = "EMOTION_ESCALATION";
    public static final String SAFETY = "SAFETY";
    public static final String COMMITMENT_OVERDUE = "COMMITMENT_OVERDUE";
    public static final String QUALITY_CLUSTER = "QUALITY_CLUSTER";
    public static final String PUBLIC_OPINION = "PUBLIC_OPINION";

    private static final List<String> STATUSES = List.of("OPEN", "ACKNOWLEDGED", "IN_PROGRESS", "RESOLVED");

    private final ObjectMapper mapper;
    private final Path file = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "alerts.json");
    private final Map<String, Alert> alerts = new LinkedHashMap<>();

    public AlertCenter(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        if (Files.exists(file)) {
            alerts.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, Alert>>() {}));
        }
    }

    /** 触发预警：未闭环的同源预警只更新次数与详情，已闭环后再次触发会重新开启新预警。 */
    public synchronized Alert raise(String type, String level, String sourceKey, Long conversationId,
                                    String title, String detail, Map<String, Object> payload) {
        Optional<Alert> open = alerts.values().stream()
                .filter(alert -> alert.sourceKey().equals(sourceKey) && !"RESOLVED".equals(alert.status()))
                .findFirst();
        OffsetDateTime now = OffsetDateTime.now();
        Alert result;
        if (open.isPresent()) {
            Alert current = open.get();
            String mergedLevel = rank(level) > rank(current.level()) ? level : current.level();
            result = new Alert(current.id(), type, mergedLevel, sourceKey, conversationId, title, detail,
                    payload == null ? Map.of() : payload, current.status(), current.owner(), current.occurrences() + 1,
                    current.createdAt(), now, slaDue(current.createdAt(), mergedLevel), current.resolvedAt(), current.history());
        } else {
            List<AlertEvent> history = new ArrayList<>();
            history.add(new AlertEvent(now, "OPEN", "system", "系统自动触发：" + title));
            result = new Alert(UUID.randomUUID().toString().substring(0, 8), type, level, sourceKey, conversationId,
                    title, detail, payload == null ? Map.of() : payload, "OPEN", "", 1, now, now,
                    slaDue(now, level), null, history);
        }
        alerts.put(result.id(), result);
        save();
        return result;
    }

    public synchronized Alert transition(String id, String status, String operator, String note) {
        Alert current = alerts.get(id);
        if (current == null) throw new IllegalArgumentException("预警不存在");
        String target = Objects.toString(status, "").toUpperCase();
        if (!STATUSES.contains(target)) throw new IllegalArgumentException("无效状态：" + status);
        if ("RESOLVED".equals(current.status())) throw new IllegalStateException("预警已闭环");
        if ("RESOLVED".equals(target) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("闭环时必须填写处理结果");
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<AlertEvent> history = new ArrayList<>(current.history());
        history.add(new AlertEvent(now, target, Objects.toString(operator, ""), Objects.toString(note, "")));
        String owner = current.owner().isBlank() && operator != null ? operator : current.owner();
        Alert updated = new Alert(current.id(), current.type(), current.level(), current.sourceKey(), current.conversationId(),
                current.title(), current.detail(), current.payload(), target, owner, current.occurrences(),
                current.createdAt(), now, current.slaDueAt(), "RESOLVED".equals(target) ? now : null, history);
        alerts.put(id, updated);
        save();
        return updated;
    }

    public synchronized List<AlertView> list(String status, String type) {
        OffsetDateTime now = OffsetDateTime.now();
        return alerts.values().stream()
                .filter(alert -> status == null || status.isBlank() || alert.status().equalsIgnoreCase(status))
                .filter(alert -> type == null || type.isBlank() || alert.type().equalsIgnoreCase(type))
                .sorted(Comparator.comparing((Alert alert) -> "RESOLVED".equals(alert.status()))
                        .thenComparing(alert -> -rank(alert.level()))
                        .thenComparing(Alert::updatedAt, Comparator.reverseOrder()))
                .map(alert -> new AlertView(alert, !"RESOLVED".equals(alert.status()) && now.isAfter(alert.slaDueAt())))
                .toList();
    }

    public synchronized Summary summary() {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        STATUSES.forEach(status -> byStatus.put(status, 0L));
        Map<String, Long> openByType = new LinkedHashMap<>();
        long overdue = 0;
        List<Long> resolutionMinutes = new ArrayList<>();
        for (Alert alert : alerts.values()) {
            byStatus.merge(alert.status(), 1L, Long::sum);
            if (!"RESOLVED".equals(alert.status())) {
                openByType.merge(alert.type(), 1L, Long::sum);
                if (now.isAfter(alert.slaDueAt())) overdue++;
            } else if (alert.resolvedAt() != null) {
                resolutionMinutes.add(Duration.between(alert.createdAt(), alert.resolvedAt()).toMinutes());
            }
        }
        double averageResolution = resolutionMinutes.stream().mapToLong(Long::longValue).average().orElse(0);
        long resolved = byStatus.getOrDefault("RESOLVED", 0L);
        long total = alerts.size();
        return new Summary(total, byStatus, openByType, overdue,
                total == 0 ? 0 : Math.round(resolved * 1000.0 / total) / 1000.0,
                Math.round(averageResolution * 10) / 10.0);
    }

    private OffsetDateTime slaDue(OffsetDateTime from, String level) {
        return switch (level) {
            case "CRITICAL" -> from.plusMinutes(30);
            case "HIGH" -> from.plusHours(2);
            case "MEDIUM" -> from.plusHours(8);
            default -> from.plusHours(24);
        };
    }

    private int rank(String level) {
        return switch (Objects.toString(level, "")) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "MEDIUM" -> 1;
            default -> 0;
        };
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("alerts.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), alerts);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 演示环境写盘失败不影响预警展示
        }
    }

    public record AlertEvent(OffsetDateTime at, String status, String operator, String note) {
    }

    public record Alert(
            String id,
            String type,
            String level,
            String sourceKey,
            Long conversationId,
            String title,
            String detail,
            Map<String, Object> payload,
            String status,
            String owner,
            int occurrences,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime slaDueAt,
            OffsetDateTime resolvedAt,
            List<AlertEvent> history) {
    }

    public record AlertView(Alert alert, boolean slaBreached) {
    }

    public record Summary(
            long total,
            Map<String, Long> byStatus,
            Map<String, Long> openByType,
            long slaBreached,
            double closureRate,
            double averageResolutionMinutes) {
    }
}
