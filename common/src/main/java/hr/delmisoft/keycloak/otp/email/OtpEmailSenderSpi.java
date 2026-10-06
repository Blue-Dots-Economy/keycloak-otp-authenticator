package hr.delmisoft.keycloak.otp.email;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/** SPI {@code otp-email}; choose the provider with {@code KC_SPI_OTP_EMAIL__PROVIDER} (default {@code smtp}). */
public class OtpEmailSenderSpi implements Spi {
    @Override public boolean isInternal() { return false; }
    @Override public String getName() { return "otp-email"; }
    @Override public Class<? extends Provider> getProviderClass() { return OtpEmailSender.class; }
    @Override public Class<? extends ProviderFactory> getProviderFactoryClass() { return OtpEmailSenderFactory.class; }
}
