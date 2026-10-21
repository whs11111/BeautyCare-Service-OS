package com.beautycare.workflow;

import java.util.*;
import com.beautycare.ai.*;
import com.beautycare.personalization.*;
import com.beautycare.api.QuizController;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Run with an isolated -Duser.home directory. No cloud API credentials or calls. */
public class QuizSmokeTest {
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); System.out.println("PASS " + label); }
    static void bad(Runnable task, String label) {
        boolean rejected = false;
        try { task.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, label);
    }
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var store = new CustomerProfileStore(mapper);
        var meter = new TokenMeter();
        var router = new ModelRouter("deepseek", "https://invalid.example", "", "unused", "unused", "unused", "unused",
                "https://invalid.example", "", "deepseek-chat", "https://invalid.example", "", "unused", "risk");
        var client = new ModelClient(RestClient.builder(), mapper, meter, router, new TrainingRecorder(mapper, false), 10, 1, 1);
        var personalization = new PersonalizationService(new ProductCatalog(mapper, "catalog.json"), store, client, meter);
        var service = new SkinQuizService(mapper, store, personalization, client);
        check(service.quiz().path("questions").size() == 11, "all eleven source questionnaire questions loaded");
        String id = "conversation-9001";
        check(service.nextQuestion(id).path("id").asText().equals("cheek_tight"), "dryness asked before oil");
        check(service.answer(id, "cheek_tight", List.of("yes")).skinType() == null, "do not classify from dryness alone");
        var mixed = service.answer(id, "noon_oil", List.of("heavy"));
        check("混干".equals(mixed.skinType()), "T-zone oily and dry cheeks are classified by region");
        int count = store.get(id).facts().size();
        String factId = store.get(id).facts().stream().filter(f -> f.value().equals("混干")).findFirst().orElseThrow().id();
        service.answer(id, "noon_oil", List.of("heavy"));
        check(store.get(id).facts().size() == count && store.get(id).facts().stream().anyMatch(f -> f.id().equals(factId)), "repeat answers do not duplicate or recreate facts");
        var reloaded = new SkinQuizService(mapper, store, personalization, client);
        check(reloaded.nextQuestion(id).path("id").asText().equals("reactivity"), "answers survive service restart");
        bad(() -> service.answer(id, "reactivity", List.of("made-up")), "unknown option rejected");
        bad(() -> service.answer(id, "reactivity", List.of("often", "rarely")), "single choice enforced");
        bad(() -> service.answer(id, "intolerance", List.of("none", "alcohol")), "exclusive options cannot mix");
        bad(() -> service.answer(id, "goals", List.of("red", "dry", "oil", "acne")), "multi-select maximum enforced");
        bad(() -> service.submit(id, Map.of(), null), "incomplete full survey rejected");
        Map<String, List<String>> all = new LinkedHashMap<>();
        service.quiz().path("questions").forEach(q -> all.put(q.path("id").asText(), List.of(q.path("options").get(0).path("id").asText())));
        all.put("intolerance", List.of("alcohol", "fragrance"));
        all.put("budget", List.of("b400"));
        var result = service.submit("conversation-9002", all, "");
        check(result.answeredCount() == 11 && result.report() != null && result.reportMode().equals("TEMPLATE"), "no-key survey returns a clearly labeled template report");
        check(result.recommendation() != null && !result.recommendation().items().isEmpty(), "survey returns skincare recommendations");
        check(result.recommendation().items().stream().noneMatch(item -> item.category().equals("粉底")), "survey does not recommend makeup for skincare result");
        check(store.get("conversation-9002").facts().stream().anyMatch(f -> f.dimension().equals("avoid") && f.value().equals("含香精")), "answers write into canonical customer profile");
        check(service.nextQuestion("conversation-9002") == null, "completed dimensions are not asked again");
        var rejected = store.get("conversation-9002").facts().stream().filter(f -> f.value().equals("含香精")).findFirst().orElseThrow();
        store.setStatus("conversation-9002", rejected.id(), "REJECTED");
        service.submit("conversation-9002", all, "");
        check(store.get("conversation-9002").facts().stream().filter(f -> f.id().equals(rejected.id())).allMatch(f -> f.status().equals("REJECTED") && f.source().equals("AGENT")), "survey never overwrites agent exclusion");
        all.put("budget", List.of("b200"));
        service.submit("conversation-9002", all, "");
        check(store.get("conversation-9002").facts().stream().filter(f -> f.dimension().equals("budget")).count() == 1, "changed budget replaces obsolete quiz-owned preference");
        check(store.get("conversation-9003").facts().isEmpty(), "profiles of different customers stay isolated");
        var controller = new QuizController(service);
        check(controller.next("conversation-9002").getStatusCode().value() == 204, "next endpoint uses 204 when complete");
        boolean status400 = false;
        try { controller.answer(id, new QuizController.AnswerRequest("missing", List.of("x"))); }
        catch (ResponseStatusException expected) { status400 = expected.getStatusCode().value() == 400; }
        check(status400, "bad inputs produce 400 instead of server error");
        System.out.println("ALL PASSED");
    }
}
