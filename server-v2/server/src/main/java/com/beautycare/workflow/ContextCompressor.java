package com.beautycare.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.beautycare.api.AgentController.AssistanceContextRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 发送给模型前的上下文压缩：
 * 1. 去掉自动问候、接待/转接通知等平台消息；
 * 2. 去掉头像、坐席编号等与分析无关的字段和空值；
 * 3. 按节点保留最近 N 条消息，更早的内容由 previousSummary（滚动摘要）承接；
 * 4. 截断超长单条消息。
 */
@Component
public class ContextCompressor {

    private static final Pattern PLATFORM_NOTICE = Pattern.compile(
            "^(我是客服\\d+，很高兴为您服务|.*已(领取|接入|转接)(会话|您的咨询)|.*为您转接).*");
    private static final Set<String> DROPPED_KEYS = Set.of(
            "agentAvatar", "avatar", "agentName", "agentId", "sessionId", "automatic",
            "read", "status", "localId", "pending", "uploading");
    private static final int MAX_TEXT = 300;

    private final ObjectMapper objectMapper;

    public ContextCompressor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public enum Profile {
        /** 前情分析：需要较完整轨迹。 */
        CONTEXT(16, false),
        /** 已处理事件：保留所有有效消息及完整文字，避免遗漏早期闭环。 */
        RESOLVED_EVENTS(Integer.MAX_VALUE, false),
        /** 情绪风险：只看用户消息。 */
        RISK(8, true),
        /** 回复草稿：最近若干轮即可。 */
        REPLY(10, false);

        final int maxMessages;
        final boolean userOnly;

        Profile(int maxMessages, boolean userOnly) {
            this.maxMessages = maxMessages;
            this.userOnly = userOnly;
        }
    }

    public Result compress(AssistanceContextRequest request, Profile profile) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.messages() != null) {
            for (Map<String, Object> message : request.messages()) {
                if (isPlatformNotice(message)) continue;
                if (profile.userOnly && !"user".equalsIgnoreCase(role(message))) continue;
                messages.add(compactMessage(message, profile != Profile.RESOLVED_EVENTS));
            }
        }
        if (messages.size() > profile.maxMessages) {
            messages = new ArrayList<>(messages.subList(messages.size() - profile.maxMessages, messages.size()));
        }

        AssistanceContextRequest compressed = new AssistanceContextRequest(
                request.conversationId(),
                profile == Profile.RESOLVED_EVENTS ? request.latestMessage() : truncate(request.latestMessage()),
                profile == Profile.RISK ? null : request.previousSummary(),
                messages,
                profile == Profile.RISK ? null : prune(request.order()),
                profile == Profile.RISK ? null : prune(request.customer()),
                profile == Profile.RISK ? null : pruneList(request.workOrders()),
                pruneList(request.commitments()));

        long before = length(request);
        long after = length(compressed);
        return new Result(compressed, Math.max(0, before - after));
    }

    public static String role(Map<String, Object> message) {
        Object role = message.get("role");
        if (role == null) role = message.get("from");
        return Objects.toString(role, "");
    }

    public static String text(Map<String, Object> message) {
        return Objects.toString(message.getOrDefault("text", ""), "");
    }

    public static boolean isPlatformNotice(Map<String, Object> message) {
        if (Boolean.TRUE.equals(message.get("automatic")) && !"bot-reply".equals(message.get("kind"))) return true;
        String role = role(message);
        if ("system".equalsIgnoreCase(role)) return true;
        return PLATFORM_NOTICE.matcher(text(message)).matches();
    }

    private Map<String, Object> compactMessage(Map<String, Object> message, boolean truncateText) {
        Map<String, Object> result = new LinkedHashMap<>();
        message.forEach((key, value) -> {
            if (DROPPED_KEYS.contains(key) || isEmpty(value)) return;
            if (truncateText && "text".equals(key)) value = truncate(Objects.toString(value));
            result.put(key, value instanceof Map<?, ?> || value instanceof Collection<?> ? pruneValue(value) : value);
        });
        if (!result.containsKey("role") && result.containsKey("from")) result.put("role", result.remove("from"));
        return result;
    }

    private Map<String, Object> prune(Map<String, Object> map) {
        if (map == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (DROPPED_KEYS.contains(key) || isEmpty(value)) return;
            result.put(key, pruneValue(value));
        });
        return result.isEmpty() ? null : result;
    }

    private List<Map<String, Object>> pruneList(List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : list) {
            Map<String, Object> pruned = prune(item);
            if (pruned != null) result.add(pruned);
        }
        return result.isEmpty() ? null : result;
    }

    @SuppressWarnings("unchecked")
    private Object pruneValue(Object value) {
        if (value instanceof Map<?, ?> map) return prune((Map<String, Object>) map);
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            for (Object item : collection) {
                if (!isEmpty(item)) result.add(pruneValue(item));
            }
            return result;
        }
        return value;
    }

    private boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String text) return text.isBlank();
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return false;
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
    }

    private long length(Object value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (Exception exception) {
            return 0;
        }
    }

    public record Result(AssistanceContextRequest request, long savedChars) {
    }
}
