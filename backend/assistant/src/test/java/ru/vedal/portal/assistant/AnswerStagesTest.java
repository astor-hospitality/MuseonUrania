package ru.vedal.portal.assistant;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class AnswerStagesTest {
    @Test void emitsStagesAtActualOperations() {
        var calls = new ArrayList<String>();
        Retrieval retrieval = (question, scope) -> {
            calls.add("retrieve");
            return List.of(new Retrieval.Passage(
                    new LlmEngine.Source("Тест", "/documents/", "document"), "Документ"));
        };
        ChatModel model = (messages, chunks) -> { calls.add("model"); chunks.accept("Ответ"); return "Ответ"; };
        // Раздел документов открыт: материал здесь — документ, и при скрытом
        // разделе движок отсеял бы его до модели, а тест про стадии не про это.
        var engine = new ModelEngine(retrieval, model, false, PublicDocuments.SHOWN);
        engine.answer("Документ", "", LlmEngine.Scope.PUBLIC, chunk -> calls.add("chunk"), calls::add);
        assertThat(calls).containsExactly("searching", "retrieve", "composing", "model", "chunk");
    }
    @Test void doesNotClaimToComposeWhenNothingWasFound() {
        var calls = new ArrayList<String>();
        var engine = new ModelEngine((q, s) -> List.of(), (m, c) -> { throw new AssertionError("no materials"); }, false,
                PublicDocuments.HIDDEN);
        assertThat(engine.answer("Тест", "", LlmEngine.Scope.PUBLIC, c -> {}, calls::add)).isEmpty();
        assertThat(calls).containsExactly("searching");
    }
}
