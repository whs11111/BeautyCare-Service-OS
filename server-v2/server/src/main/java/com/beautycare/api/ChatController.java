package com.beautycare.api;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private final List<Map<String, Object>> messages = new CopyOnWriteArrayList<>();

    @GetMapping("/chat/list")
    public List<Object> list() {
        return List.of();
    }

    @GetMapping("/chat/detail/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return Map.of("id", id, "messages", messages);
    }

    @PostMapping("/message/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> body) {
        Map<String, Object> saved = new HashMap<>(body);
        saved.putIfAbsent("time", LocalTime.now().withSecond(0).toString());
        messages.add(saved);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/order/check")
    public Map<String, Object> order() {
        return Map.of(
                "orderedSku", "01",
                "receivedSku", "05",
                "match", 93,
                "result", "SKU错发");
    }

    @GetMapping("/ai/analyze")
    public Map<String, Object> analyze(@RequestParam(defaultValue = "1") long id) {
        return Map.of(
                "conversationId", id,
                "riskScore", 82,
                "level", "L3",
                "emotion", "critical",
                "keywords", List.of("投诉12315", "第三次进线"));
    }

    @PostMapping("/workorder/create")
    public ResponseEntity<Map<String, Object>> workorder(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(Map.of(
                "id", "WO-20260919-018",
                "status", "CREATED",
                "priority", "URGENT"));
    }
}
