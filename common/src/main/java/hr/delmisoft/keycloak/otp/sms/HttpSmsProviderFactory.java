package hr.delmisoft.keycloak.otp.sms;

import hr.delmisoft.keycloak.otp.identifier.IdentifierUtil;
import hr.delmisoft.keycloak.otp.identifier.PhoneNumberInvalidException;
import hr.delmisoft.keycloak.otp.ns.NsNotifyClient;
import hr.delmisoft.keycloak.otp.ns.NsNotifyException;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link SmsProvider} that hands the login OTP to notification-service
 * {@code POST /v1/notify} as {@code template_key: login_otp}, signed with HMAC v2.
 * notification-service owns the SMS vendor, its template id and the DLT-approved text,
 * so only the code is sent. Activated with {@code KC_SPI_SMS_PROVIDER=http}.
 *
 * <p>Settings (SPI config first, then env): the notification-service client's
 * ({@link NsNotifyClient}: {@code SMS_HTTP_URL}, {@code SMS_HTTP_SECRET},
 * {@code SMS_HTTP_KEY_ID}, {@code SMS_HTTP_TIMEOUT_MS}), plus
 * {@code template-id}/{@code SMS_HTTP_TEMPLATE_ID} (default {@code login_otp}) and
 * {@code otp-var-name}/{@code SMS_HTTP_OTP_VAR_NAME} (default {@code message}).
 *
 * <p>The phone is sent in E.164, canonicalised with the realm's default region.
 */
public class HttpSmsProviderFactory implements SmsProviderFactory {

    public static final String PROVIDER_ID = "http";

    private static final Logger LOG = Logger.getLogger(HttpSmsProviderFactory.class);
    private static final Pattern OTP_PATTERN = Pattern.compile("(\\d{4,10})");
    private static final String DEFAULT_TEMPLATE_ID = "login_otp";
    private static final String DEFAULT_OTP_VAR_NAME = "message";

    private NsNotifyClient client;
    private String templateId;
    private String otpVarName;

    @Override
    public void init(Config.Scope config) {
        this.client = NsNotifyClient.fromConfig(config);
        this.templateId = NsNotifyClient.readConfigOrDefault(config, "template-id", "SMS_HTTP_TEMPLATE_ID", DEFAULT_TEMPLATE_ID);
        this.otpVarName = NsNotifyClient.readConfigOrDefault(config, "otp-var-name", "SMS_HTTP_OTP_VAR_NAME", DEFAULT_OTP_VAR_NAME);
    }

    @Override
    public SmsProvider create(KeycloakSession session) {
        return new HttpSmsProvider(session, client, templateId, otpVarName);
    }

    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
    @Override public String getId() { return PROVIDER_ID; }

    static final class HttpSmsProvider implements SmsProvider {

        private final KeycloakSession session;
        private final NsNotifyClient client;
        private final String templateId;
        private final String otpVarName;

        HttpSmsProvider(KeycloakSession session, NsNotifyClient client, String templateId, String otpVarName) {
            this.session = session;
            this.client = client;
            this.templateId = templateId;
            this.otpVarName = otpVarName;
        }

        @Override
        public void send(String phoneNumber, String message) throws SmsException {
            if (client == null || !client.isConfigured()) {
                throw new SmsException("HTTP SMS provider not configured: check SMS_HTTP_URL and SMS_HTTP_SECRET");
            }
            if (phoneNumber == null || phoneNumber.isBlank()) throw new SmsException("Phone number is empty");
            if (message == null || message.isBlank()) throw new SmsException("Message is empty");

            String otpCode = extractOtp(message);
            String e164;
            try {
                e164 = IdentifierUtil.canonicalize(session, phoneNumber);
            } catch (PhoneNumberInvalidException e) {
                LOG.warnf("OTP SMS not sent: phone=%s cannot be expressed in E.164", SmsLogSafe.maskPhone(phoneNumber));
                throw new SmsException("Phone number cannot be expressed in E.164");
            }

            try {
                client.send(buildJsonBody(e164, otpCode));
                LOG.infof("OTP SMS handed to notification-service: phone=%s", SmsLogSafe.maskPhone(e164));
            } catch (NsNotifyException e) {
                LOG.errorf("OTP SMS not accepted by notification-service: phone=%s status=%d %s",
                        SmsLogSafe.maskPhone(e164), e.status(), e.getMessage());
                throw new SmsException(e.getMessage(), e);
            }
        }

        @Override public void close() { }

        String buildJsonBody(String e164Phone, String otpCode) {
            return "{\"template_key\":\"" + NsNotifyClient.json(templateId) + "\","
                    + "\"channel\":\"sms\","
                    + "\"to\":{\"phone\":\"" + NsNotifyClient.json(e164Phone) + "\"},"
                    + "\"variables\":{\"" + NsNotifyClient.json(otpVarName) + "\":\"" + NsNotifyClient.json(otpCode) + "\"},"
                    + "\"priority\":\"urgent\"}";
        }

        /**
         * First 4-10 digit run of the rendered SMS body. Only the code is forwarded:
         * the delivered text is the DLT-registered template notification-service holds.
         */
        static String extractOtp(String message) throws SmsException {
            Matcher m = OTP_PATTERN.matcher(message);
            if (m.find()) return m.group(1);
            throw new SmsException("HTTP SMS provider: could not extract OTP code from message");
        }
    }
}
