package ru.vedal.portal.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import ru.vedal.portal.audit.AuditLog;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ведалина при скрытом разделе документов.
 *
 * <p><b>Почему такой тест понадобился.</b> Раздел документов на сайте скрыт
 * решением заказчика, а ассистент продолжал вести туда. Замер на стенде:
 * «Найти документ», набранное текстом, получало «В разделе «Документы
 * и лицензирование» доступны каталог, буклет, технические карточки изделий…»
 * со ссылкой на «/documents/» — раздел, которого посетитель на сайте
 * не найдёт.
 *
 * <p><b>Что стережёт.</b> Все пути, которыми документ доходит до посетителя:
 * кнопка, выдача файла по просьбе, поиск по словам, страницы сайта, промпт
 * модели и тексты отказов. Забытый путь вернул бы документы молча — ответ
 * просто стал бы снова длиннее на одну ссылку.
 *
 * <p>Базы здесь нет: материалы те же, что в {@link VedalinaAnswersTest},
 * и проверяется устройство, а не хранилище. Отсев в запросе к pgvector
 * проверить без базы нельзя — его сторожит повторная проверка в
 * {@link ModelEngine}, она проверяется ниже.
 */
class HiddenPublicDocumentsTest {

    private static final String PHONE = "8 800 600 3449";
    private static final String EMAIL = "sales@vedal-med.ru";
    private static final PublicDocuments HIDDEN = PublicDocuments.HIDDEN;

    private final SitePages pages = new SitePages(PHONE, EMAIL, "09:00", "17:30", HIDDEN);
    private final DeterministicSearch search = new DeterministicSearch(
            new VedalinaAnswersTest.Каталог(), new VedalinaAnswersTest.Лента(),
            new VedalinaAnswersTest.Перечень(), pages, HIDDEN);
    private final AssistantService vedalina = new AssistantService(
            new Guardrails(HIDDEN), search, new VedalinaAnswersTest.Перечень(), HIDDEN,
            Mockito.mock(AuditLog.class), PHONE, EMAIL);

    private static boolean pointsToDocuments(LlmEngine.Source source) {
        return "document".equals(source.kind())
                || source.url().startsWith("/documents")
                || source.url().startsWith("/api/public/v1/documents/");
    }

    // ————— кнопка —————

    @Test
    void theFindADocumentButtonIsGone() {
        assertThat(ScriptedReplies.prompts(HIDDEN))
                .extracting(ScriptedReplies.Prompt::intent)
                .doesNotContain("document")
                .as("остальные кнопки на месте и в прежнем порядке")
                .containsExactly("equipment", "quote", "service", "human");

        assertThat(ScriptedReplies.prompts(PublicDocuments.SHOWN))
                .as("вернуть кнопку — дело одной настройки")
                .extracting(ScriptedReplies.Prompt::intent)
                .contains("document");
    }

    // Виджет, загруженный до скрытия раздела, ещё может прислать это намерение.
    @Test
    void aStaleDocumentButtonDoesNotGetTheListingPromise() {
        assertThat(vedalina.scripted("document", "public")).isEmpty();
        assertThat(vedalina.scripted("quote", "public"))
                .as("остальные заготовки не тронуты")
                .isPresent();
    }

    // ————— свободный текст —————

    @ParameterizedTest
    @ValueSource(strings = {
            "Найти документ",
            "какие документы у вас есть",
            "Перечисли доступные документы компании VEDAL",
            "Пришли описание VEDAL A-2000 отдельным PDF",
            "Дай PDF на VEDAL Т-100",
            "документы и лицензирование",
    })
    void noAnswerLeadsToDocuments(String question) {
        var reply = vedalina.ask(question, LlmEngine.Scope.PUBLIC, "public");

        assertThat(reply.sources())
                .as("ни ссылки на документ, ни страницы раздела: %s", question)
                .noneMatch(HiddenPublicDocumentsTest::pointsToDocuments);
        assertThat(reply.answer())
                .as("и ни слова о разделе: %s", question)
                .doesNotContain("Документы и лицензирование")
                .doesNotContain("доступен для скачивания")
                .doesNotContain("перечн");
    }

    // Скрытие документов не должно отнять у посетителя всё остальное.
    @Test
    void theCatalogueIsStillAnswered() {
        var reply = vedalina.ask("что такое VEDAL A-2000", LlmEngine.Scope.PUBLIC, "public");

        assertThat(reply.handoff()).isNull();
        assertThat(reply.sources()).extracting(LlmEngine.Source::url)
                .contains("/products/vedal-a-2000/");
    }

    // Скрыт публичный раздел. Сотрудник видит перечень в админке — и его
    // ассистент документы не теряет.
    @Test
    void staffStillGetsTheDocuments() {
        var reply = vedalina.ask("Пришли описание VEDAL A-2000 отдельным PDF",
                LlmEngine.Scope.STAFF, "editor");

        assertThat(reply.sources()).singleElement()
                .satisfies(source -> assertThat(source.kind()).isEqualTo("document"));
    }

    // ————— тексты —————

