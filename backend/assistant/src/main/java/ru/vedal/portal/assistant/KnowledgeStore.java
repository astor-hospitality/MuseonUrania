package ru.vedal.portal.assistant;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.regex.Pattern;

/**
 * Сверка индекса с моделью: размерность колонки обязана совпасть с моделью.
 *
 * <p><b>Зачем проверять на старте.</b> Колонка {@code knowledge_chunk.embedding}
 * создана как {@code vector(256)} — под эмбеддинги Яндекса. У GigaChat
 * вектор длиннее, и портал, поднявшийся с ним поверх старой колонки,
 * работал бы ровно до первой индексации: вопрос посетителя ищется
 * по старым чанкам (их модель другая, {@link VectorSearch} их не берёт,
 * выдача пуста — и это тихо), а индексация падает ошибкой SQL
 * «expected 256 dimensions, not 1024» — то есть в месте, где про модели
 * ничего не известно. Лучше отказ на старте с текстом, что делать.
 *
 * <p><b>Что делать, сказано в тексте ошибки:</b> смена провайдера
 * эмбеддингов — это миграция колонки под новую размерность и полная
 * переиндексация корпуса (старые векторы в новую колонку не влезут и
 * сравнивать их всё равно нечем). Порядок — в
 * {@code docs/operations/gigachat_activation.md}.
 */
final class KnowledgeStore {

    private static final Pattern VECTOR = Pattern.compile("vector\\((\\d+)\\)");

    private KnowledgeStore() {}

    /** Размерность колонки {@code knowledge_chunk.embedding}, как её видит база. */
    static int columnDimension(JdbcClient jdbc) {
        var type = jdbc.sql("""
                        select format_type(a.atttypid, a.atttypmod)
                        from pg_attribute a
                        where a.attrelid = 'knowledge_chunk'::regclass
                          and a.attname = 'embedding'
                        """)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "В базе нет колонки knowledge_chunk.embedding: миграция V34 не накатана?"));
        var matcher = VECTOR.matcher(type);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Колонка knowledge_chunk.embedding имеет тип " + type
                            + ", а ожидается vector(<размерность>)");
        }
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Отказ, если модель и колонка расходятся по размерности.
     *
     * @throws IllegalStateException с текстом, называющим обе размерности
     *                               и порядок действий
     */
    static void ensureFits(JdbcClient jdbc, Embeddings embeddings) {
        var column = columnDimension(jdbc);
        if (column == embeddings.dimension()) return;
        throw new IllegalStateException(
                "Модель эмбеддингов " + embeddings.name() + " даёт векторы длины "
                        + embeddings.dimension() + ", а колонка knowledge_chunk.embedding создана как vector("
                        + column + "). Смена провайдера эмбеддингов — это миграция колонки под новую "
                        + "размерность и полная переиндексация корпуса; порядок — в "
                        + "docs/operations/gigachat_activation.md (раздел «Переиндексация»). "
                        + "Чтобы не менять индекс, оставьте VEDAL_RAG_PROVIDER=yandex или "
                        + "выключите RAG: VEDAL_RAG_ENABLED=false.");
    }
}
