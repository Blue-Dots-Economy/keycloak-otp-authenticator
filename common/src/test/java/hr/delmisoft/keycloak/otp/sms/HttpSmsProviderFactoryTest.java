package hr.delmisoft.keycloak.otp.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HttpSmsProviderFactoryTest {

    private static final String URL = "http://ns.signals.svc.cluster.local:3000/notify";
    private static final String SECRET = "ns_keycloak_secret";
    private static final String MESSAGE = "Your verification code is: 123456";

    private HttpSmsProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new HttpSmsProviderFactory();
    }

    private static HttpSmsProviderFactory.HttpSmsProvider provider(HttpClient client) {
        return new HttpSmsProviderFactory.HttpSmsProvider(
                client, URI.create(URL), "keycloak", SECRET, "login_otp", "message", 5000L);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient clientReturning(int status, String body) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(status).when(response).statusCode();
        doReturn(body).when(response).body();
        doReturn(response).when(client).send(any(), any());
        return client;
    }

    @Test
    void getId_returnsHttp() {
        assertThat(factory.getId(), equalTo(HttpSmsProviderFactory.PROVIDER_ID));
        assertThat(factory.getId(), equalTo("http"));
    }

    @Test
    void create_returnsNonNull() {
        factory.init(null);
        assertThat(factory.create(null), notNullValue());
    }

    @Test
    void send_throws_whenNotConfigured() {
        factory.init(null);
        SmsProvider unconfigured = factory.create(null);

        SmsException ex = assertThrows(SmsException.class, () -> unconfigured.send("+919999999999", MESSAGE));
        assertThat(ex.getMessage(), containsString("not configured"));
    }

    @Test
    void parseUrl_rejectsValuesThatWouldThrowOutOfSend() {
        // URI.create throws an UNCHECKED IllegalArgumentException, which would
        // escape the SmsException contract: the user sees Keycloak's generic
        // error instead of `smsSendError`, and the trace names URI.create rather
        // than the config key that is wrong.
        assertThat(HttpSmsProviderFactory.parseUrl("http://ns:3000/notify"), notNullValue());
        assertThat(HttpSmsProviderFactory.parseUrl("notification-service:3000/notify"), nullValue());
        assertThat(HttpSmsProviderFactory.parseUrl("not a url at all"), nullValue());
        assertThat(HttpSmsProviderFactory.parseUrl("/notify"), nullValue());
        assertThat(HttpSmsProviderFactory.parseUrl(null), nullValue());
        assertThat(HttpSmsProviderFactory.parseUrl("  "), nullValue());
    }

    @Test
    void send_reportsAMalformedUrlAsAnSmsException() throws Exception {
        HttpSmsProviderFactory.HttpSmsProvider p = new HttpSmsProviderFactory.HttpSmsProvider(
                mock(HttpClient.class),
                HttpSmsProviderFactory.parseUrl("notification-service:3000/notify"),
                "keycloak", SECRET, "login_otp", "message", 5000L);

        SmsException ex = assertThrows(SmsException.class, () -> p.send("+919999999999", MESSAGE));
        assertThat(ex.getMessage(), containsString("SMS_HTTP_URL"));
    }

    @Test
    void send_throws_onEmptyPhoneOrMessage() throws Exception {
        HttpSmsProviderFactory.HttpSmsProvider p = provider(mock(HttpClient.class));

        assertThrows(SmsException.class, () -> p.send("", MESSAGE));
        assertThrows(SmsException.class, () -> p.send(null, MESSAGE));
        assertThrows(SmsException.class, () -> p.send("+919999999999", ""));
        assertThrows(SmsException.class, () -> p.send("+919999999999", null));
    }

    @Test
    void send_throws_whenMessageHasNoCode() {
        HttpSmsProviderFactory.HttpSmsProvider p = provider(mock(HttpClient.class));

        SmsException ex = assertThrows(SmsException.class, () -> p.send("+919999999999", "no code here"));
        assertThat(ex.getMessage(), containsString("could not extract OTP"));
    }

    @Test
    void send_succeeds_on2xx() throws Exception {
        provider(clientReturning(200, "{\"enqueued\":true}")).send("+919999999999", MESSAGE);
    }

    @Test
    void send_throws_on4xx() throws Exception {
        HttpSmsProviderFactory.HttpSmsProvider p = provider(clientReturning(401, "{\"error\":\"Invalid signature\"}"));

        SmsException ex = assertThrows(SmsException.class, () -> p.send("+919999999999", MESSAGE));
        assertThat(ex.getMessage(), containsString("401"));
    }

    @Test
    void send_treatsDuplicateSuppressionAsSent() throws Exception {
        // notification-service answers 409 when it already has this exact payload.
        // Each login mints a fresh code, so an identical payload means this OTP is
        // already on its way — failing the login here would show the user an error
        // for a code they are about to receive.
        provider(clientReturning(409, "{\"enqueued\":false,\"reason\":\"duplicate-fallback\"}"))
                .send("+919999999999", MESSAGE);
    }

    @Test
    void buildJsonBody_carriesChannelRecipientTemplateAndCode() {
        String body = provider(mock(HttpClient.class)).buildJsonBody("+919999999999", "123456");

        assertThat(body, containsString("\"channel\":\"sms\""));
        assertThat(body, containsString("\"to\":\"+919999999999\""));
        assertThat(body, containsString("\"template_id\":\"login_otp\""));
        assertThat(body, containsString("\"priority\":\"realtime\""));
        assertThat(body, containsString("\"variables\":{\"message\":\"123456\"}"));
    }

    @Test
    void buildJsonBody_sendsNoMessageText() {
        // The delivered copy must be the text registered with the operator under
        // DLT, which notification-service holds — not this plugin's theme string.
        String body = provider(mock(HttpClient.class)).buildJsonBody("+919999999999", "123456");

        assertThat(body, not(containsString("verification code")));
        assertThat(body, not(containsString("\"body\"")));
    }

    @Test
    void send_signsTheHeadersItActuallySends() throws Exception {
        // The isolated sign() test hand-feeds all four components, so it cannot
        // catch send() feeding the wrong ones. Swapping the timestamp to
        // milliseconds keeps that test green and produces a 100%
        // `401 Request expired` in every environment, because
        // notification-service compares against epoch SECONDS with a 30s window.
        // Recomputing from the request's own headers is what pins that.
        HttpClient client = clientReturning(200, "{}");
        provider(client).send("+919999999999", MESSAGE);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(req.capture(), any());
        HttpRequest sent = req.getValue();

        String timestamp = sent.headers().firstValue("X-NS-Timestamp").orElseThrow();
        String nonce = sent.headers().firstValue("X-NS-Nonce").orElseThrow();
        String signature = sent.headers().firstValue("X-NS-Signature").orElseThrow();

        String expected = "v1=" + HttpSmsProviderFactory.HttpSmsProvider.sign(
                SECRET, "POST", "/notify", timestamp, nonce);
        assertThat(signature, equalTo(expected));
    }

    @Test
    void send_stampsAnEpochSecondsTimestampInsideTheAcceptedWindow() throws Exception {
        // notification-service rejects anything more than 30s from its own clock.
        // Milliseconds here would be ~55 years out and fail 100% of the time.
        HttpClient client = clientReturning(200, "{}");
        provider(client).send("+919999999999", MESSAGE);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(req.capture(), any());

        long sent = Long.parseLong(req.getValue().headers().firstValue("X-NS-Timestamp").orElseThrow());
        long nowSeconds = System.currentTimeMillis() / 1000L;
        assertThat(Math.abs(nowSeconds - sent) < 30, equalTo(true));
    }

    @Test
    void send_sendsTheHmacEnvelopeNotificationServiceExpects() throws Exception {
        HttpClient client = clientReturning(200, "{}");
        provider(client).send("+919999999999", MESSAGE);

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(req.capture(), any());
        HttpRequest sent = req.getValue();

        assertThat(sent.headers().firstValue("X-NS-Key").orElse(""), equalTo("keycloak"));
        assertThat(sent.headers().firstValue("X-NS-Timestamp").isPresent(), equalTo(true));
        assertThat(sent.headers().firstValue("X-NS-Nonce").isPresent(), equalTo(true));
        assertThat(sent.headers().firstValue("X-NS-Signature").orElse(""), containsString("v1="));
        assertThat(sent.method(), equalTo("POST"));
    }

    @Test
    void send_signsWithAFreshNonceEachTime() throws Exception {
        // The nonce is replay-protected server side: a repeat inside the window is
        // rejected outright, so a reused nonce would break every OTP after the first.
        HttpClient client = clientReturning(200, "{}");
        HttpSmsProviderFactory.HttpSmsProvider p = provider(client);
        p.send("+919999999999", "code 111111");
        p.send("+919999999999", "code 222222");

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, org.mockito.Mockito.times(2)).send(req.capture(), any());

        String first = req.getAllValues().get(0).headers().firstValue("X-NS-Nonce").orElse("a");
        String second = req.getAllValues().get(1).headers().firstValue("X-NS-Nonce").orElse("a");
        assertThat(first, not(equalTo(second)));
    }

    @Test
    void sign_matchesTheDocumentedBaseString() throws Exception {
        // METHOD\nPATH\nTIMESTAMP\nNONCE, HMAC-SHA256, hex. Pinned against a value
        // computed independently, so a change to the join order fails here rather
        // than as a 401 in an environment.
        String actual = HttpSmsProviderFactory.HttpSmsProvider.sign(
                "s3cr3t", "POST", "/notify", "1700000000", "abcdef");

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.HexFormat.of().formatHex(
                mac.doFinal("POST\n/notify\n1700000000\nabcdef"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(actual, equalTo(expected));
    }

    @Test
    void signingPath_usesTheRequestTargetIncludingQuery() {
        // notification-service signs over req.url, which carries the query string.
        assertThat(HttpSmsProviderFactory.HttpSmsProvider.signingPath(URI.create(URL)),
                equalTo("/notify"));
        assertThat(HttpSmsProviderFactory.HttpSmsProvider.signingPath(
                        URI.create("http://ns:3000/notify?trace=1")),
                equalTo("/notify?trace=1"));
        assertThat(HttpSmsProviderFactory.HttpSmsProvider.signingPath(URI.create("http://ns:3000")),
                equalTo("/"));
    }

    @Test
    void extractOtp_findsTheCode() throws Exception {
        assertThat(HttpSmsProviderFactory.HttpSmsProvider.extractOtp(MESSAGE), equalTo("123456"));
        assertThat(HttpSmsProviderFactory.HttpSmsProvider.extractOtp("OTP 9876"), equalTo("9876"));
    }

    @Test
    void maskPhone_keepsOnlyTheLastFourDigits() {
        assertThat(SmsLogSafe.maskPhone("+91 99999-91234"),
                equalTo("****1234"));
        assertThat(SmsLogSafe.maskPhone("123"), equalTo("****"));
    }

    @Test
    void parseTimeout_rejectsValuesThatWouldHangAnInteractiveLogin() {
        assertThat(HttpSmsProviderFactory.parseTimeout("2500"), equalTo(2500L));
        assertThat(HttpSmsProviderFactory.parseTimeout(null), equalTo(5000L));
        assertThat(HttpSmsProviderFactory.parseTimeout(""), equalTo(5000L));
        assertThat(HttpSmsProviderFactory.parseTimeout("not-a-number"), equalTo(5000L));
        // 0 means "no timeout" to HttpClient — worse than any misconfiguration it
        // could be covering for, since this call sits inside a login.
        assertThat(HttpSmsProviderFactory.parseTimeout("0"), equalTo(5000L));
        assertThat(HttpSmsProviderFactory.parseTimeout("-1"), equalTo(5000L));
    }
}
