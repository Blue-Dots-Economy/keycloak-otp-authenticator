package hr.delmisoft.keycloak.otp.sms;

import hr.delmisoft.keycloak.otp.ns.NsNotifyClient;
import hr.delmisoft.keycloak.otp.ns.NsNotifyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpSmsProviderFactoryTest {

    private static final String MESSAGE = "Your verification code is: 123456";

    private HttpSmsProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new HttpSmsProviderFactory();
    }

    private static HttpSmsProviderFactory.HttpSmsProvider provider(NsNotifyClient client) {
        return new HttpSmsProviderFactory.HttpSmsProvider(null, client, "login_otp", "message");
    }

    private static NsNotifyClient okClient() {
        NsNotifyClient client = mock(NsNotifyClient.class);
        when(client.isConfigured()).thenReturn(true);
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
    void send_throws_onEmptyPhoneOrMessage() throws Exception {
        NsNotifyClient client = okClient();
        HttpSmsProviderFactory.HttpSmsProvider p = provider(client);

        assertThrows(SmsException.class, () -> p.send("", MESSAGE));
        assertThrows(SmsException.class, () -> p.send(null, MESSAGE));
        assertThrows(SmsException.class, () -> p.send("+919876543210", ""));
        assertThrows(SmsException.class, () -> p.send("+919876543210", null));
        verify(client, never()).send(anyString());
    }

    @Test
    void send_throws_whenMessageHasNoCode() throws Exception {
        NsNotifyClient client = okClient();
        HttpSmsProviderFactory.HttpSmsProvider p = provider(client);

        SmsException ex = assertThrows(SmsException.class, () -> p.send("+919876543210", "no code here"));
        assertThat(ex.getMessage(), containsString("could not extract OTP"));
        verify(client, never()).send(anyString());
    }

    @Test
    void send_postsTemplateKeyBodyToNs() throws Exception {
        NsNotifyClient client = okClient();
        provider(client).send("+919876543210", MESSAGE);
        verify(client).send("{\"template_key\":\"login_otp\",\"channel\":\"sms\","
                + "\"to\":{\"phone\":\"+919876543210\"},\"variables\":{\"message\":\"123456\"},"
                + "\"priority\":\"urgent\"}");
    }

    @Test
    void send_canonicalisesPhoneToE164() throws Exception {
        for (String raw : List.of("9876543210", "+91 98765 43210", "+91-98765-43210", "919876543210")) {
            NsNotifyClient client = okClient();
            provider(client).send(raw, MESSAGE);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(client).send(body.capture());
            assertThat(raw, body.getValue(), containsString("\"phone\":\"+919876543210\""));
        }
    }

    @Test
    void send_rejectsUnparseablePhoneWithoutRequest() throws Exception {
        NsNotifyClient client = okClient();
        SmsException e = assertThrows(SmsException.class, () -> provider(client).send("12345", MESSAGE));
        assertThat(e.getMessage(), containsString("E.164"));
        assertThat(e.getMessage(), not(containsString("12345")));
        verify(client, never()).send(anyString());
    }

    @Test
    void send_usesConfiguredTemplateAndVariableName() throws Exception {
        NsNotifyClient client = okClient();
        new HttpSmsProviderFactory.HttpSmsProvider(null, client, "kc_login", "otp").send("+919876543210", MESSAGE);
        verify(client).send(argThat((String b) -> b.contains("\"template_key\":\"kc_login\"")
                && b.contains("\"variables\":{\"otp\":\"123456\"}")));
    }

    @Test
    void send_wrapsNsFailureWithoutCode() throws Exception {
        NsNotifyClient client = okClient();
        doThrow(new NsNotifyException("notification-service send failed: HTTP 422 {}", 422)).when(client).send(anyString());
        SmsException e = assertThrows(SmsException.class, () -> provider(client).send("+919876543210", MESSAGE));
        assertThat(e.getMessage(), containsString("422"));
        assertThat(e.getMessage(), not(containsString("123456")));
    }

    @Test
    void send_unconfiguredFailsWithoutRequest() throws Exception {
        NsNotifyClient client = mock(NsNotifyClient.class);
        when(client.isConfigured()).thenReturn(false);
        SmsException e = assertThrows(SmsException.class, () -> provider(client).send("+919876543210", MESSAGE));
        assertThat(e.getMessage(), containsString("not configured"));
        verify(client, never()).send(anyString());
    }

    @Test
    void extractOtp_findsTheCode() throws Exception {
        assertThat(HttpSmsProviderFactory.HttpSmsProvider.extractOtp(MESSAGE), equalTo("123456"));
        assertThat(HttpSmsProviderFactory.HttpSmsProvider.extractOtp("OTP 9876"), equalTo("9876"));
    }

    @Test
    void maskPhone_keepsOnlyTheLastFourDigits() {
        assertThat(SmsLogSafe.maskPhone("+91 99999-91234"), equalTo("****1234"));
        assertThat(SmsLogSafe.maskPhone("123"), equalTo("****"));
    }
}
