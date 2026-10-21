package com.beautycare.personalization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 用户偏好档案：每条偏好都带证据、来源、提及次数和确认状态。
 * 状态：STATED（用户明确说过）、INFERRED（由订单/工单/模型推测）、CONFIRMED（客服或用户确认）、REJECTED（被纠正，不再参与推荐）。
 * 明确表达优先于推测；被纠正的偏好保留记录但不再使用。
 */
@Component
public class CustomerProfileStore {

    private final ObjectMapper mapper;
    private final Path file = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "profiles.json");
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    public CustomerProfileStore(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        if (Files.exists(file)) {
            profiles.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, Profile>>() {}));
        }
    }

    public synchronized Profile get(String customerId) {
        return profiles.computeIfAbsent(customerId,
                id -> new Profile(id, new ArrayList<>(), new ArrayList<>(), OffsetDateTime.now()));
    }

    public synchronized boolean processed(String customerId, String messageId) {
        return messageId != null && get(customerId).processedMessageIds().contains(messageId);
    }

    public synchronized void markProcessed(String customerId, List<String> messageIds) {
        Profile profile = get(customerId);
        LinkedHashSet<String> ids = new LinkedHashSet<>(profile.processedMessageIds());
        ids.addAll(messageIds);
        List<String> bounded = new ArrayList<>(ids);
        if (bounded.size() > 500) bounded = bounded.subList(bounded.size() - 500, bounded.size());
        profile.processedMessageIds().clear();
        profile.processedMessageIds().addAll(bounded);
    }

    /** 合并一条偏好：同维度同取值的事实累加提及次数和证据，不重复建档。 */
    public synchronized Fact upsert(String customerId, String dimension, String value, String status,
                                    String source, String messageId, String snippet) {
        Profile profile = get(customerId);
        Optional<Fact> existing = profile.facts().stream()
                .filter(fact -> fact.dimension().equals(dimension) && fact.value().equals(value))
                .findFirst();
        Fact updated;
        if (existing.isPresent()) {
            Fact fact = existing.get();
            List<String> evidence = new ArrayList<>(fact.evidenceMessageIds());
            boolean newEvidence = messageId != null && !evidence.contains(messageId);
            if (newEvidence) evidence.add(messageId);
            String mergedStatus = fact.status();
            // 明确表达可以把推测升级为 STATED，但不会覆盖客服确认或纠正结果。
            if ("INFERRED".equals(fact.status()) && "STATED".equals(status)) mergedStatus = "STATED";
            updated = new Fact(fact.id(), dimension, value, mergedStatus,
                    fact.mentions() + (newEvidence || messageId == null ? 1 : 0),
                    evidence, snippet == null ? fact.lastSnippet() : snippet, source, OffsetDateTime.now());
            profile.facts().set(profile.facts().indexOf(fact), updated);
        } else {
            List<String> evidence = new ArrayList<>();
            if (messageId != null) evidence.add(messageId);
            updated = new Fact(UUID.randomUUID().toString().substring(0, 8), dimension, value, status, 1,
                    evidence, snippet, source, OffsetDateTime.now());
            profile.facts().add(updated);
        }
        return updated;
    }

    public synchronized Optional<Fact> setStatus(String customerId, String factId, String status) {
        Profile profile = get(customerId);
        for (int i = 0; i < profile.facts().size(); i++) {
            Fact fact = profile.facts().get(i);
            if (fact.id().equals(factId)) {
                Fact updated = new Fact(fact.id(), fact.dimension(), fact.value(), status, fact.mentions(),
                        fact.evidenceMessageIds(), fact.lastSnippet(), "AGENT", OffsetDateTime.now());
                profile.facts().set(i, updated);
                save();
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling("profiles.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), profiles);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 演示环境写盘失败不影响当次推荐。
        }
    }

    public record Profile(
            String customerId,
            List<Fact> facts,
            List<String> processedMessageIds,
            OffsetDateTime createdAt) {
    }

    public record Fact(
            String id,
            String dimension,
            String value,
            String status,
            int mentions,
            List<String> evidenceMessageIds,
            String lastSnippet,
            String source,
            OffsetDateTime updatedAt) {
    }
}
