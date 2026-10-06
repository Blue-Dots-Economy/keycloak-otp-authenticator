package hr.delmisoft.keycloak.otp.ns;

import hr.delmisoft.keycloak.otp.sms.SmsLogSafe;
import org.jboss.logging.Logger;
import org.keycloak.Config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Client for notification-service {@code POST /v1/notify}, signed with HMAC v2:
 * {@code HMAC-SHA256(secret, METHOD\npath\ntimestamp\nnonce\nsha256hex(body))}.
 * The digest is taken over the exact bytes sent, so the body is serialised once.
 *
 * <p>Settings, SPI config first, then env: {@code url}/{@code SMS_HTTP_URL} (the full
 * /v1/notify URL), {@code secret}/{@code SMS_HTTP_SECRET}, {@code key-id}/{@code SMS_HTTP_KEY_ID}
 * (default {@code keycloak}), {@code timeout-ms}/{@code SMS_HTTP_TIMEOUT_MS} (default 5000).
 * One configuration serves both the SMS and the email {@code http} providers.
 *
 * <p>Accepted: any 2xx, or 409 {@code duplicate-fallback} (NS already holds this exact
 * payload, so this code is on its way). Everything else is an {@link NsNotifyException}.
 * Redirects are not followed.
 */
public final class NsNotifyClient {

    private static final Logger LOG = Logger.getLogger(NsNotifyClient.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DEFAULT_KEY_ID = "keycloak";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;
    private static final Pattern DUPLICATE_FALLBACK =
            Pattern.compile("\"(?:error|reason)\"\\s*:\\s*\"duplicate-fallback\"");

    private final HttpClient http;
    private final URI uri;
    private final String keyId;
    private final String secret;
    private final long timeoutMs;

    NsNotifyClient(HttpClient http, URI uri, String keyId, String secret, long timeoutMs) {
        this.http = http;
        this.uri = uri;
        this.keyId = keyId;
        this.secret = secret;
        this.timeoutMs = timeoutMs;
    }

    public static NsNotifyClient fromConfig(Config.Scope config) {
        String url = readConfig(config, "url", "SMS_HTTP_URL");
        String secret = readConfig(config, "secret", "SMS_HTTP_SECRET");
        String keyId = readConfigOrDefault(config, "key-id", "SMS_HTTP_KEY_ID", DEFAULT_KEY_ID);
        long timeoutMs = parseTimeout(readConfig(config, "timeout-ms", "SMS_HTTP_TIMEOUT_MS"));
        if (url == null || url.isBlank() || secret == null || secret.isBlank()) {
            LOG.warn("notification-service client not fully configured. Set SMS_HTTP_URL and SMS_HTTP_SECRET "
                    + "(or the equivalent SPI config) before activating an 'http' provider.");
        }
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        return new NsNotifyClient(http, parseUrl(url), keyId, secret, timeoutMs);
    }

    public boolean isConfigured() {
        return uri != null && secret != null && !secret.isBlank();
    }

    public void send(String jsonBody) throws NsNotifyException {
        if (!isConfigured()) {
            throw new NsNotifyException("notification-service client not configured: check SMS_HTTP_URL and SMS_HTTP_SECRET", -1);
        }
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String nonce = newNonce();
        String signature = signV2(secret, "POST", signingPath(uri), timestamp, nonce, body);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-NS-Key", keyId)
                    .header("X-NS-Timestamp", timestamp)
                    .header("X-NS-Nonce", nonce)
                    .header("X-NS-Signature", "v2=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (IllegalArgumentException e) {
            // The URI scheme or a header value (for example SMS_HTTP_KEY_ID) is outside what HTTP allows.
            throw new NsNotifyException("notification-service request could not be built: check SMS_HTTP_URL and SMS_HTTP_KEY_ID", e);
        }

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new NsNotifyException("notification-service I/O error: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NsNotifyException("notification-service call interrupted", e);
        }
        int status = response.statusCode();
        String responseBody = response.body() == null ? "" : response.body();
        long latencyMs = System.currentTimeMillis() - startedAt;

        if (status >= 200 && status < 300) {
            LOG.debugf("notification-service accepted the send: status=%d latency_ms=%d", status, latencyMs);
            return;
        }
        if (status == 409 && DUPLICATE_FALLBACK.matcher(responseBody).find()) {
            LOG.infof("notification-service already holds this payload, treating as sent: latency_ms=%d", latencyMs);
            return;
        }
        throw new NsNotifyException("notification-service send failed: HTTP " + status + " "
                + SmsLogSafe.boundedResponse(responseBody), status);
    }

    static String signV2(String secret, String method, String path, String timestamp, String nonce, byte[] body)
            throws NsNotifyException {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", method.toUpperCase(), path, timestamp, nonce, digest);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new NsNotifyException("Failed to sign notification-service request", e);
        }
    }

    /** NS signs over the request target as Fastify sees it, query string included. */
    static String signingPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = uri.getRawQuery();
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Null when absent or not an absolute http(s) URL; {@link #send} then fails cleanly. */
    static URI parseUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI parsed = URI.create(raw.trim());
            String scheme = parsed.getScheme();
            boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpScheme || parsed.getHost() == null) {
                LOG.errorf("SMS_HTTP_URL must be an absolute http(s) URL (scheme=%s, host=%s)", scheme, parsed.getHost());
                return null;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            LOG.error("SMS_HTTP_URL is not a valid URL");
            return null;
        }
    }

    static long parseTimeout(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_TIMEOUT_MS;
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_TIMEOUT_MS;
        } catch (NumberFormatException e) {
            LOG.warnf("Invalid SMS_HTTP_TIMEOUT_MS '%s', using %d", raw, DEFAULT_TIMEOUT_MS);
            return DEFAULT_TIMEOUT_MS;
        }
    }

    public static String readConfig(Config.Scope config, String key, String envName) {
        if (config != null) {
            String fromConfig = config.get(key);
            if (fromConfig != null && !fromConfig.isBlank()) return fromConfig;
        }
        return System.getenv(envName);
    }

    public static String readConfigOrDefault(Config.Scope config, String key, String envName, String defaultValue) {
        String value = readConfig(config, key, envName);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    /** JSON string-content escaping (no surrounding quotes). */
    public static String json(String value) {
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
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
