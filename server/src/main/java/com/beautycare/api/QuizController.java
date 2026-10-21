package com.beautycare.api;

import java.util.List;
import java.util.Map;

import com.beautycare.personalization.SkinQuizService;
import com.beautycare.personalization.SkinQuizService.QuizResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 测肤问卷接口。
 * 小程序页面：/quiz.html?c={customerId}（静态页，调用下面的接口）。
 * 对话内快捷提问：GET next → 在聊天里以选项按钮展示 → 用户点选后 POST answer。
 */
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final SkinQuizService quizService;

    public QuizController(SkinQuizService quizService) {
        this.quizService = quizService;
    }

    @GetMapping
    public JsonNode quiz() {
        return quizService.quiz();
    }

    /** 下一道最值得问的题；204 表示信息已足够，不必再问。 */
    @GetMapping("/{customerId}/next")
    public ResponseEntity<JsonNode> next(@PathVariable String customerId) {
        try {
            JsonNode question = quizService.nextQuestion(customerId);
            return question == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(question);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/{customerId}/answer")
    public QuizResult answer(@PathVariable String customerId, @RequestBody AnswerRequest request) {
        try {
            return quizService.answer(customerId, request.questionId(), request.optionIds() == null ? List.of() : request.optionIds());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/{customerId}/submit")
    public QuizResult submit(@PathVariable String customerId, @RequestBody SubmitRequest request) {
        try {
            return quizService.submit(customerId, request.answers() == null ? Map.of() : request.answers(), request.note());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    public record AnswerRequest(String questionId, List<String> optionIds) {
    }

    public record SubmitRequest(Map<String, List<String>> answers, String note) {
    }
}
