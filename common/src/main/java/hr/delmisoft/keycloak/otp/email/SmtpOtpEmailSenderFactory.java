package hr.delmisoft.keycloak.otp.email;

import hr.delmisoft.keycloak.otp.EmailOtpConst;
import org.keycloak.Config;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.HashMap;
import java.util.Map;

/** Today's path: Keycloak's own SMTP and the realm's email theme ({@code email-otp-code.ftl}). The default. */
public class SmtpOtpEmailSenderFactory implements OtpEmailSenderFactory {

    public static final String PROVIDER_ID = "smtp";

    @Override
    public OtpEmailSender create(KeycloakSession session) {
        return new OtpEmailSender() {
            @Override
            public void send(RealmModel realm, UserModel user, String code) throws OtpEmailException {
                try {
                    session.getProvider(EmailTemplateProvider.class)
                            .setRealm(realm)
                            .setUser(user)
                            .send(EmailOtpConst.EMAIL_SUBJECT_KEY, EmailOtpConst.EMAIL_TEMPLATE,
                                    new HashMap<>(Map.of("code", code)));
                } catch (EmailException e) {
                    throw new OtpEmailException(PROVIDER_ID, "Failed to send OTP email over SMTP", e);
                }
            }

            @Override public void close() { }
        };
    }

    @Override public void init(Config.Scope config) { }
    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
    @Override public String getId() { return PROVIDER_ID; }
    /** Highest order wins when KC_SPI_OTP_EMAIL__PROVIDER is unset, so SMTP stays the default. */
    @Override public int order() { return 100; }
}
