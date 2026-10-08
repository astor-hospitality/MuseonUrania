package ru.vedal.portal.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.vedal.portal.PostgresTestBase;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила, по которым Ведалина зовёт модель.
 *
 * <p>Модель здесь подставная: проверяются не её формулировки, а устройство
 * вокруг неё — когда её спрашивают, что ей показывают, что делают с ответом
 * и что происходит, когда она молчит. Настоящая модель отвечает по-разному
 * на один и тот же вопрос, и тест на её текст был бы тестом на погоду.
 */
class ModelEngineTest extends PostgresTestBase {

    @Autowired
    DeterministicSearch search;

    /** Записывает, о чём спросили, и отвечает заготовкой. */
    private static final class Подставная implements ChatModel {
        final List<Message> asked = new ArrayList<>();
        String reply = "Инкубатор-трансформер VEDAL A-2000 [1] подходит для отделения.";
        RuntimeException fail;

        @Override
        public String complete(List<Message> messages, Consumer<String> onChunk) {
            asked.addAll(messages);
            if (fail != null) throw fail;
            onChunk.accept(reply);
            return reply;
        }
    }

    @Test
    void theAnswerComesFromTheModelAndTheLinksFromThePortal() {
        var model = new Подставная();
        var engine = new ModelEngine(search, model, true, PublicDocuments.HIDDEN);

        var answer = engine.answer("Что такое VEDAL A-2000?", LlmEngine.Scope.PUBLIC).orElseThrow();

        assertThat(answer.text()).isEqualTo(model.reply);
        assertThat(answer.sources())
                .as("Ссылки даёт поиск, а не модель: своя ссылка у неё была бы "
                        + "правдоподобной и несуществующей")
                .isNotEmpty();
        assertThat(answer.sources().getFirst().url()).startsWith("/");
    }

    // Главное правило: не нашлось материалов — модель не спрашивается вовсе.
    // Придумывать ей нечего, а просить её сказать «не знаю» значит платить
    // за отказ.
    @Test
    void withoutMaterialsTheModelIsNotAskedAtAll() {
        var model = new Подставная();
        var engine = new ModelEngine(search, model, true, PublicDocuments.HIDDEN);

        var answer = engine.answer("расскажи про погоду в Кабуле", LlmEngine.Scope.PUBLIC);

        assertThat(answer).isEmpty();
        assertThat(model.asked)
                .as("Вопрос не по теме до модели доходить не должен")
                .isEmpty();
    }

    // Модель видит ровно то, что нашёл портал, — с номерами, которыми потом
    // ссылается. Номер в тексте и номер источника под ответом обязаны быть
    // одним числом, иначе сноска уведёт читателя не туда.
    @Test
    void theModelSeesTheFoundMaterialsNumberedTheSameWayAsTheLinks() {
        var model = new Подставная();
        var engine = new ModelEngine(search, model, true, PublicDocuments.HIDDEN);

        var answer = engine.answer("Что такое VEDAL A-2000?", LlmEngine.Scope.PUBLIC).orElseThrow();

        var rules = model.asked.stream()
                .filter(m -> m.role() == ChatModel.Role.SYSTEM)
                .findFirst()
                .orElseThrow()
                .text();

        assertThat(rules).contains("Материалы:").contains("[1] ");
        assertThat(rules)
                .as("Первый материал в промпте — первый источник под ответом")
                .contains(answer.sources().getFirst().title());

        // Запреты проекта названы прямо: цена, сроки, диагнозы. Модель знает
        // их «вообще» — по чужим производителям — и вставит с полной
        // уверенностью, если не запретить.
        assertThat(rules).contains("цен").contains("сроков").contains("диагноз");
    }

    // Вопрос уходит отдельной репликой, а не подклеивается к правилам:
    // склеенный с ними, он читается моделью как часть инструкции, и просьба
    // «забудь правила» из вопроса встаёт с ними в один ряд.
    @Test
    void theQuestionIsAskedAsTheVisitorsOwnMessage() {
        var model = new Подставная();
        new ModelEngine(search, model, true, PublicDocuments.HIDDEN).answer("Что такое VEDAL A-2000?", LlmEngine.Scope.PUBLIC);

        var user = model.asked.stream().filter(m -> m.role() == ChatModel.Role.USER).toList();
        assertThat(user).hasSize(1);
        assertThat(user.getFirst().text()).isEqualTo("Что такое VEDAL A-2000?");
    }

    @Test
    void aFollowUpUsesOnlyItsOwnConversationContext() {
        var model = new Подставная();
        var engine = new ModelEngine(search, model, true, PublicDocuments.HIDDEN);

        var answer = engine.answer("А сколько он весит?",
                "Посетитель: Расскажи про VEDAL R1.\nВедалина: VEDAL R1 — открытая система.",
                LlmEngine.Scope.PUBLIC, chunk -> { }).orElseThrow();

        assertThat(answer.sources()).anyMatch(source -> source.title().contains("VEDAL R1"));
        var system = model.asked.stream()
                .filter(message -> message.role() == ChatModel.Role.SYSTEM)
                .findFirst().orElseThrow().text();
        assertThat(system).contains("Контекст текущего разговора:")
                .contains("Расскажи про VEDAL R1");
        assertThat(model.asked.getLast().text()).isEqualTo("А сколько он весит?");
    }

    @Test
    void aPunctuatedFollowUpUsesTheConversationContextForSearch() {
        var model = new Подставная();
        var engine = new ModelEngine(search, model, true, PublicDocuments.HIDDEN);

        // Изделие — R2, а не T-100: T-100 снят с публикации в каталоге (V40),
        // и находился он только строкой перечня документов. При скрытом
        // разделе документов такой источник посетителю не отдаётся, и тест
        // проверял бы уже не контекст разговора, а состав перечня.
        var answer = engine.answer("А маленького?",
                "Посетитель: Расскажи про VEDAL R2.",
                LlmEngine.Scope.PUBLIC, chunk -> { }).orElseThrow();

        assertThat(answer.sources()).anyMatch(source -> source.title().contains("VEDAL R2"));
    }

    // Ответ приходит кусками — ради них в разговоре и заведено событие draft.
    @Test
    void theAnswerIsHandedOverAsItArrives() {
        var model = new Подставная();
        var chunks = new ArrayList<String>();

        new ModelEngine(search, model, true, PublicDocuments.HIDDEN)
                .answer("Что такое VEDAL A-2000?", LlmEngine.Scope.PUBLIC, chunks::add);

        assertThat(String.join("", chunks)).isEqualTo(model.reply);
    }

    // Модель молчит — материалы всё равно есть. Отдаём их перечнем: посетитель
    // получает ссылки сразу, а не ждёт человека из-за чужой недоступности.
    @Test
    void whenTheModelIsDownTheFoundMaterialsAreStillAnswered() {
        var model = new Подставная();
        model.fail = new IllegalStateException("Модель недоступна");
        var engine = new ModelEngine(search, model, true, PublicDocuments.HIDDEN);

        var answer = engine.answer("Что такое VEDAL A-2000?", LlmEngine.Scope.PUBLIC).orElseThrow();

        assertThat(answer.sources()).isNotEmpty();
        assertThat(answer.text())
                .as("Это перечень найденного — тот же ответ, что был до модели")
                .contains("VEDAL A-2000");
    }
}
