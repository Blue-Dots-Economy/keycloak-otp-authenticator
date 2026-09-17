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
class Msg91SmsProviderFactoryTest {

    private Msg91SmsProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new Msg91SmsProviderFactory();
    }

    @Test
    void getId_returnsMsg91() {
        assertThat(factory.getId(), equalTo(Msg91SmsProviderFactory.PROVIDER_ID));
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
                () -> provider.send("+919999999999", "Your verification code is: 123456")
        );
        assertThat(ex.getMessage(), containsString("not configured"));
    }

    @Test
    void send_throws_onEmptyPhoneNumber() {
        HttpClient client = mock(HttpClient.class);
        Msg91SmsProviderFactory.Msg91SmsProvider provider =
                new Msg91SmsProviderFactory.Msg91SmsProvider(client, "key", "tpl", "MSGIND", "OTP");

        assertThrows(SmsException.class, () -> provider.send("", "Your verification code is: 123456"));
        assertThrows(SmsException.class, () -> provider.send(null, "Your verification code is: 123456"));
    }

    @Test
    void send_throws_onEmptyMessage() {
        HttpClient client = mock(HttpClient.class);
        Msg91SmsProviderFactory.Msg91SmsProvider provider =
                new Msg91SmsProviderFactory.Msg91SmsProvider(client, "key", "tpl", "MSGIND", "OTP");

        assertThrows(SmsException.class, () -> provider.send("+919999999999", ""));
        assertThrows(SmsException.class, () -> provider.send("+919999999999", null));
    }

    @Test
    void send_throws_whenMessageHasNoDigits() {
        HttpClient client = mock(HttpClient.class);
        Msg91SmsProviderFactory.Msg91SmsProvider provider =
                new Msg91SmsProviderFactory.Msg91SmsProvider(client, "key", "tpl", "MSGIND", "OTP");

        SmsException ex = assertThrows(
                SmsException.class,
                () -> provider.send("+919999999999", "no code here")
        );
        assertThat(ex.getMessage(), containsString("could not extract OTP"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void send_succeeds_on2xxResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(response).when(client).send(any(), any());

        Msg91SmsProviderFactory.Msg91SmsProvider provider =
                new Msg91SmsProviderFactory.Msg91SmsProvider(client, "key", "tpl", "MSGIND", "OTP");

        provider.send("+919999999999", "Your verification code is: 123456");
    }

    @SuppressWarnings("unchecked")
    @Test
    void send_throwsSmsException_on4xxResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(401).when(response).statusCode();
        doReturn("{\"type\":\"error\",\"message\":\"invalid authkey\"}").when(response).body();
        doReturn(response).when(client).send(any(), any());

        Msg91SmsProviderFactory.Msg91SmsProvider provider =
                new Msg91SmsProviderFactory.Msg91SmsProvider(client, "key", "tpl", "MSGIND", "OTP");

        SmsException ex = assertThrows(
                SmsException.class,
                () -> provider.send("+919999999999", "Your verification code is: 123456")
        );
        assertThat(ex.getMessage(), containsString("401"));
    }

    @Test
    void normalisePhone_stripsPlusAndNonDigits() {
        assertThat(Msg91SmsProviderFactory.Msg91SmsProvider.normalisePhone("+91 99999-99999"),
                equalTo("919999999999"));
        assertThat(Msg91SmsProviderFactory.Msg91SmsProvider.normalisePhone("919999999999"),
                equalTo("919999999999"));
    }

    @Test
    void extractOtp_findsCodeInMessage() throws Exception {
        assertThat(
                Msg91SmsProviderFactory.Msg91SmsProvider.extractOtp("Your verification code is: 123456"),
                equalTo("123456"));
        assertThat(
                Msg91SmsProviderFactory.Msg91SmsProvider.extractOtp("OTP 9876"),
                equalTo("9876"));
    }

    @Test
    void buildJsonBody_includesTemplateRecipientAndVar() {
        HttpClient client = mock(HttpClient.class);
        Msg91SmsProviderFactory.Msg91SmsProvider provider =
                new Msg91SmsProviderFactory.Msg91SmsProvider(client, "key", "TPL123", "MSGIND", "var");

        String body = provider.buildJsonBody("919999999999", "123456");

        assertThat(body, containsString("\"template_id\":\"TPL123\""));
        assertThat(body, containsString("\"sender\":\"MSGIND\""));
        assertThat(body, containsString("\"mobiles\":\"919999999999\""));
        assertThat(body, containsString("\"var\":\"123456\""));
        assertThat(body, containsString("\"short_url\":\"0\""));
    }

    @Test
    void buildJsonBody_omitsSenderWhenBlank() {
        HttpClient client = mock(HttpClient.class);
        Msg91SmsProviderFactory.Msg91SmsProvider provider =
                new Msg91SmsProviderFactory.Msg91SmsProvider(client, "key", "TPL123", "", "var");

        String body = provider.buildJsonBody("919999999999", "123456");

        assertThat(body.contains("\"sender\""), equalTo(false));
    }
}
