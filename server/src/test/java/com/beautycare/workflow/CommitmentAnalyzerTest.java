package com.beautycare.workflow;

import com.beautycare.api.ReceptionController;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;

/** Isolated semantic-provider contract and persistence tests, without paid model requests. */
public class CommitmentAnalyzerTest {
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); System.out.println("PASS " + label); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OffsetDateTime sent = OffsetDateTime.now();
        Map<String, Object> source = Map.of("text", "您稍等5分钟", "sentAt", sent.toString());
        CommitmentAnalyzer analyzer = new CommitmentAnalyzer(input -> mapper.valueToTree(Map.of("hasCommitment", true, "evidence", "稍等5分钟", "description", "5分钟后回复客户", "dueAt", sent.plusMinutes(5).toString())));
        check(analyzer.analyze(source, List.of()).dueAt().equals(sent.plusMinutes(5)), "Colloquial message is analyzed by provider, not keyword gate");
        CommitmentAnalyzer vague = new CommitmentAnalyzer(input -> mapper.valueToTree(Map.of("hasCommitment", true, "evidence", "稍等5分钟", "description", "跟进回复")));
        check(vague.analyze(source, List.of()).dueAt() == null, "Missing deadline is never invented");
        CommitmentAnalyzer invalid = new CommitmentAnalyzer(input -> mapper.valueToTree(Map.of("hasCommitment", true, "evidence", "不存在的承诺", "description", "假承诺")));
        try { invalid.analyze(source, List.of()); throw new AssertionError("Ungrounded promise accepted"); } catch (IllegalArgumentException expected) { check(true, "Fabricated evidence rejected"); }
        ReceptionController reception = new ReceptionController(mapper);
        reception.bootstrap(List.of(Map.of("id", 1, "name", "测试客户")));
        String auth = "Bearer " + reception.login(Map.of("account", "agent1", "password", "123456")).get("token");
        Map<String, Object> state = reception.open("1");
        reception.send("1", auth, Map.of("id", "spoken", "from", "agent", "text", "您稍等5分钟"));
        check(reception.commitmentJobs().size() == 1, "All actual agent texts queue semantic extraction");
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) state.get("commitments");
        reception.finishCommitment("1", "spoken", new CommitmentAnalyzer.Result(true, "5分钟后回复", sent.plusMinutes(5)), "MODEL");
        check(tasks.size() == 1 && Boolean.FALSE.equals(tasks.get(0).get("reminderEligible")), "Short semantic commitment persisted without popup");
        check(reception.commitmentJobs().isEmpty(), "Completed extraction is not called again");
        reception.send("1", auth, Map.of("id", "vague", "from", "agent", "text", "我尽快给您回复"));
        reception.finishCommitment("1", "vague", new CommitmentAnalyzer.Result(true, "尽快回复客户", null), "MODEL");
        check(tasks.get(1).get("dueAt") == null && Boolean.FALSE.equals(tasks.get(1).get("reminderEligible")), "Vague promise saved without fake deadline");
        reception.send("1", auth, Map.of("id", "fallback", "from", "agent", "text", "我会在20分钟内回复您"));
        reception.finishCommitment("1", "fallback", null, "RULE_FALLBACK");
        check(tasks.size() == 3 && Boolean.TRUE.equals(tasks.get(2).get("reminderEligible")), "Provider failure retains rule fallback");
        reception.send("1", auth, Map.of("id", "race", "from", "agent", "text", "我会在30分钟内回复您"));
        Map<String, Object> race = tasks.get(3);
        reception.updateCommitment("1", race.get("id").toString(), auth, Map.of("action", "end", "revision", "1"));
        reception.finishCommitment("1", "race", new CommitmentAnalyzer.Result(true, "晚到结果", sent.plusHours(1)), "MODEL");
        check("ENDED".equals(race.get("status")) && tasks.size() == 4, "Late model cannot revive manually ended promise");
        check(new ReceptionController(mapper).commitmentJobs().isEmpty(), "Analysis completion persists after restart");
        System.out.println("ALL COMMITMENT CHECKS PASSED");
    }
}
