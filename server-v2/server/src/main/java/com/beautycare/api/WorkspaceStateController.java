package com.beautycare.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace/state")
public class WorkspaceStateController {

    private final ObjectMapper objectMapper;
    private final Path stateFile = Path.of(
            System.getProperty("user.home"),
            ".beautycare-service-os",
            "workspace-state.json");

    public WorkspaceStateController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public synchronized JsonNode load() throws IOException {
        if (!Files.exists(stateFile)) {
            return objectMapper.valueToTree(Map.of("conversations", new Object[0]));
        }
        return objectMapper.readTree(stateFile.toFile());
    }

    @PutMapping
    public synchronized Map<String, Object> save(@RequestBody JsonNode state)
            throws IOException {
        Files.createDirectories(stateFile.getParent());
        Path receptionFile = stateFile.resolveSibling("reception.json");
        if (Files.exists(receptionFile)) {
            JsonNode reception = objectMapper.readTree(receptionFile.toFile());
            JsonNode previous = Files.exists(stateFile) ? objectMapper.readTree(stateFile.toFile()) : objectMapper.createObjectNode();
            state = protectRestoredHistories(state, reception, previous);
        }
        Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), state);
        try {
            Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return Map.of("saved", true);
    }

    /** Old browser tabs must not resurrect messages removed by an explicit restore. */
    public static JsonNode protectRestoredHistories(JsonNode incoming, JsonNode reception, JsonNode previous) {
        JsonNode safe = incoming.deepCopy();
        if (!(safe.path("conversations") instanceof ArrayNode conversations)) return safe;
        for (int i = 0; i < conversations.size(); i++) {
            JsonNode candidate = conversations.get(i);
            JsonNode authoritative = reception.path(candidate.path("id").asText());
            String revision = authoritative.path("historyRevision").asText("");
            if (revision.isBlank()) continue;
            if (!revision.equals(candidate.path("historyRevision").asText())) {
                JsonNode saved = null;
                for (JsonNode item : previous.path("conversations")) {
                    if (item.path("id").equals(candidate.path("id")) && revision.equals(item.path("historyRevision").asText())) {
                        saved = item;
                        break;
                    }
                }
                if (saved == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "聊天记录已恢复，请刷新页面后重试");
                candidate = saved.deepCopy();
                conversations.set(i, candidate);
            }
            if (candidate instanceof ObjectNode object) {
                object.set("messages", authoritative.path("messages").deepCopy());
                object.set("reception", authoritative.path("session").deepCopy());
                object.set("sessions", authoritative.path("sessions").deepCopy());
                object.set("commitments", authoritative.path("commitments").deepCopy());
            }
        }
        return safe;
    }
}
