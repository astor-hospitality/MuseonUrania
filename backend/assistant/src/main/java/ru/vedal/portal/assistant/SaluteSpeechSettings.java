package ru.vedal.portal.assistant;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Настройки SaluteSpeech (Сбер) в одном месте — по образцу
 * {@link GigaChatSettings}.
 *
 * <p>Проверяются не при создании, а в {@link #textToSpeech}: только когда
 * озвучивание переведено на Сбер ({@code VEDAL_TTS_PROVIDER=salute}).
 * При умолчании {@code yandex} пустой ключ Сбера — не ошибка.
 *
 * <p><b>Ключ — свой, не от GigaChat.</b> OAuth-дверь у обоих сервисов
 * одна ({@code ngw.devices.sberbank.ru:9443}), но проект в кабинете
 * developers.sber.ru — отдельный («SaluteSpeech API»), ключ авторизации
 * отдельный и область доступа своя: {@code SALUTE_SPEECH_PERS},
 * {@code SALUTE_SPEECH_B2B}, {@code SALUTE_SPEECH_CORP}. Ключ GigaChat
 * с областью GigaChat дверь SaluteSpeech не пустит.
 *
 * <p><b>Сертификат — тот же.</b> Двери подписаны тем же корнем Минцифры,
 * что и GigaChat, поэтому {@code SALUTE_CA_BUNDLE} по умолчанию берётся
 * из {@code GIGACHAT_CA_BUNDLE}: один файл в образе, одна переменная.
 *
 * @param authKey  Authorization key проекта SaluteSpeech ({@code SALUTE_AUTH_KEY})
 * @param scope    область доступа ({@code SALUTE_SCOPE})
 * @param voice    голос: {@code Nec_24000} (Наталья), {@code Bys_24000} (Борис),
 *                 {@code May_24000}, {@code Tur_24000}, {@code Ost_24000},
 *                 {@code Pon_24000}; суффикс — частота, есть варианты {@code _8000}
 * @param format   формат ответа двери: {@code wav16} (WAV с заголовком) или
 *                 {@code pcm16} (сырой PCM, заголовок допишет портал)
 * @param authUrl  OAuth-дверь (настройкой — ради теста)
 * @param apiUrl   корень REST API, без завершающей косой черты
 * @param caBundle путь к PEM с «Russian Trusted Root CA»; пусто — только
 *                 штатное хранилище JVM
 */
public record SaluteSpeechSettings(
        String authKey,
        String scope,
        String voice,
        String format,
        String authUrl,
        String apiUrl,
        String caBundle) {

    public static final String PROVIDER = "salute";
    public static final String CLOUD_API = "https://smartspeech.sber.ru";
    public static final String DEFAULT_SCOPE = "SALUTE_SPEECH_PERS";
    public static final String DEFAULT_VOICE = "Nec_24000";
    public static final String DEFAULT_FORMAT = SaluteSpeechTextToSpeech.WAV16;

    private static final Set<String> SCOPES =
            Set.of("SALUTE_SPEECH_PERS", "SALUTE_SPEECH_B2B", "SALUTE_SPEECH_CORP");

    /**
     * Озвучивание через SaluteSpeech — с проверкой всего нужного.
     * Отказ здесь — на старте, с именами переменных, а не 503 при первой
     * просьбе посетителя озвучить ответ.
     */
    public SaluteSpeechTextToSpeech textToSpeech(ObjectMapper json, Duration timeout) {
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalStateException("""
                    VEDAL_TTS_PROVIDER=salute, но доступ к SaluteSpeech не задан. Нужен \
                    SALUTE_AUTH_KEY — Authorization key из кабинета developers.sber.ru \
                    (проект → SaluteSpeech API → «Ключ авторизации»). Это отдельный \
                    проект и отдельный ключ, не GIGACHAT_AUTH_KEY. Чтобы озвучивать \
                    по-прежнему Яндексом, поставьте VEDAL_TTS_PROVIDER=yandex.""");
        }
        var basic = authKey.strip();
        if (!basic.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            throw new IllegalStateException(
                    "SALUTE_AUTH_KEY содержит пробелы или не-ASCII символы. Ключ — base64, "
                            + "то есть латиница, цифры, «+», «/» и «=»: похоже, при копировании "
                            + "из кабинета прихватилось лишнее.");
        }
        if (scope == null || !SCOPES.contains(scope.strip())) {
            throw new IllegalStateException(
                    "SALUTE_SCOPE «" + scope + "» не из списка SALUTE_SPEECH_PERS, "
                            + "SALUTE_SPEECH_B2B, SALUTE_SPEECH_CORP; какая — зависит от договора "
                            + "с Сбером. Области GIGACHAT_API_* сюда не подходят.");
        }
        if (voice == null || voice.isBlank()) {
            throw new IllegalStateException(
                    "SALUTE_TTS_VOICE пуст: ожидается голос вида Nec_24000, Bys_24000, "
                            + "May_24000, Tur_24000, Ost_24000 или Pon_24000.");
        }
        var kind = format == null ? "" : format.strip().toLowerCase(Locale.ROOT);
        if (!kind.equals(SaluteSpeechTextToSpeech.WAV16) && !kind.equals(SaluteSpeechTextToSpeech.PCM16)) {
            throw new IllegalStateException(
                    "SALUTE_TTS_FORMAT «" + format + "» не поддержан: дверь /voice/synthesize "
                            + "отдаёт audio/wav, поэтому допустимы wav16 (умолчание) и pcm16. "
                            + "opus потребовал бы другого типа ответа и не включён.");
        }

        var http = HttpClient.newBuilder().connectTimeout(timeout);
        if (caBundle != null && !caBundle.isBlank()) {
            http.sslContext(TrustedCertificates.withExtraRoots(Path.of(caBundle.strip())));
        }
        var auth = new GigaChatAuth(http.build(), URI.create(authUrl), json, basic, scope.strip(), timeout);
        return new SaluteSpeechTextToSpeech(auth, synthesizeUrl(), voice.strip(), kind, timeout);
    }

    public URI synthesizeUrl() {
        var root = apiUrl == null || apiUrl.isBlank() ? CLOUD_API : apiUrl.strip();
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return URI.create(root + SaluteSpeechTextToSpeech.PATH);
    }
}