    @Test
    void theSitePagesDoNotMentionTheSection() {
        var published = pages.published(List.of("VEDAL A-2000 — Инкубатор-трансформер"));

        assertThat(published).extracting(SitePages.Page::url).doesNotContain("/documents/");
        assertThat(published).filteredOn(page -> List.of("home", "news").contains(page.slug()))
                .as("главная и лента разделом документов не заманивают")
                .allSatisfy(page -> assertThat(page.text().toLowerCase()).doesNotContain("документ"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"привет", "добрый день", "спасибо", "ок"})
    void smallTalkDoesNotOfferDocuments(String phrase) {
        assertThat(ScriptedReplies.smallTalk(phrase, HIDDEN)).get().asString()
                .doesNotContain("документ");
    }

    @Test
    void refusalsDoNotPromiseTheListing() {
        var guardrails = new Guardrails(HIDDEN);

        assertThat(guardrails.notFound("подскажите рецепт борща"))
                .doesNotContain("документ")
                .as("умения перечислены по-прежнему")
                .contains("каталог");
        assertThat(guardrails.notFound("recipe for borscht")).doesNotContain("document");
        assertThat(guardrails.notFound("罗宋汤的做法")).doesNotContain("文件清单");

        assertThat(guardrails.refuse("есть ли регистрационное удостоверение")).get()
                .extracting(Guardrails.Refusal::answer).asString()
                .as("статус регистрации по-прежнему не подтверждается")
                .contains("я не подтверждаю")
                .as("но в скрытый раздел не отправляет")
                .doesNotContain("«Документы»")
                .doesNotContain("перечн");
        assertThat(guardrails.refuse("do you have a registration certificate")).get()
                .extracting(Guardrails.Refusal::answer).asString()
                .doesNotContain("«Documents»")
                .doesNotContain("listing");

        assertThat(guardrails.refuse(" ")).get()
                .extracting(Guardrails.Refusal::answer).asString()
                .doesNotContain("документ");
    }

    // ————— модель —————

    private static final Retrieval.Passage PRODUCT = new Retrieval.Passage(
            new LlmEngine.Source("VEDAL A-2000 — Инкубатор-трансформер",
                    "/products/vedal-a-2000/", "product"),
            "Совмещает инкубатор закрытого типа и открытую реанимационную систему.");
    private static final Retrieval.Passage DATASHEET = new Retrieval.Passage(
            new LlmEngine.Source("Датащит VEDAL A-2000",
                    "/api/public/v1/documents/vedal-a-2000-product-sheet/file", "document"),
            "Выдержка из PDF: габариты и масса.");
    // Строка индекса, собранная до скрытия раздела: вид — страница, адрес — раздел.
    private static final Retrieval.Passage SECTION_PAGE = new Retrieval.Passage(
            new LlmEngine.Source("Документы и лицензирование", "/documents/", "page"),
            "В разделе опубликованы каталог, буклет и технические карточки.");

    /** Записывает, что показали модели. */
    private static final class Model implements ChatModel {
        final List<Message> asked = new ArrayList<>();

        @Override
        public String complete(List<Message> messages, java.util.function.Consumer<String> onChunk) {
            asked.addAll(messages);
            onChunk.accept("Ответ [1].");
            return "Ответ [1].";
        }

        String system() {
            return asked.stream().filter(m -> m.role() == Role.SYSTEM)
                    .findFirst().orElseThrow().text();
        }
    }

    // Последний рубеж перед моделью: выдержку из PDF, попавшую в промпт,
    // модель перескажет, и отсев ссылок после ответа её из текста не уберёт.
    @Test
    void theModelNeverSeesAHiddenDocument() {
        var model = new Model();
        var engine = new ModelEngine((question, scope) -> List.of(DATASHEET, SECTION_PAGE, PRODUCT),
                model, false, HIDDEN);

        var answer = engine.answer("габариты A-2000", LlmEngine.Scope.PUBLIC).orElseThrow();

        assertThat(answer.sources()).containsExactly(PRODUCT.source());
        assertThat(model.system())
                .as("материалы пронумерованы заново, с первого оставшегося")
                .contains("[1] VEDAL A-2000")
                .doesNotContain("Датащит")
                .doesNotContain("Выдержка из PDF")
                .doesNotContain("Документы и лицензирование")
                .as("и правила про таблицы PDF в промпте нет")
                .doesNotContain("PDF");
    }

    @Test
    void onlyHiddenDocumentsFoundMeansNothingFound() {
        var model = new Model();
        var engine = new ModelEngine((question, scope) -> List.of(DATASHEET, SECTION_PAGE),
                model, false, HIDDEN);

        assertThat(engine.answer("датащит", LlmEngine.Scope.PUBLIC)).isEmpty();
        assertThat(model.asked).as("за пустой ответ модели не платим").isEmpty();
    }

    @Test
    void staffModelPromptKeepsTheDocuments() {
        var model = new Model();
        var engine = new ModelEngine((question, scope) -> List.of(DATASHEET, PRODUCT),
                model, false, HIDDEN);

        var answer = engine.answer("габариты A-2000", LlmEngine.Scope.STAFF).orElseThrow();

        assertThat(answer.sources()).containsExactly(DATASHEET.source(), PRODUCT.source());
        assertThat(model.system()).contains("Выдержка из PDF").contains("таблицы PDF");
    }

    // ————— правило отсева —————

    @Test
    void whatCountsAsADocument() {
        var file = new LlmEngine.Source("Файл", "/api/public/v1/documents/x/file", "page");
        var product = PRODUCT.source();

        assertThat(HIDDEN.hides(DATASHEET.source(), LlmEngine.Scope.PUBLIC)).isTrue();
        assertThat(HIDDEN.hides(SECTION_PAGE.source(), LlmEngine.Scope.PUBLIC)).isTrue();
        assertThat(HIDDEN.hides(file, LlmEngine.Scope.PUBLIC))
                .as("адрес файла выдаёт документ и под чужим видом")
                .isTrue();
        assertThat(HIDDEN.hides(product, LlmEngine.Scope.PUBLIC)).isFalse();

        assertThat(HIDDEN.hides(DATASHEET.source(), LlmEngine.Scope.STAFF)).isFalse();
        assertThat(PublicDocuments.SHOWN.hides(DATASHEET.source(), LlmEngine.Scope.PUBLIC)).isFalse();
    }
}
