package com.beautycare.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class RestoredHistorySmokeTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode previous = mapper.readTree("""
                {"conversations":[{"id":5,"historyRevision":"new","messages":[{"id":"shipping"}],"tag":"keep"},{"id":2,"messages":[{"id":"zhou"}]}]}
                """);
        JsonNode incoming = mapper.readTree("""
                {"conversations":[{"id":5,"messages":[{"id":"old-good-review"}],"tag":"stale"},{"id":2,"messages":[{"id":"zhou"}]}]}
                """);
        JsonNode reception = mapper.readTree("""
                {"5":{"historyRevision":"new","messages":[{"id":"shipping"}],"session":{"agentId":"agent2"},"sessions":[],"commitments":[]}}
                """);
        JsonNode result = WorkspaceStateController.protectRestoredHistories(incoming, reception, previous);
        if (!result.path("conversations").get(0).path("messages").get(0).path("id").asText().equals("shipping"))
            throw new AssertionError("Old tab restored deleted messages");
        if (!result.path("conversations").get(0).path("tag").asText().equals("keep"))
            throw new AssertionError("Old tab replaced reset metadata");
        if (!result.path("conversations").get(1).equals(incoming.path("conversations").get(1)))
            throw new AssertionError("Unrelated customer changed");
        if (!incoming.path("conversations").get(0).path("tag").asText().equals("stale"))
            throw new AssertionError("Input mutated");
        ((com.fasterxml.jackson.databind.node.ObjectNode)incoming.path("conversations").get(0)).put("historyRevision", "new");
        result = WorkspaceStateController.protectRestoredHistories(incoming, reception, previous);
        if (result.toString().contains("old-good-review")) throw new AssertionError("Same revision reintroduced deleted messages");
        System.out.println("PASS old workspace writes cannot resurrect restored history; unrelated customers preserved");
    }
}
