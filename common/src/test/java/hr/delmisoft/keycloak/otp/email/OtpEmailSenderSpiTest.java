package hr.delmisoft.keycloak.otp.email;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

class OtpEmailSenderSpiTest {
    @Test
    void spiShape() {
        OtpEmailSenderSpi spi = new OtpEmailSenderSpi();
        assertThat(spi.getName(), equalTo("otp-email"));
        assertThat(spi.isInternal(), is(false));
        assertThat(spi.getProviderClass(), equalTo(OtpEmailSender.class));
        assertThat(spi.getProviderFactoryClass(), equalTo(OtpEmailSenderFactory.class));
    }

    @Test
    void smtpIsDefaultByOrder() {
        assertThat(new SmtpOtpEmailSenderFactory().order(),
                greaterThan(new HttpOtpEmailSenderFactory().order()));
    }

    @Test
    void registeredInServiceFiles() throws Exception {
        String spis = read("/META-INF/services/org.keycloak.provider.Spi");
        assertThat(spis, containsString(OtpEmailSenderSpi.class.getName()));
        String factories = read("/META-INF/services/" + OtpEmailSenderFactory.class.getName());
        assertThat(factories, containsString(SmtpOtpEmailSenderFactory.class.getName()));
        assertThat(factories, containsString(HttpOtpEmailSenderFactory.class.getName()));
    }

    private String read(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
