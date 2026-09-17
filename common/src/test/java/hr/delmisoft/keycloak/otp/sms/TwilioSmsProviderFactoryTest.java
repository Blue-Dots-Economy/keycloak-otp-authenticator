package hr.delmisoft.keycloak.otp.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class TwilioSmsProviderFactoryTest {

    private TwilioSmsProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new TwilioSmsProviderFactory();
    }

    @Test
    void getId_returnsTwilio() {
        assertThat(factory.getId(), equalTo(TwilioSmsProviderFactory.PROVIDER_ID));
    }

    @Test
    void create_returnsNonNull() {
        factory.init(null);
        SmsProvider provider = factory.create(null);
        assertThat(provider, notNullValue());
    }

    @Test
    void send_throws_whenProviderNotConfigured() {
        factory.init(null);
        SmsProvider provider = factory.create(null);

        SmsException ex = assertThrows(
                SmsException.class,
                () -> provider.send("+15555550100", "code 123456")
        );
        assertThat(ex.getMessage(), containsString("not configured"));
    }

    @Test
    void send_throws_onEmptyPhoneNumber() throws Exception {
        HttpClient client = mock(HttpClient.class);
        TwilioSmsProviderFactory.TwilioSmsProvider provider =
                new TwilioSmsProviderFactory.TwilioSmsProvider(client, "AC123", "tok", "+15555550000");

        assertThrows(SmsException.class, () -> provider.send("", "msg"));
        assertThrows(SmsException.class, () -> provider.send(null, "msg"));
    }

    @Test
    void send_throws_onEmptyMessage() {
        HttpClient client = mock(HttpClient.class);
        TwilioSmsProviderFactory.TwilioSmsProvider provider =
                new TwilioSmsProviderFactory.TwilioSmsProvider(client, "AC123", "tok", "+15555550000");

        assertThrows(SmsException.class, () -> provider.send("+15555550100", ""));
        assertThrows(SmsException.class, () -> provider.send("+15555550100", null));
    }

    @SuppressWarnings("unchecked")
    @Test
    void send_succeeds_on2xxResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(201).when(response).statusCode();
        doReturn(response).when(client).send(any(), any());

        TwilioSmsProviderFactory.TwilioSmsProvider provider =
                new TwilioSmsProviderFactory.TwilioSmsProvider(client, "AC123", "tok", "+15555550000");

        provider.send("+15555550100", "your code is 123456");
    }

    @SuppressWarnings("unchecked")
    @Test
    void send_throwsSmsException_on4xxResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(401).when(response).statusCode();
        doReturn("{\"code\":20003,\"message\":\"Authenticate\"}").when(response).body();
        doReturn(response).when(client).send(any(), any());

        TwilioSmsProviderFactory.TwilioSmsProvider provider =
                new TwilioSmsProviderFactory.TwilioSmsProvider(client, "AC123", "tok", "+15555550000");

        SmsException ex = assertThrows(
                SmsException.class,
                () -> provider.send("+15555550100", "code 123456")
        );
        assertThat(ex.getMessage(), containsString("401"));
    }
}
