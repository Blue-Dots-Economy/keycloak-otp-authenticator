package hr.delmisoft.keycloak.otp.ns;

import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NsNotifyClientTest {

    private static final String URL = "http://ns.signals.svc.cluster.local:3000/v1/notify";
    private static final String SECRET = "ns_keycloak_secret";
    private static final String BODY =
            "{\"template_key\":\"login_otp\",\"channel\":\"sms\",\"to\":{\"phone\":\"+919876543210\"},"
            + "\"variables\":{\"message\":\"123456\"},\"priority\":\"urgent\"}";

    @SuppressWarnings("unchecked")
    private static HttpClient clientReturning(int status, String body) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(status).when(response).statusCode();
        doReturn(body).when(response).body();
        doReturn(response).when(client).send(any(), any());
        return client;
    }

    private static NsNotifyClient client(HttpClient http, String url) {
        return new NsNotifyClient(http, URI.create(url), "keycloak", SECRET, 5000L);
    }

    private static HttpRequest captured(HttpClient http) throws Exception {
        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(req.capture(), any());
        return req.getValue();
    }

    /** Vector produced by notification-service src/lib/auth/hmac.ts (see Step 2). */
    @Test
    void signV2_matchesNotificationServiceVector() throws Exception {
        String sig = NsNotifyClient.signV2("test-secret", "POST", "/v1/notify", "1700000000",
                "00112233445566778899aabbccddeeff", BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(sig, equalTo("b13a4b0821f7eba44554c6589d41466186bccf68802cb03a13799d970fde9545"));
    }

    @Test
    void send_postsBodyWithV2Headers() throws Exception {
        HttpClient http = clientReturning(202, "{\"status\":\"accepted\"}");
        client(http, URL).send(BODY);

        HttpRequest req = captured(http);
        assertThat(req.method(), equalTo("POST"));
        assertThat(req.uri().toString(), equalTo(URL));
        assertThat(req.headers().firstValue("Content-Type").orElse(""), equalTo("application/json"));
        assertThat(req.headers().firstValue("X-NS-Key").orElse(""), equalTo("keycloak"));
        assertThat(req.headers().firstValue("X-NS-Nonce").orElse(""), matchesPattern("[0-9a-f]{32}"));
        assertThat(req.headers().firstValue("X-NS-Timestamp").orElse(""), matchesPattern("\\d{10}"));
        assertThat(req.headers().firstValue("X-NS-Signature").orElse(""), matchesPattern("v2=[0-9a-f]{64}"));
        assertThat(new String(RequestBodies.of(req), StandardCharsets.UTF_8), equalTo(BODY));
    }

    @Test
    void send_signatureVerifiesAgainstCapturedBodyBytes() throws Exception {
        String body = "{\"variables\":{\"message\":\"123456\",\"note\":\"Zoë \\\"quoted\\\" \\u0001\"}}";
        HttpClient http = clientReturning(202, "");
        client(http, URL).send(body);

        HttpRequest req = captured(http);
        String expected = "v2=" + NsNotifyClient.signV2(SECRET, "POST", "/v1/notify",
                req.headers().firstValue("X-NS-Timestamp").orElseThrow(),
                req.headers().firstValue("X-NS-Nonce").orElseThrow(),
                RequestBodies.of(req));
        assertThat(req.headers().firstValue("X-NS-Signature").orElseThrow(), equalTo(expected));
    }

    @Test
    void signingPath_includesQuery() {
        assertThat(NsNotifyClient.signingPath(URI.create("http://ns:3000/v1/notify?x=1")), equalTo("/v1/notify?x=1"));
        assertThat(NsNotifyClient.signingPath(URI.create("http://ns:3000/v1/notify")), equalTo("/v1/notify"));
        assertThat(NsNotifyClient.signingPath(URI.create("http://ns:3000")), equalTo("/"));
    }

    @Test
    void send_accepts2xx() throws Exception {
        client(clientReturning(200, "{}"), URL).send(BODY);
        client(clientReturning(202, "{}"), URL).send(BODY);
    }

    @Test
    void send_409DuplicateFallbackIsAccepted() throws Exception {
        client(clientReturning(409, "{\"error\":\"duplicate-fallback\"}"), URL).send(BODY);
        client(clientReturning(409, "{\"enqueued\":false,\"reason\":\"duplicate-fallback\"}"), URL).send(BODY);
    }

    @Test
    void send_other409Fails() throws Exception {
        NsNotifyException a = assertThrows(NsNotifyException.class,
                () -> client(clientReturning(409, "{\"error\":\"idempotency_in_progress\"}"), URL).send(BODY));
        assertThat(a.status(), equalTo(409));
        assertThrows(NsNotifyException.class,
                () -> client(clientReturning(409, "not json"), URL).send(BODY));
    }

    @Test
    void send_non2xxFailsWithStatus() throws Exception {
        NsNotifyException e = assertThrows(NsNotifyException.class,
                () -> client(clientReturning(422, "{\"error\":\"unknown_template\"}"), URL).send(BODY));
        assertThat(e.status(), equalTo(422));
        assertThat(e.getMessage(), containsString("422"));
        assertThat(e.getMessage(), not(containsString("123456")));
    }

    @Test
    void send_ioErrorFails() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("connection refused")).when(http).send(any(), any());
        NsNotifyException e = assertThrows(NsNotifyException.class, () -> client(http, URL).send(BODY));
        assertThat(e.status(), equalTo(-1));
    }

    @Test
    void send_unconfiguredFailsWithoutRequest() throws Exception {
        HttpClient http = mock(HttpClient.class);
        NsNotifyClient unconfigured = new NsNotifyClient(http, null, "keycloak", SECRET, 5000L);
        assertThat(unconfigured.isConfigured(), is(false));
        NsNotifyException e = assertThrows(NsNotifyException.class, () -> unconfigured.send(BODY));
        assertThat(e.getMessage(), containsString("SMS_HTTP_URL"));
        verifyNoInteractions(http);
    }

    @Test
    void parseUrl_andParseTimeout() {
        assertThat(NsNotifyClient.parseUrl("http://ns:3000/v1/notify"), notNullValue());
        assertThat(NsNotifyClient.parseUrl("ns:3000/v1/notify"), nullValue());
        assertThat(NsNotifyClient.parseUrl("/v1/notify"), nullValue());
        assertThat(NsNotifyClient.parseUrl(" "), nullValue());
        assertThat(NsNotifyClient.parseTimeout(null), equalTo(5000L));
        assertThat(NsNotifyClient.parseTimeout("0"), equalTo(5000L));
        assertThat(NsNotifyClient.parseTimeout("abc"), equalTo(5000L));
        assertThat(NsNotifyClient.parseTimeout("2500"), equalTo(2500L));
    }

    @Test
    void json_escapes() {
        assertThat(NsNotifyClient.json("a\"b\\c\n\t\u0001"), equalTo("a\\\"b\\\\c\\n\\t\\u0001"));
    }

    @Test
    void parseUrl_acceptsOnlyHttpSchemes() {
        assertThat(NsNotifyClient.parseUrl("https://ns:3000/v1/notify"), notNullValue());
        assertThat(NsNotifyClient.parseUrl("HTTP://ns:3000/v1/notify"), notNullValue());
        assertThat(NsNotifyClient.parseUrl("ftp://ns:3000/v1/notify"), nullValue());
    }

    @Test
    void send_ftpUriFailsAsNsNotifyException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        NsNotifyClient ftp = new NsNotifyClient(http, URI.create("ftp://ns:3000/v1/notify"), "keycloak", SECRET, 5000L);
        NsNotifyException e = assertThrows(NsNotifyException.class, () -> ftp.send(BODY));
        assertThat(e.status(), equalTo(-1));
        verifyNoInteractions(http);
    }

    @Test
    void send_headerIllegalKeyIdFailsAsNsNotifyException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        NsNotifyClient badKey = new NsNotifyClient(http, URI.create(URL), "key\nid", SECRET, 5000L);
        NsNotifyException e = assertThrows(NsNotifyException.class, () -> badKey.send(BODY));
        assertThat(e.status(), equalTo(-1));
        verifyNoInteractions(http);
    }

    @Test
    void isConfigured_falseWhenSecretBlank() {
        HttpClient http = mock(HttpClient.class);
        assertThat(new NsNotifyClient(http, URI.create(URL), "keycloak", " ", 5000L).isConfigured(), is(false));
        assertThat(new NsNotifyClient(http, URI.create(URL), "keycloak", null, 5000L).isConfigured(), is(false));
        assertThat(new NsNotifyClient(http, URI.create(URL), "keycloak", SECRET, 5000L).isConfigured(), is(true));
    }

    @Test
    void readConfig_spiWinsOverEnv() {
        Config.Scope scope = mock(Config.Scope.class);
        doReturn("from-spi").when(scope).get("url");
        assertThat(NsNotifyClient.readConfig(scope, "url", "PATH"), equalTo("from-spi"));
    }

    @Test
    void readConfig_blankSpiFallsThroughToEnv() {
        // PATH is set in every test environment, so it stands in for SMS_HTTP_* here.
        Config.Scope scope = mock(Config.Scope.class);
        doReturn(" ").when(scope).get("url");
        assertThat(System.getenv("PATH"), notNullValue());
        assertThat(NsNotifyClient.readConfig(scope, "url", "PATH"), equalTo(System.getenv("PATH")));
        assertThat(NsNotifyClient.readConfig(null, "url", "PATH"), equalTo(System.getenv("PATH")));
    }

    @Test
    void readConfigOrDefault_returnsDefaultWhenUnset() {
        Config.Scope scope = mock(Config.Scope.class);
        String unsetEnv = "NS_NOTIFY_CLIENT_TEST_UNSET_" + System.nanoTime();
        assertThat(NsNotifyClient.readConfigOrDefault(scope, "key-id", unsetEnv, "keycloak"), equalTo("keycloak"));
        doReturn("signals").when(scope).get("key-id");
        assertThat(NsNotifyClient.readConfigOrDefault(scope, "key-id", unsetEnv, "keycloak"), equalTo("signals"));
    }
}
