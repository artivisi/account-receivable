package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArNotificationProperties;
import com.artivisi.accountreceivable.dto.NotificationRequest;
import com.artivisi.accountreceivable.service.notification.TemplateNotificationPayloadMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * A hub validates a message against the variables its template declares and refuses the whole thing
 * if any is missing — in its own log, where the sender never looks. So the mapping is asserted here
 * key for key, through the same binding a deployment goes through: the example file is loaded as
 * configuration, its {@code ${...}} placeholders resolved from the environment.
 *
 * <p>The example is a real hub's variable set; a deployment that copies it keeps its file identical
 * to this one, so what is tested here is what it runs.
 */
class TemplateNotificationPayloadMapperTest {

    private static final String CONTACT_FULL =
            "<ul style='list-style-type:none;'><li>Layanan Keuangan : 021-0000000</li></ul>";

    private static TemplateNotificationPayloadMapper exampleMapper() {
        return new TemplateNotificationPayloadMapper(bindExample(Map.of(
                "AR_NOTIFICATION_BANK_NAME", "Bank Contoh",
                "AR_NOTIFICATION_VA_DISPLAY_PREFIX", "8888001",
                "AR_NOTIFICATION_CONTACT_INFO", "021-0000000",
                "AR_NOTIFICATION_CONTACT_INFO_FULL", CONTACT_FULL)));
    }

