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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MSG91-backed {@link SmsProvider} using the Flow API (DLT-compliant for India).
 * Activated by setting {@code KC_SPI_SMS_PROVIDER=msg91}.
 *
 * <p>Configuration is read from Keycloak SPI config first, then env vars:
 *
 * <ul>
 *   <li>{@code KC_SPI_SMS_MSG91_AUTH_KEY}     / {@code MSG91_AUTH_KEY}     — required</li>
 *   <li>{@code KC_SPI_SMS_MSG91_TEMPLATE_ID}  / {@code MSG91_TEMPLATE_ID}  — required (DLT-approved Flow template id)</li>
 *   <li>{@code KC_SPI_SMS_MSG91_SENDER_ID}    / {@code MSG91_SENDER_ID}    — optional override (else taken from template)</li>
 *   <li>{@code KC_SPI_SMS_MSG91_OTP_VAR_NAME} / {@code MSG91_OTP_VAR_NAME} — template variable receiving the OTP code (default {@code var})</li>
 * </ul>
 *
 * <p>The {@link SmsProvider#send(String, String) send} contract passes the full
 * SMS body (e.g. {@code "Your verification code is: 123456"}). msg91 Flow
 * templates are pre-approved and only accept variable substitutions, so this
 * provider extracts the numeric OTP from the message and posts it under the
 * configured variable name.
 */
public class Msg91SmsProviderFactory implements SmsProviderFactory {

    public static final String PROVIDER_ID = "msg91";

    private static final Logger LOG = Logger.getLogger(Msg91SmsProviderFactory.class);
    private static final String MSG91_FLOW_API = "https://control.msg91.com/api/v5/flow/";
    private static final Pattern OTP_PATTERN = Pattern.compile("(\\d{4,10})");

    private String authKey;
    private String templateId;
    private String senderId;
    private String otpVarName;
    private HttpClient httpClient;

    @Override
    public void init(Config.Scope config) {
        this.authKey    = readConfig(config, "auth-key",      "MSG91_AUTH_KEY");
        this.templateId = readConfig(config, "template-id",   "MSG91_TEMPLATE_ID");
        this.senderId   = readConfig(config, "sender-id",     "MSG91_SENDER_ID");
        this.otpVarName = readConfigOrDefault(config, "otp-var-name", "MSG91_OTP_VAR_NAME", "var");

        if (authKey == null || authKey.isBlank()
                || templateId == null || templateId.isBlank()) {
            LOG.warn("Msg91SmsProvider not fully configured. Set MSG91_AUTH_KEY and "
                    + "MSG91_TEMPLATE_ID (or the equivalent SPI config) before activating "
                    + "provider 'msg91'.");
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
        return new Msg91SmsProvider(httpClient, authKey, templateId, senderId, otpVarName);
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

    private static String readConfigOrDefault(Config.Scope config, String key,
                                              String envName, String defaultValue) {
        String value = readConfig(config, key, envName);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /**
     * Stateless HTTP client that POSTs the msg91 Flow API endpoint.
     */
    static final class Msg91SmsProvider implements SmsProvider {

        private final HttpClient httpClient;
        private final String authKey;
        private final String templateId;
        private final String senderId;
        private final String otpVarName;

        Msg91SmsProvider(HttpClient httpClient, String authKey, String templateId,
                         String senderId, String otpVarName) {
            this.httpClient = httpClient;
            this.authKey = authKey;
            this.templateId = templateId;
            this.senderId = senderId;
            this.otpVarName = otpVarName;
        }

        @Override
        public void send(String phoneNumber, String message) throws SmsException {
            if (authKey == null || authKey.isBlank()
                    || templateId == null || templateId.isBlank()) {
                throw new SmsException("MSG91 provider not configured");
            }
            if (phoneNumber == null || phoneNumber.isBlank()) {
                throw new SmsException("Phone number is empty");
            }
            if (message == null || message.isBlank()) {
                throw new SmsException("Message is empty");
            }

            String normalisedPhone = normalisePhone(phoneNumber);
            String otpCode = extractOtp(message);

            String body = buildJsonBody(normalisedPhone, otpCode);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(MSG91_FLOW_API))
                    .timeout(Duration.ofSeconds(10))
                    .header("authkey", authKey)
                    .header("Content-Type", "application/json")
                    .header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            long startedAt = System.currentTimeMillis();
            try {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                long latencyMs = System.currentTimeMillis() - startedAt;
                String responseBody = resp.body() == null ? "" : resp.body();
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    // No response body on the success path: it echoes request
                    // context back and tells us nothing a 2xx has not already.
                    LOG.infof("MSG91 SMS dispatched: phone=%s status=%d latency_ms=%d",
                            SmsLogSafe.maskPhone(normalisedPhone), resp.statusCode(), latencyMs);
                    return;
                }
                LOG.errorf("MSG91 SMS failed: phone=%s status=%d latency_ms=%d response=%s",
                        SmsLogSafe.maskPhone(normalisedPhone), resp.statusCode(), latencyMs,
                        SmsLogSafe.boundedResponse(responseBody));
                throw new SmsException("MSG91 send failed: HTTP " + resp.statusCode()
                        + " " + SmsLogSafe.boundedResponse(responseBody));
            } catch (java.io.IOException e) {
                long latencyMs = System.currentTimeMillis() - startedAt;
                LOG.errorf(e, "MSG91 SMS IO error: phone=%s latency_ms=%d error=%s",
                        SmsLogSafe.maskPhone(normalisedPhone), latencyMs, e.getMessage());
                throw new SmsException("MSG91 send IO error", e);
            } catch (InterruptedException e) {
                long latencyMs = System.currentTimeMillis() - startedAt;
                Thread.currentThread().interrupt();
                LOG.errorf(e, "MSG91 SMS interrupted: phone=%s latency_ms=%d",
                        SmsLogSafe.maskPhone(normalisedPhone), latencyMs);
                throw new SmsException("MSG91 send interrupted", e);
            }
        }

        @Override
        public void close() {
            // no-op
        }

        String buildJsonBody(String phone, String otpCode) {
            StringBuilder sb = new StringBuilder(192);
            sb.append('{')
                    .append("\"template_id\":\"").append(jsonEscape(templateId)).append("\",")
                    .append("\"short_url\":\"0\",");
            if (senderId != null && !senderId.isBlank()) {
                sb.append("\"sender\":\"").append(jsonEscape(senderId)).append("\",");
            }
            sb.append("\"recipients\":[{")
                    .append("\"mobiles\":\"").append(jsonEscape(phone)).append("\",")
                    .append('"').append(jsonEscape(otpVarName)).append("\":\"")
                    .append(jsonEscape(otpCode)).append("\"")
                    .append("}]}");
            return sb.toString();
        }

        /** msg91 expects numeric mobiles with country code, no leading '+'. */
        static String normalisePhone(String phoneNumber) {
            String trimmed = phoneNumber.trim();
            if (trimmed.startsWith("+")) {
                trimmed = trimmed.substring(1);
            }
            return trimmed.replaceAll("[^0-9]", "");
        }

        /** Extract first 4-10 digit run from the SMS body. */
        static String extractOtp(String message) throws SmsException {
            Matcher m = OTP_PATTERN.matcher(message);
            if (m.find()) {
                return m.group(1);
            }
            throw new SmsException("MSG91: could not extract OTP code from message");
        }

        private static String jsonEscape(String value) {
            StringBuilder out = new StringBuilder(value.length() + 8);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                }
            }
            return out.toString();
        }
    }
}
