package ru.vedal.portal.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Доверие к корню Минцифры — <i>в дополнение</i> к штатным корням JVM.
 *
 * <p>Сертификат для теста берётся из хранилища самой JVM и пишется в PEM:
 * настоящий корень Сбера в тесте не нужен, а подделывать его нечем. Важно
 * устройство: связка читается, корни из неё попадают в доверенные,
 * а штатные при этом не пропадают.
 */
class TrustedCertificatesTest {

    @TempDir
    Path dir;

    private static X509Certificate someJvmRoot() throws Exception {
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        var manager = (X509TrustManager) factory.getTrustManagers()[0];
        return manager.getAcceptedIssuers()[0];
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    @Test
    void certificatesFromThePemBundleAreRead() throws Exception {
        var root = someJvmRoot();
        var bundle = dir.resolve("russian_trusted_root_ca.pem");
        Files.writeString(bundle, "# комментарий перед сертификатом\n" + pem(root) + pem(root));

        var read = TrustedCertificates.read(bundle);

        assertThat(read).hasSize(2);
        assertThat(read.getFirst().getSubjectX500Principal()).isEqualTo(root.getSubjectX500Principal());
    }

    // Главное: связка ДОБАВЛЯЕТСЯ к штатным корням, а не заменяет их —
    // иначе вместе с Сбером перестали бы проверяться Яндекс и Keycloak.
    @Test
    void theContextTrustsBothTheJvmRootsAndTheBundle() throws Exception {
        var root = someJvmRoot();
        var bundle = dir.resolve("bundle.pem");
        Files.writeString(bundle, pem(root));

        var context = TrustedCertificates.withExtraRoots(bundle);
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        var jvmRoots = ((X509TrustManager) factory.getTrustManagers()[0]).getAcceptedIssuers().length;

        assertThat(context).isNotNull();
        var either = new TrustedCertificates.Either(
                (X509TrustManager) factory.getTrustManagers()[0],
                (X509TrustManager) trustOnly(bundle).getTrustManagers()[0]);
        assertThat(either.getAcceptedIssuers()).hasSize(jvmRoots + 1);
    }

    private static TrustManagerFactory trustOnly(Path bundle) throws Exception {
        var store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        store.load(null, null);
        var certificates = TrustedCertificates.read(bundle);
        for (var at = 0; at < certificates.size(); at++) {
            store.setCertificateEntry("c" + at, certificates.get(at));
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return factory;
    }

    // Нет файла — отказ на старте с именем переменной и указанием, где взять.
    @Test
    void aMissingBundleIsRefusedWithDirections() {
        assertThatThrownBy(() -> TrustedCertificates.withExtraRoots(dir.resolve("нет-такого.pem")))
                .hasMessageContaining("GIGACHAT_CA_BUNDLE")
                .hasMessageContaining("Russian Trusted Root CA")
                .hasMessageContaining("backend/certs/README.md");
    }

    @Test
    void aBundleWithoutCertificatesIsRefused() throws Exception {
        var empty = dir.resolve("empty.pem");
        Files.writeString(empty, "# тут ничего нет\n");

        assertThatThrownBy(() -> TrustedCertificates.withExtraRoots(empty))
                .hasMessageContaining("нет ни одного сертификата");
    }
}
