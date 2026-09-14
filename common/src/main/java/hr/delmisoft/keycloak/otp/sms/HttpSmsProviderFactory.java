package hr.delmisoft.keycloak.otp.sms;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vendor-neutral {@link SmsProvider} that hands the OTP to the Blue Dots
 * notification service instead of calling an SMS vendor directly.
 * Activated by setting {@code KC_SPI_SMS_PROVIDER=http}.
 *
 * <p><b>Why this exists.</b> Every other provider here ({@code msg91},
 * {@code twilio}, {@code sns}) teaches Keycloak the name of one vendor. Adding
 * the next vendor then costs a Java change, a jar rebuild, a Keycloak image
 * build, an image tag pin and a chart change — repeated per vendor, forever.
 * This provider pays that once: notification-service owns the vendor, so a new
 * SMS vendor becomes a change in one service and nothing here moves.
 *
 * <p>It also removes a duplicated setting. With {@code msg91} the login-OTP
 * template id is configured twice, once here and once in notification-service.
 * Here the template is named ({@code login_otp} by default) and the notification
 * service owns both its vendor-side id and its message text, so there is one
 * copy of each.
 *
 * <p><b>What is sent.</b> The {@link SmsProvider#send(String, String) send}
 * contract passes a rendered SMS body, but this provider forwards only the OTP
 * code, under the configured variable name. That is deliberate: under Indian DLT
 * rules the delivered text must match the template registered with the operator,
 * so the authoritative copy is the one notification-service holds, not the one
 * rendered from this plugin's theme messages.
 *
 * <p><b>Configuration</b> — SPI config first, then env var:
 *
 * <ul>
 *   <li>{@code KC_SPI_SMS_HTTP_URL}          / {@code SMS_HTTP_URL}          — required, the full /notify URL</li>
 *   <li>{@code KC_SPI_SMS_HTTP_KEY_ID}       / {@code SMS_HTTP_KEY_ID}       — required, HMAC key id (default {@code keycloak})</li>
 *   <li>{@code KC_SPI_SMS_HTTP_SECRET}       / {@code SMS_HTTP_SECRET}       — required, HMAC shared secret</li>
 *   <li>{@code KC_SPI_SMS_HTTP_TEMPLATE_ID}  / {@code SMS_HTTP_TEMPLATE_ID}  — template key (default {@code login_otp})</li>
 *   <li>{@code KC_SPI_SMS_HTTP_OTP_VAR_NAME} / {@code SMS_HTTP_OTP_VAR_NAME} — variable carrying the code (default {@code message})</li>
 *   <li>{@code KC_SPI_SMS_HTTP_TIMEOUT_MS}   / {@code SMS_HTTP_TIMEOUT_MS}   — request timeout (default 5000)</li>
 * </ul>
 *
 * <p><b>Trade-off.</b> Login OTP gains a hard dependency on notification-service
 * being reachable. The timeout is deliberately short, because this call sits
 * inside an interactive login.
 */
public class HttpSmsProviderFactory implements SmsProviderFactory {

    public static final String PROVIDER_ID = "http";

    private static final Logger LOG = Logger.getLogger(HttpSmsProviderFactory.class);
    private static final Pattern OTP_PATTERN = Pattern.compile("(\\d{4,10})");
    private static final String DEFAULT_TEMPLATE_ID = "login_otp";
    private static final String DEFAULT_OTP_VAR_NAME = "message";
    private static final String DEFAULT_KEY_ID = "keycloak";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private String url;
    private String keyId;
    private String secret;
    private String templateId;
    private String otpVarName;
    private long timeoutMs;
    private HttpClient httpClient;

    @Override
    public void init(Config.Scope config) {
        this.url        = readConfig(config, "url", "SMS_HTTP_URL");
        this.secret     = readConfig(config, "secret", "SMS_HTTP_SECRET");
        this.keyId      = readConfigOrDefault(config, "key-id", "SMS_HTTP_KEY_ID", DEFAULT_KEY_ID);
        this.templateId = readConfigOrDefault(config, "template-id", "SMS_HTTP_TEMPLATE_ID", DEFAULT_TEMPLATE_ID);
        this.otpVarName = readConfigOrDefault(config, "otp-var-name", "SMS_HTTP_OTP_VAR_NAME", DEFAULT_OTP_VAR_NAME);
        this.timeoutMs  = parseTimeout(readConfig(config, "timeout-ms", "SMS_HTTP_TIMEOUT_MS"));

        if (url == null || url.isBlank() || secret == null || secret.isBlank()) {
            LOG.warn("HttpSmsProvider not fully configured. Set SMS_HTTP_URL and SMS_HTTP_SECRET "
                    + "(or the equivalent SPI config) before activating provider 'http'.");
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public SmsProvider create(KeycloakSession session) {
        return new HttpSmsProvider(httpClient, url, keyId, secret, templateId, otpVarName, timeoutMs);
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

    static long parseTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT_MS;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            // A zero or negative timeout means "wait forever" to HttpClient, which
            // inside an interactive login is worse than any misconfiguration it
            // could be covering for.
            return parsed > 0 ? parsed : DEFAULT_TIMEOUT_MS;
        } catch (NumberFormatException e) {
            LOG.warnf("Invalid SMS_HTTP_TIMEOUT_MS '%s', using %d", raw, DEFAULT_TIMEOUT_MS);
            return DEFAULT_TIMEOUT_MS;
        }
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
     * Stateless client that POSTs notification-service {@code /notify}.
     */
    static final class HttpSmsProvider implements SmsProvider {

        private static final SecureRandom RANDOM = new SecureRandom();

        private final HttpClient httpClient;
        private final String url;
        private final String keyId;
        private final String secret;
        private final String templateId;
        private final String otpVarName;
        private final long timeoutMs;

        HttpSmsProvider(HttpClient httpClient, String url, String keyId, String secret,
                        String templateId, String otpVarName, long timeoutMs) {
            this.httpClient = httpClient;
            this.url = url;
            this.keyId = keyId;
            this.secret = secret;
            this.templateId = templateId;
            this.otpVarName = otpVarName;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public void send(String phoneNumber, String message) throws SmsException {
            if (url == null || url.isBlank() || secret == null || secret.isBlank()) {
                throw new SmsException("HTTP SMS provider not configured");
            }
            if (phoneNumber == null || phoneNumber.isBlank()) {
                throw new SmsException("Phone number is empty");
            }
            if (message == null || message.isBlank()) {
                throw new SmsException("Message is empty");
            }

            String otpCode = extractOtp(message);
            String body = buildJsonBody(phoneNumber.trim(), otpCode);

            URI uri = URI.create(url);
            String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
            String nonce = newNonce();
            String signature = sign(secret, "POST", signingPath(uri), timestamp, nonce);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-NS-Key", keyId)
                    .header("X-NS-Timestamp", timestamp)
                    .header("X-NS-Nonce", nonce)
                    .header("X-NS-Signature", "v1=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            long startedAt = System.currentTimeMillis();
            try {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                long latencyMs = System.currentTimeMillis() - startedAt;
                String responseBody = resp.body() == null ? "" : resp.body();

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    LOG.infof("OTP SMS handed to notification-service: phone=%s status=%d latency_ms=%d",
                            maskPhone(phoneNumber), resp.statusCode(), latencyMs);
                    return;
                }

                // notification-service answers 409 when an identical payload was
                // already enqueued inside its dedupe window. Each login generates a
                // fresh code, so an identical payload means this exact OTP is
                // already on its way — failing the login here would show the user
                // an error for a code they are about to receive.
                if (resp.statusCode() == 409) {
                    LOG.warnf("OTP SMS suppressed as a duplicate by notification-service, "
                                    + "treating as sent: phone=%s latency_ms=%d response=%s",
                            maskPhone(phoneNumber), latencyMs, responseBody);
                    return;
                }

                LOG.errorf("OTP SMS rejected by notification-service: phone=%s status=%d latency_ms=%d response=%s",
                        maskPhone(phoneNumber), resp.statusCode(), latencyMs, responseBody);
                throw new SmsException("notification-service send failed: HTTP "
                        + resp.statusCode() + " " + responseBody);
            } catch (java.io.IOException e) {
                long latencyMs = System.currentTimeMillis() - startedAt;
                LOG.errorf(e, "OTP SMS IO error talking to notification-service: phone=%s latency_ms=%d error=%s",
                        maskPhone(phoneNumber), latencyMs, e.getMessage());
                throw new SmsException("notification-service send IO error", e);
            } catch (InterruptedException e) {
                long latencyMs = System.currentTimeMillis() - startedAt;
                Thread.currentThread().interrupt();
                LOG.errorf(e, "OTP SMS interrupted: phone=%s latency_ms=%d",
                        maskPhone(phoneNumber), latencyMs);
                throw new SmsException("notification-service send interrupted", e);
            }
        }

        @Override
        public void close() {
            // no-op
        }

        String buildJsonBody(String phone, String otpCode) {
            return "{"
                    + "\"channel\":\"sms\","
                    + "\"to\":\"" + jsonEscape(phone) + "\","
                    + "\"template_id\":\"" + jsonEscape(templateId) + "\","
                    + "\"priority\":\"realtime\","
                    + "\"variables\":{\"" + jsonEscape(otpVarName) + "\":\"" + jsonEscape(otpCode) + "\"}"
                    + "}";
        }

        /**
         * notification-service signs over the request target exactly as its HTTP
         * server saw it, which includes the query string. Signing only the path
         * would fail verification the moment a query parameter is added.
         */
        static String signingPath(URI uri) {
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = uri.getRawQuery();
            return query == null || query.isEmpty() ? path : path + "?" + query;
        }

        /** {@code HMAC-SHA256(secret, METHOD\nPATH\nTIMESTAMP\nNONCE)}, hex. */
        static String sign(String secret, String method, String path, String timestamp, String nonce)
                throws SmsException {
            String baseString = String.join("\n", method, path, timestamp, nonce);
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.GeneralSecurityException e) {
                throw new SmsException("Failed to sign notification-service request", e);
            }
        }

        /**
         * Nonces are replay-protected server-side, so a repeat within the window is
         * rejected outright. SecureRandom rather than a counter: Keycloak may be
         * running more than one replica against the same notification service.
         */
        static String newNonce() {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }

        /** Extract first 4-10 digit run from the SMS body. */
        static String extractOtp(String message) throws SmsException {
            Matcher m = OTP_PATTERN.matcher(message);
            if (m.find()) {
                return m.group(1);
            }
            throw new SmsException("HTTP SMS provider: could not extract OTP code from message");
        }

        /** Logs are not a place for whole phone numbers. */
        static String maskPhone(String phoneNumber) {
            String digits = phoneNumber.replaceAll("[^0-9]", "");
            return digits.length() <= 4 ? "****" : "****" + digits.substring(digits.length() - 4);
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