    private static ArNotificationProperties.Template bindExample(Map<String, Object> environment) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("deployment", environment));
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("template-example", new ClassPathResource("notification/template-example.yml"));
            sources.forEach(env.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Binder.get(env).bind("ar.notification.template", ArNotificationProperties.Template.class).get();
    }

    private static Map<String, String> billGeneric() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("debtorName", "Budi Santoso");
        g.put("invoiceNumber", "2026010101000001");
        g.put("invoiceType", "Biaya Pendidikan");
        g.put("amount", "833333.00");
        g.put("currency", "IDR");
        g.put("issueDate", "2026-01-01");
        g.put("dueDate", "2026-01-31");
        g.put("vaNumber", "011234567890");
        g.put("escrowCode", "bank-contoh");
        g.put("email", "budi.santoso@example.com");
        g.put("phone", "081200000001");
        return g;
    }

    private static Map<String, String> paymentGeneric() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("debtorName", "Budi Santoso");
        g.put("invoiceNumber", "2026010101000001");
        g.put("invoiceType", "Biaya Pendidikan");
        g.put("invoiceAmount", "833333.00");
        g.put("paymentAmount", "833333.00");
        g.put("currency", "IDR");
        g.put("cumulativePaid", "833333.00");
        g.put("outstanding", "0");
        g.put("paymentReference", "PAY-0001");
        g.put("paidAt", "2026-01-05T01:45:04Z");
        g.put("paidAtLocal", "2026-01-05 08:45:04");
        g.put("issueDate", "2026-01-01");
        g.put("email", "budi.santoso@example.com");
        g.put("phone", "081200000001");
        return g;
    }

    @Test
    void billIssuedIsExactlyTheHubsVariableSet() {
        assertThat(exampleMapper().billIssued(billGeneric())).containsExactly(
                entry("nomorTagihan", "2026010101000001"),
                entry("tanggalTagihan", "2026-01-01"),
                entry("nama", "Budi Santoso"),
                entry("email", "budi.santoso@example.com"),
                entry("noHp", "081200000001"),
                entry("jumlah", "833333.00"),
                entry("rekening", "Bank Contoh 8888001011234567890"),
                entry("rekeningFull", "<ul><li>Bank Contoh 8888001011234567890</li></ul>"),
                entry("keterangan", "Biaya Pendidikan"),
                entry("contactinfo", "021-0000000"),
                entry("contactinfoFull", CONTACT_FULL));
    }

    @Test
    void anAbsentContactIsLeftOutRatherThanSentEmpty() {
        Map<String, String> g = billGeneric();
        g.remove("email");
        g.put("phone", " ");

        assertThat(exampleMapper().billIssued(g)).doesNotContainKeys("email", "noHp")
                .containsKey("nama");
    }

    @Test
    void paymentReceivedIsExactlyTheHubsVariableSet() {
        assertThat(exampleMapper().paymentReceived(paymentGeneric())).containsExactly(
                entry("nomorTagihan", "2026010101000001"),
                entry("tanggalTagihan", "2026-01-01"),
                entry("nama", "Budi Santoso"),
                entry("nilaiTagihan", "833333.00"),
                entry("nilaiPembayaran", "833333.00"),
                entry("noHp", "081200000001"),
                entry("rekening", "Bank Contoh"),
                entry("keterangan", "Biaya Pendidikan"),
                entry("contactinfo", "021-0000000"),
                entry("contactinfoFull", CONTACT_FULL),
                entry("waktu", "2026-01-05 08:45:04"),
                entry("referensi", "PAY-0001"));
    }

    @Test
    void dunningPassesThroughUnchanged() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("debtorName", "Budi Santoso");
        g.put("invoiceNumber", "2026010101000001");
        g.put("currency", "IDR");
        g.put("amountOutstanding", "833333.00");
        g.put("dueDate", "2026-01-31");
        g.put("daysOverdue", "4");

        assertThat(exampleMapper().dunning(g)).isEqualTo(g);
    }

    @Test
    void envelopeUsesTheHubsFieldNames() {
        Map<String, Object> envelope = exampleMapper().envelope(new NotificationRequest(
                "tagihan", "a@example.test", null, Map.of("nama", "Budi Santoso")));

        assertThat(envelope).containsExactly(
                entry("konfigurasi", "tagihan"),
                entry("email", "a@example.test"),
                entry("mobile", null),
                entry("data", Map.of("nama", "Budi Santoso")));
    }

    @Test
    void anUnsetDeploymentValueFailsStartup() {
        assertThatThrownBy(() -> bindExample(Map.of(
                "AR_NOTIFICATION_BANK_NAME", "Bank Contoh",
                "AR_NOTIFICATION_VA_DISPLAY_PREFIX", "8888001",
                "AR_NOTIFICATION_CONTACT_INFO", "021-0000000")))
                .hasStackTraceContaining("unresolved placeholder: ${AR_NOTIFICATION_CONTACT_INFO_FULL}");
    }

    @Test
    void aBlankDeploymentValueFailsStartup() {
        assertThatThrownBy(() -> bindExample(Map.of(
                "AR_NOTIFICATION_BANK_NAME", "Bank Contoh",
                "AR_NOTIFICATION_VA_DISPLAY_PREFIX", "8888001",
                "AR_NOTIFICATION_CONTACT_INFO", " ",
                "AR_NOTIFICATION_CONTACT_INFO_FULL", CONTACT_FULL)))
                .hasStackTraceContaining("contactinfo is blank");
    }

    @Test
    void aReferenceAREventDoesNotCarryFailsStartup() {
        ArNotificationProperties.Template template = new ArNotificationProperties.Template(
                new ArNotificationProperties.Envelope("konfigurasi", "email", "mobile", "data"),
                Map.of("nama", "{debtorNmae}"),
                Map.of("nama", "{debtorName}"),
                Map.of("nama", "{debtorName}"));

        assertThatThrownBy(() -> new TemplateNotificationPayloadMapper(template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bill-issued.nama references {debtorNmae}");
    }

    @Test
    void aMissingEventFailsStartup() {
        assertThatThrownBy(() -> new ArNotificationProperties.Template(
                new ArNotificationProperties.Envelope("konfigurasi", "email", "mobile", "data"),
                Map.of("nama", "{debtorName}"),
                Map.of("nama", "{debtorName}"),
                Map.of()))
                .hasMessageContaining("ar.notification.template.dunning is required");
    }
}
