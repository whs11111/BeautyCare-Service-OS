package com.beautycare.ai;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 按工作流节点统计模型调用、缓存命中、规则短路与 Token 消耗，供成本看板和评估使用。
 * savedTokens 为估算值：缓存命中按原调用用量计，规则短路和上下文压缩按字符数估算。
 */
@Component
public class TokenMeter {

    private final Map<String, NodeCounter> nodes = new ConcurrentHashMap<>();
    private final OffsetDateTime startedAt = OffsetDateTime.now();

    public void recordCall(String node, String model, long promptTokens, long completionTokens, long cachedPromptTokens) {
        NodeCounter counter = counter(node);
        counter.calls.incrementAndGet();
        counter.promptTokens.addAndGet(promptTokens);
        counter.completionTokens.addAndGet(completionTokens);
        counter.providerCachedTokens.addAndGet(cachedPromptTokens);
        counter.lastModel = model;
    }

    public void recordCacheHit(String node, long savedTokens) {
        NodeCounter counter = counter(node);
        counter.cacheHits.incrementAndGet();
        counter.savedTokens.addAndGet(savedTokens);
    }

    /** 规则层已能给出结论，跳过模型调用。 */
    public void recordSkip(String node, long estimatedSavedTokens) {
        NodeCounter counter = counter(node);
        counter.skips.incrementAndGet();
        counter.savedTokens.addAndGet(estimatedSavedTokens);
    }

    /** 上下文压缩、图片降采样等在调用前削减的输入量。 */
    public void recordTrim(String node, long estimatedSavedTokens) {
        if (estimatedSavedTokens <= 0) return;
        counter(node).savedTokens.addAndGet(estimatedSavedTokens);
    }

    public Snapshot snapshot() {
        List<NodeSnapshot> list = nodes.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .sorted(Comparator.comparing(NodeSnapshot::node))
                .toList();
        long calls = list.stream().mapToLong(NodeSnapshot::calls).sum();
        long hits = list.stream().mapToLong(NodeSnapshot::cacheHits).sum();
        long skips = list.stream().mapToLong(NodeSnapshot::skips).sum();
        long prompt = list.stream().mapToLong(NodeSnapshot::promptTokens).sum();
        long providerCached = list.stream().mapToLong(NodeSnapshot::providerCachedPromptTokens).sum();
        long completion = list.stream().mapToLong(NodeSnapshot::completionTokens).sum();
        long saved = list.stream().mapToLong(NodeSnapshot::savedTokens).sum();
        long requests = calls + hits + skips;
        double avoidedRate = requests == 0 ? 0 : (double) (hits + skips) / requests;
        double savingRate = prompt + completion + saved == 0 ? 0 : (double) saved / (prompt + completion + saved);
        return new Snapshot(startedAt, calls, hits, skips, prompt, providerCached, completion, saved,
                round(avoidedRate), round(savingRate), list);
    }

    public void reset() {
        nodes.clear();
    }

    /** 中文约 1.5 字符 / Token 的粗略估算，仅用于“节省量”展示。 */
    public static long estimateTokens(String text) {
        return text == null ? 0 : (long) Math.ceil(text.length() / 1.5);
    }

    private NodeCounter counter(String node) {
        return nodes.computeIfAbsent(node == null ? "default" : node, key -> new NodeCounter());
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static final class NodeCounter {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong cacheHits = new AtomicLong();
        final AtomicLong skips = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
        final AtomicLong savedTokens = new AtomicLong();
        final AtomicLong providerCachedTokens = new AtomicLong();
        volatile String lastModel = "";

        NodeSnapshot snapshot(String node) {
            return new NodeSnapshot(node, lastModel, calls.get(), cacheHits.get(), skips.get(),
                    promptTokens.get(), providerCachedTokens.get(), completionTokens.get(), savedTokens.get());
        }
    }

    public record NodeSnapshot(
            String node,
            String lastModel,
            long calls,
            long cacheHits,
            long skips,
            long promptTokens,
            long providerCachedPromptTokens,
            long completionTokens,
            long savedTokens) {
    }

    public record Snapshot(
            OffsetDateTime since,
            long modelCalls,
            long cacheHits,
            long ruleSkips,
            long promptTokens,
            long providerCachedPromptTokens,
            long completionTokens,
            long estimatedSavedTokens,
            double avoidedCallRate,
            double estimatedSavingRate,
            List<NodeSnapshot> nodes) {
    }
}
