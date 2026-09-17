package hr.delmisoft.keycloak.otp.sms;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Twilio-backed {@link SmsProvider}. Activated by setting the runtime SPI
 * configuration to this factory's id ({@code "twilio"}), e.g. via the
 * Keycloak environment variable {@code KC_SPI_SMS_PROVIDER=twilio}.
 *
 * <p>Configuration is read from the Keycloak SPI config (preferred) and
 * falls back to environment variables so the same JAR can be wired with
 * {@code --spi-sms-twilio-account-sid=...} flags or {@code TWILIO_*} env vars.
 *
 * <ul>
 *   <li>{@code KC_SPI_SMS_TWILIO_ACCOUNT_SID} / {@code TWILIO_ACCOUNT_SID}</li>
 *   <li>{@code KC_SPI_SMS_TWILIO_AUTH_TOKEN}  / {@code TWILIO_AUTH_TOKEN}</li>
 *   <li>{@code KC_SPI_SMS_TWILIO_FROM_NUMBER} / {@code TWILIO_FROM_NUMBER}</li>
 * </ul>
 *
 * <p>The provider is intentionally swappable. To switch off Twilio, set
 * {@code KC_SPI_SMS_PROVIDER} back to {@code log} (or to whichever new
 * provider id ships next) and restart Keycloak — no code change required.
 */
public class TwilioSmsProviderFactory implements SmsProviderFactory {

    public static final String PROVIDER_ID = "twilio";

    private static final Logger LOG = Logger.getLogger(TwilioSmsProviderFactory.class);
    private static final String TWILIO_API = "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private String accountSid;
    private String authToken;
    private String fromNumber;
    private HttpClient httpClient;

    @Override
    public void init(Config.Scope config) {
        this.accountSid = readConfig(config, "account-sid", "TWILIO_ACCOUNT_SID");
        this.authToken  = readConfig(config, "auth-token",  "TWILIO_AUTH_TOKEN");
        this.fromNumber = readConfig(config, "from-number", "TWILIO_FROM_NUMBER");

        if (accountSid == null || accountSid.isBlank()
                || authToken == null || authToken.isBlank()
                || fromNumber == null || fromNumber.isBlank()) {
            LOG.warn("TwilioSmsProvider not fully configured. Set TWILIO_ACCOUNT_SID, "
                    + "TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER (or the equivalent SPI config) "
                    + "before activating provider 'twilio'.");
        }

        // Redirects are deliberately not followed: HttpClient's default policy is
        // NEVER unless followRedirects() is set. A 3xx from an SMS gateway would
        // otherwise replay the auth headers and the OTP to whatever host it names.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public SmsProvider create(KeycloakSession session) {
        return new TwilioSmsProvider(httpClient, accountSid, authToken, fromNumber);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // HttpClient holds no resources we need to release here
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    private static String readConfig(Config.Scope config, String key, String envName) {
        if (config != null) {
            String fromConfig = config.get(key);
            if (fromConfig != null && !fromConfig.isBlank()) {
                return fromConfig;
            }
        }
        return System.getenv(envName);
    }

    /**
     * Stateless HTTP client that POSTs the Twilio Messages REST endpoint.
     * Kept package-private and inner so the only public surface is the
     * factory.
     */
    static final class TwilioSmsProvider implements SmsProvider {

        private final HttpClient httpClient;
        private final String accountSid;
        private final String authToken;
        private final String fromNumber;

        TwilioSmsProvider(HttpClient httpClient, String accountSid, String authToken, String fromNumber) {
            this.httpClient = httpClient;
            this.accountSid = accountSid;
            this.authToken = authToken;
            this.fromNumber = fromNumber;
        }

        @Override
        public void send(String phoneNumber, String message) throws SmsException {
            if (accountSid == null || authToken == null || fromNumber == null) {
                throw new SmsException("Twilio provider not configured");
            }
            if (phoneNumber == null || phoneNumber.isBlank()) {
                throw new SmsException("Phone number is empty");
            }
            if (message == null || message.isBlank()) {
                throw new SmsException("Message is empty");
            }

            String form = "To=" + urlEncode(phoneNumber)
                    + "&From=" + urlEncode(fromNumber)
                    + "&Body=" + urlEncode(message);

            String basicAuth = Base64.getEncoder()
                    .encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(TWILIO_API, accountSid)))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    LOG.debugf("Twilio SMS dispatched to %s (status=%d)",
                            SmsLogSafe.maskPhone(phoneNumber), resp.statusCode());
                    return;
                }
                throw new SmsException("Twilio send failed: HTTP " + resp.statusCode() + " " + resp.body());
            } catch (java.io.IOException e) {
                throw new SmsException("Twilio send IO error", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SmsException("Twilio send interrupted", e);
            }
        }

        @Override
        public void close() {
            // no-op
        }

        private static String urlEncode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
