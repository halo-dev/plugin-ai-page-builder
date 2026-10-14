package run.halo.aipagebuilder.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validates model questions and Console answers independently of SDK schema validation. */
final class UserQuestions {
    private UserQuestions() { }

    static Map<String, Object> schema() {
        var option = Map.of("type", "object", "properties", Map.of(
            "id", Map.of("type", "string", "maxLength", 64),
            "label", Map.of("type", "string", "maxLength", 200)),
            "required", List.of("id", "label"), "additionalProperties", false);
        var question = Map.of("type", "object", "properties", Map.of(
            "id", Map.of("type", "string", "maxLength", 64),
            "title", Map.of("type", "string", "maxLength", 500),
            "options", Map.of("type", "array", "maxItems", 4, "items", option),
            "allowCustomAnswer", Map.of("type", "boolean")),
            "required", List.of("id", "title", "options", "allowCustomAnswer"),
            "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of("questions",
            Map.of("type", "array", "minItems", 1, "maxItems", 3, "items", question)),
            "required", List.of("questions"), "additionalProperties", false);
    }

    static ChatApi.Question parse(Map<String, Object> input) {
        if (input == null || !(input.get("questions") instanceof List<?> questions)
            || questions.isEmpty() || questions.size() > 3) throw invalid();
        var result = new ArrayList<ChatApi.QuestionItem>();
        var ids = new HashSet<String>();
        for (var raw : questions) {
            if (!(raw instanceof Map<?, ?> question)
                || !(question.get("options") instanceof List<?> options)
                || !(question.get("allowCustomAnswer") instanceof Boolean custom)
                || options.size() == 1 || options.size() > 4 || options.isEmpty() && !custom) {
                throw invalid();
            }
            var id = text(question.get("id"), 64);
            if (!ids.add(id)) throw invalid();
            var choices = new ArrayList<ChatApi.QuestionOption>();
            var optionIds = new HashSet<String>();
            for (var rawOption : options) {
                if (!(rawOption instanceof Map<?, ?> option)) throw invalid();
                var optionId = text(option.get("id"), 64);
                if (!optionIds.add(optionId)) throw invalid();
                choices.add(new ChatApi.QuestionOption(optionId, text(option.get("label"), 200)));
            }
            result.add(new ChatApi.QuestionItem(id, text(question.get("title"), 500),
                List.copyOf(choices), custom));
        }
        return new ChatApi.Question(UUID.randomUUID().toString(), List.copyOf(result), null,
            Instant.now(), null);
    }

    static ChatApi.Question answer(ChatApi.Question question, ChatApi.AnswerRequest request) {
        if (request == null || request.answers() == null
            || request.answers().size() != question.questions().size()) throw invalid();
        var answers = new ArrayList<ChatApi.AnswerItem>();
        var ids = new HashSet<String>();
        for (var answer : request.answers()) {
            if (answer == null || !ids.add(answer.questionId())) throw invalid();
            var item = question.questions().stream().filter(q -> q.id().equals(answer.questionId()))
                .findFirst().orElseThrow(UserQuestions::invalid);
            var optionId = answer.optionId();
            var value = answer.text();
            if (optionId != null && item.options().stream().noneMatch(o -> o.id().equals(optionId))) {
                throw invalid();
            }
            if (value != null && (!item.allowCustomAnswer() || value.length() > 2000)) throw invalid();
            if (optionId == null && (value == null || value.isBlank())) throw invalid();
            answers.add(new ChatApi.AnswerItem(item.id(), optionId, value));
        }
        return new ChatApi.Question(question.id(), question.questions(), List.copyOf(answers),
            question.createdAt(), Instant.now());
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw invalid();
        return text;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题或答案格式无效：每次 1–3 题，每题 2–4 个选项或自由文本，答案最多 2000 字符");
    }
}
