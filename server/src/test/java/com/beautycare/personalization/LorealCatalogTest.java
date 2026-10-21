package com.beautycare.personalization;

import com.beautycare.ai.TokenMeter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** No network or credentials. Run with an isolated user.home. */
public class LorealCatalogTest {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); System.out.println("PASS " + label); }
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var paris = new ProductCatalog(mapper);
        check(paris.all().size() == 58 && paris.all().stream().allMatch(p -> "巴黎欧莱雅".equals(p.brand())), "Default catalog retains original 55 and adds 3 verified Paris Loreal products");
        check(paris.all().stream().map(ProductCatalog.Product::sku).distinct().count() == 58, "Active catalog has no duplicate product identifiers");
        try (var stream = new org.springframework.core.io.ClassPathResource("catalog-paris-replacements.json").getInputStream()) {
            var mapping = mapper.readTree(stream).path("items");
            check(mapping.size() == 65, "All 65 original other-brand items preserved in replacement mapping");
            Set<String> originalIds = new HashSet<>();
            int comparable = 0, review = 0, missing = 0;
            for (var item : mapping) {
                originalIds.add(item.path("originalSku").asText());
                String target = item.path("candidateSku").asText("");
                if (!target.isBlank()) check(paris.find(target).isPresent(), "Replacement target exists: " + target);
                switch (item.path("status").asText()) {
                    case "SAME_CATEGORY" -> { comparable++; check(paris.find(target).orElseThrow().category().equals(item.path("category").asText()), "Comparable replacement preserves category"); }
                    case "INCLUDED_WITH_VERIFIED_FEATURES" -> review++;
                    case "NO_VERIFIED_EQUIVALENT" -> { missing++; check(target.isBlank(), "Unverified gaps have no fabricated target"); }
                    default -> throw new AssertionError("Unknown replacement state");
                }
            }
            check(originalIds.size() == 65 && comparable == 25 && review == 30 && missing == 10, "Replacement coverage and uncertainty counts explicit");
        }
        try (var stream = new org.springframework.core.io.ClassPathResource("catalog-paris-feature-evidence.json").getInputStream()) {
            var evidence = mapper.readTree(stream).path("items");
            check(evidence.size() == 18, "Thirty mappings resolve to eighteen unique evidence-backed products");
            Set<String> evidenceIds = new HashSet<>();
            for (var item : evidence) {
                String sku = item.path("sku").asText();
                check(evidenceIds.add(sku), "Evidence has unique target: " + sku);
                var product = paris.find(sku).orElseThrow();
                check(product.skinTypes().isEmpty() && !product.verifiedIngredients(), "Unknown skin suitability and full ingredients remain unknown: " + sku);
                check(mapper.valueToTree(product.highlights()).equals(item.path("features")), "Active features match official evidence: " + sku);
                check(mapper.valueToTree(product.concerns()).equals(item.path("concerns")), "Recommendation tags match reviewed evidence: " + sku);
                check(!item.path("official").path("name").asText().isBlank(), "Official source text retained: " + sku);
            }
            try (var mappings = new org.springframework.core.io.ClassPathResource("catalog-paris-replacements.json").getInputStream()) {
                for (var item : mapper.readTree(mappings).path("items")) {
                    if ("INCLUDED_WITH_VERIFIED_FEATURES".equals(item.path("status").asText())) {
                        check(evidenceIds.contains(item.path("candidateSku").asText()), "Every newly included replacement has evidence");
                    }
                }
            }
        }
        check(paris.find("LP-SQ045").orElseThrow().flags().contains("A醇"), "Verified retinol warning is not erased with unknown fields");
        check(paris.find("LP-SC567").orElseThrow().concerns().isEmpty(), "Generic texture description does not imply barrier repair or anti-aging");
        check(paris.find("LP-SC017").orElseThrow().concerns().isEmpty(), "Gentle cleanser description does not imply dryness treatment");
        check(paris.find("LP-SC389-G4433000").orElseThrow().price() == 590 && paris.find("LP-SC415-G4425700").orElseThrow().price() == 272, "New products use single-variant prices, not bundle totals");
        check(paris.find("CV-001").isEmpty() && paris.all().stream().allMatch(p -> p.source().startsWith("https://www.lorealparis.com.cn/")), "Other group brands and API source URLs excluded from active catalog");
        var catalog = new ProductCatalog(mapper, "catalog-loreal.json");
        check(catalog.all().size() == 120, "Original 120-product snapshot preserved as archive");
        check(catalog.all().stream().map(ProductCatalog.Product::sku).distinct().count() == 120, "Unique product identifiers");
        check(catalog.all().stream().allMatch(p -> p.source() != null && p.source().startsWith("https://") && p.brand() != null), "Brand and HTTPS provenance preserved");
        check(catalog.all().stream().noneMatch(ProductCatalog.Product::verifiedIngredients), "Unverified ingredients remain unknown");
        var store = new CustomerProfileStore(mapper);
        var service = new PersonalizationService(catalog, store, null, new TokenMeter());
        store.upsert("sensitive", "skinType", "敏感肌", "CONFIRMED", "CHAT", "s1", "我是敏感肌");
        store.upsert("sensitive", "avoid", "含香精", "CONFIRMED", "CHAT", "s2", "不要含香精");
        store.upsert("sensitive", "concern", "泛红", "CONFIRMED", "CHAT", "s3", "容易泛红");
        var sensitive = service.recommend("sensitive", "推荐面霜");
        check(!sensitive.items().isEmpty(), "Sensitive profile has products to review");
        check(sensitive.items().stream().allMatch(p -> catalog.find(p.sku()).orElseThrow().skinTypes().contains("敏感肌") || p.reasons().stream().anyMatch(r -> r.contains("敏感肌适用性尚未核实"))), "Unknown sensitive suitability is disclosed instead of rejected");
        check(sensitive.items().stream().allMatch(p -> p.reasons().stream().anyMatch(r -> r.contains("包装成分表"))), "Every unverified recommendation carries ingredient caveat");
        check(sensitive.items().stream().flatMap(p -> p.reasons().stream()).noneMatch(r -> r.contains("无酒精") || r.contains("无香精") || r.contains("无A醇")), "Empty flags never become free-from claims");
        check(sensitive.items().stream().allMatch(p -> p.source().startsWith("https://") && !p.ingredientsVerified()), "Recommendation response retains source and verification state");
        store.upsert("oil", "skinType", "油皮", "STATED", "CHAT", "o1", "我是油皮");
        store.upsert("oil", "concern", "出油", "STATED", "CHAT", "o2", "出油很多");
        check(service.recommend("oil", "推荐洁面").items().stream().noneMatch(p -> p.name().contains("男士")), "Men line absent without explicit request");
        check(service.recommend("oil", "男士洁面").items().stream().anyMatch(p -> p.name().contains("男士")), "Explicit men request enables men products");
        store.upsert("retinol", "concern", "细纹", "STATED", "CHAT", "r1", "想改善细纹");
        store.upsert("retinol", "avoid", "A醇", "STATED", "CHAT", "r2", "不要A醇");
        check(service.recommend("retinol", "精华").items().stream().noneMatch(p -> catalog.find(p.sku()).orElseThrow().flags().contains("A醇")), "Known ingredient conflict remains hard-filtered");
        check(catalog.all().stream().filter(p -> p.price() == 0).count() == 5, "Five missing prices preserved as unknown");
        check(new ProductCatalog(mapper, "catalog.json").all().size() == 22, "Legacy catalog remains available for isolated tests");
        var parisService = new PersonalizationService(paris, store, null, new TokenMeter());
        var parisOil = parisService.recommend("oil", "推荐防晒");
        check(!parisOil.items().isEmpty() && parisOil.items().stream().allMatch(p -> "巴黎欧莱雅".equals(p.brand())), "Actual recommendations use only Paris Loreal");
        store.upsert("sensitive", "skinType", "混干", "CONFIRMED", "QUIZ", "s4", "两颊干燥");
        store.upsert("sensitive", "concern", "干燥起皮", "CONFIRMED", "QUIZ", "s5", "干燥起皮");
        var sensitiveParis = parisService.recommend("sensitive", "推荐面霜");
        check(!sensitiveParis.items().isEmpty(), "Sensitive combination-dry profile can match Paris products by other needs");
        check(sensitiveParis.items().stream().allMatch(p -> "巴黎欧莱雅".equals(p.brand())), "Unknown suitability never causes fallback to other brands");
        check(sensitiveParis.items().stream().allMatch(p -> p.reasons().stream().anyMatch(r -> r.contains("敏感肌适用性尚未核实"))), "Paris recommendations disclose unknown sensitive suitability");
        check(sensitiveParis.items().stream().allMatch(p -> Collections.disjoint(paris.find(p.sku()).orElseThrow().flags(), List.of("含酒精", "含香精", "高浓度酸", "A醇", "高浓度VC"))), "Known sensitive risk conflicts remain excluded");
        store.upsert("unknown-features", "skinType", "油皮", "CONFIRMED", "QUIZ", "u1", "油皮");
        store.upsert("unknown-features", "ingredient", "神经酰胺", "CONFIRMED", "CHAT", "u2", "想要神经酰胺");
        store.upsert("unknown-features", "concern", "闭口", "CONFIRMED", "QUIZ", "u3", "闭口");
        var unknownFeatures = parisService.recommend("unknown-features", "推荐面霜");
        check(!unknownFeatures.items().isEmpty(), "Missing ingredient, concern and skin labels do not reject candidates");
        check(unknownFeatures.items().stream().allMatch(p -> p.reasons().stream().anyMatch(r -> r.contains("神经酰胺尚未核实"))), "Unknown requested ingredient is disclosed, never claimed present");
        check(unknownFeatures.items().stream().flatMap(p -> p.reasons().stream()).noneMatch(r -> r.equals("针对闭口")), "Unknown efficacy does not become a positive match");
        System.out.println("ALL LOREAL CATALOG CHECKS PASSED");
    }
}
