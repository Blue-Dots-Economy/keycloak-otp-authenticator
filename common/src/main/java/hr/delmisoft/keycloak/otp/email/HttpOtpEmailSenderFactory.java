package hr.delmisoft.keycloak.otp.email;

import hr.delmisoft.keycloak.otp.ns.NsNotifyClient;
import hr.delmisoft.keycloak.otp.ns.NsNotifyException;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Hands the login OTP email to notification-service {@code POST /v1/notify} as
 * {@code template_key: login_otp}, {@code channel: email}. notification-service owns the
 * copy (per-network catalogue) and the sender identity. Activated with
 * {@code KC_SPI_OTP_EMAIL__PROVIDER=http}; uses the same {@code SMS_HTTP_*} client settings
 * as the SMS {@code http} provider, plus {@code template-id} (default {@code login_otp}) and
 * {@code otp-var-name} (default {@code message}) in this SPI's scope.
 *
 * <p>The address is sent as the account holds it, trimmed; notification-service validates it.
 * Neither the address nor the code is logged or put in an exception message.
 */
public class HttpOtpEmailSenderFactory implements OtpEmailSenderFactory {

    public static final String PROVIDER_ID = "http";
    private static final Logger LOG = Logger.getLogger(HttpOtpEmailSenderFactory.class);

    private NsNotifyClient client;
    private String templateId = "login_otp";
    private String otpVarName = "message";

    @Override
    public void init(Config.Scope config) {
        this.client = NsNotifyClient.fromConfig(config);
        this.templateId = NsNotifyClient.readConfigOrDefault(config, "template-id", "OTP_EMAIL_HTTP_TEMPLATE_ID", "login_otp");
        this.otpVarName = NsNotifyClient.readConfigOrDefault(config, "otp-var-name", "OTP_EMAIL_HTTP_OTP_VAR_NAME", "message");
    }

    @Override
    public OtpEmailSender create(KeycloakSession session) {
        return new HttpOtpEmailSender(client, templateId, otpVarName);
    }

    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
    @Override public String getId() { return PROVIDER_ID; }
    /** Below {@code smtp} (100), so this provider runs only when selected explicitly. */
    @Override public int order() { return 0; }

    static final class HttpOtpEmailSender implements OtpEmailSender {
        private final NsNotifyClient client;
        private final String templateId;
        private final String otpVarName;

        HttpOtpEmailSender(NsNotifyClient client, String templateId, String otpVarName) {
            this.client = client;
            this.templateId = templateId;
            this.otpVarName = otpVarName;
        }

        @Override
        public void send(RealmModel realm, UserModel user, String code) throws OtpEmailException {
            if (client == null || !client.isConfigured()) {
                throw new OtpEmailException(PROVIDER_ID,
                        "HTTP email OTP provider not configured: check SMS_HTTP_URL and SMS_HTTP_SECRET", null);
            }
            String email = user.getEmail() == null ? "" : user.getEmail().trim();
            if (email.isEmpty()) {
                throw new OtpEmailException(PROVIDER_ID, "User has no email address", null);
            }
            try {
                client.send(buildJsonBody(email, code));
                LOG.info("OTP email handed to notification-service");
            } catch (NsNotifyException e) {
                String reason = e.status() > 0
                        ? "HTTP " + e.status()
                        : (e.getCause() != null ? e.getCause() : e).getClass().getSimpleName();
                LOG.errorf("OTP email not accepted by notification-service: reason=%s", reason);
                throw new OtpEmailException(PROVIDER_ID,
                        "notification-service did not accept the OTP email: " + reason, e);
            }
        }

        @Override public void close() { }

        String buildJsonBody(String email, String code) {
            return "{\"template_key\":\"" + NsNotifyClient.json(templateId) + "\","
                    + "\"channel\":\"email\","
                    + "\"to\":{\"email\":\"" + NsNotifyClient.json(email) + "\"},"
                    + "\"variables\":{\"" + NsNotifyClient.json(otpVarName) + "\":\"" + NsNotifyClient.json(code) + "\"},"
                    + "\"priority\":\"urgent\"}";
        }
    }
}
