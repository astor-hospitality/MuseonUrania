package ru.vedal.portal.assistant;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Доверие к корневому сертификату, которого нет в хранилище JVM.
 *
 * <p><b>Зачем.</b> Двери GigaChat ({@code gigachat.devices.sberbank.ru},
 * {@code ngw.devices.sberbank.ru}) подписаны цепочкой Минцифры —
 * «Russian Trusted Root CA». В стандартном хранилище доверия JVM
 * (и в образе {@code eclipse-temurin}) этого корня нет, и соединение
 * падает с {@code PKIX path building failed}. Выключить проверку
 * сертификата нельзя: это открыло бы подмену двери, через которую
 * уходят материалы портала и ключ. Вместо этого корень добавляется
 * к доверенным — <i>к</i>, а не <i>вместо</i>: Yandex, Keycloak и всё
 * остальное продолжают проверяться штатным хранилищем.
 *
 * <p><b>Откуда файл.</b> PEM-связка приходит путём в окружении
 * ({@code GIGACHAT_CA_BUNDLE}); в образ она попадает из каталога
 * {@code backend/certs/} — см. README там же. Сам сертификат публичный,
 * секретом не является и в репозитории лежать может.
 */
final class TrustedCertificates {

    private TrustedCertificates() {}

    /**
     * SSL-контекст, которому доверены и штатные корни JVM, и сертификаты
     * из связки.
     *
     * @param bundle путь к PEM-файлу с одним или несколькими сертификатами
     * @throws IllegalStateException если файла нет или в нём не нашлось ни
     *                               одного сертификата — с текстом, по
     *                               которому понятно, что именно не так
     */
    static SSLContext withExtraRoots(Path bundle) {
        var extra = read(bundle);
        try {
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            for (var at = 0; at < extra.size(); at++) {
                store.setCertificateEntry("extra-" + at, extra.get(at));
            }

            var context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {
                    new Either(trustManager(null), trustManager(store))}, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "Не удалось собрать доверие к сертификатам из " + bundle + ": " + e.getMessage(), e);
        }
    }

    /** Сертификаты из PEM-файла. Публичный метод ради теста — проверить разбор без TLS. */
    static List<X509Certificate> read(Path bundle) {
        if (!Files.isRegularFile(bundle)) {
            throw new IllegalStateException(
                    "Связка сертификатов " + bundle + " не найдена. GIGACHAT_CA_BUNDLE должен "
                            + "указывать на PEM-файл с «Russian Trusted Root CA» (Минцифры); "
                            + "откуда его взять и куда положить — backend/certs/README.md "
                            + "и docs/operations/gigachat_activation.md.");
        }
        try (InputStream in = Files.newInputStream(bundle)) {
            // Пустой или чужой файл: CertificateFactory ответил бы «No certificate
            // data found», и по этому тексту не понять, что файл просто не PEM.
            if (!Files.readString(bundle, StandardCharsets.ISO_8859_1).contains("-----BEGIN CERTIFICATE-----")) {
                throw new IllegalStateException(
                        "В связке " + bundle + " нет ни одного сертификата: ожидается PEM "
                                + "с блоками -----BEGIN CERTIFICATE-----.");
            }
            var parsed = new ArrayList<X509Certificate>();
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                parsed.add((X509Certificate) certificate);
            }
            return parsed;
        } catch (IOException | CertificateException e) {
            throw new IllegalStateException(
                    "Связка сертификатов " + bundle + " не читается: " + e.getMessage(), e);
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) return x509;
        }
        throw new IllegalStateException("В JVM нет X509TrustManager");
    }

    /**
     * Доверяет цепочке, если ей доверяет хотя бы одно из двух хранилищ.
     *
     * <p>Сначала штатное, потом связка: так обычный сайт не платит за лишнюю
     * проверку, а ошибка (когда не доверяет никто) приходит от связки —
     * то есть называет ту цепочку, ради которой всё и заведено.
     */
    static final class Either implements X509TrustManager {

        private final X509TrustManager first;
        private final X509TrustManager second;

        Either(X509TrustManager first, X509TrustManager second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                first.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                second.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            var issuers = new ArrayList<>(Arrays.asList(first.getAcceptedIssuers()));
            issuers.addAll(Arrays.asList(second.getAcceptedIssuers()));
            return issuers.toArray(X509Certificate[]::new);
        }
    }
}
