package hr.delmisoft.keycloak.otp.email;

import hr.delmisoft.keycloak.otp.ns.NsNotifyClient;
import hr.delmisoft.keycloak.otp.ns.NsNotifyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpOtpEmailSenderFactoryTest {
    @Mock RealmModel realm;
    @Mock UserModel user;
    @Mock KeycloakSession session;

    private static NsNotifyClient okClient() {
        NsNotifyClient client = mock(NsNotifyClient.class);
        when(client.isConfigured()).thenReturn(true);
        return client;
    }

    private static HttpOtpEmailSenderFactory.HttpOtpEmailSender sender(NsNotifyClient client) {
        return new HttpOtpEmailSenderFactory.HttpOtpEmailSender(client, "login_otp", "message");
    }

    @Test
    void idAndOrder() {
        HttpOtpEmailSenderFactory f = new HttpOtpEmailSenderFactory();
        assertThat(f.getId(), equalTo("http"));
        assertThat(f.order(), equalTo(0));
    }

    @Test
    void create_returnsSender() {
        HttpOtpEmailSenderFactory f = new HttpOtpEmailSenderFactory();
        f.init(null);
        assertThat(f.create(session), notNullValue());
    }

    @Test
    void send_postsTemplateKeyEmailBody() throws Exception {
        NsNotifyClient client = okClient();
        when(user.getEmail()).thenReturn("asha@example.org");
        sender(client).send(realm, user, "123456");
        verify(client).send("{\"template_key\":\"login_otp\",\"channel\":\"email\","
                + "\"to\":{\"email\":\"asha@example.org\"},\"variables\":{\"message\":\"123456\"},"
                + "\"priority\":\"urgent\"}");
    }

    @Test
    void send_trimsTheAddressAndKeepsItsCase() throws Exception {
        NsNotifyClient client = okClient();
        when(user.getEmail()).thenReturn("  Asha.Rao+otp@Example.org \n");
        sender(client).send(realm, user, "123456");
        verify(client).send("{\"template_key\":\"login_otp\",\"channel\":\"email\","
                + "\"to\":{\"email\":\"Asha.Rao+otp@Example.org\"},\"variables\":{\"message\":\"123456\"},"
                + "\"priority\":\"urgent\"}");
    }

    @Test
    void send_usesConfiguredTemplateAndVariableName() throws Exception {
        NsNotifyClient client = okClient();
        when(user.getEmail()).thenReturn("asha@example.org");
        new HttpOtpEmailSenderFactory.HttpOtpEmailSender(client, "custom_otp", "otp").send(realm, user, "654321");
        verify(client).send("{\"template_key\":\"custom_otp\",\"channel\":\"email\","
                + "\"to\":{\"email\":\"asha@example.org\"},\"variables\":{\"otp\":\"654321\"},"
                + "\"priority\":\"urgent\"}");
    }

    @Test
    void send_userWithoutEmailFailsWithoutRequest() throws Exception {
        NsNotifyClient client = okClient();
        when(user.getEmail()).thenReturn(null);
        assertThrows(OtpEmailException.class, () -> sender(client).send(realm, user, "123456"));
        verify(client, never()).send(anyString());
    }

    @Test
    void send_userWithBlankEmailFailsWithoutRequest() throws Exception {
        NsNotifyClient client = okClient();
        when(user.getEmail()).thenReturn("   ");
        assertThrows(OtpEmailException.class, () -> sender(client).send(realm, user, "123456"));
        verify(client, never()).send(anyString());
    }

    @Test
    void send_unconfiguredFailsWithoutRequest() throws Exception {
        NsNotifyClient client = mock(NsNotifyClient.class);
        when(client.isConfigured()).thenReturn(false);
        OtpEmailException e = assertThrows(OtpEmailException.class, () -> sender(client).send(realm, user, "123456"));
        assertThat(e.getMessage(), containsString("not configured"));
        assertThat(e.transport(), equalTo("http"));
        verify(client, never()).send(anyString());
    }

    @Test
    void send_wrapsNsFailureWithStatusOnly() throws Exception {
        NsNotifyClient client = okClient();
        when(user.getEmail()).thenReturn("asha@example.org");
        NsNotifyException ns = new NsNotifyException(
                "notification-service send failed: HTTP 422 {\"to\":\"asha@example.org\",\"code\":\"123456\"}", 422);
        doThrow(ns).when(client).send(anyString());
        OtpEmailException e = assertThrows(OtpEmailException.class, () -> sender(client).send(realm, user, "123456"));
        assertThat(e.getMessage(), containsString("422"));
        assertThat(e.getMessage(), not(containsString("123456")));
        assertThat(e.getMessage(), not(containsString("asha@example.org")));
        assertThat(e.transport(), equalTo("http"));
        assertThat(e.getCause(), sameInstance(ns));
    }

    @Test
    void send_wrapsNsIoErrorWithoutCodeOrAddress() throws Exception {
        NsNotifyClient client = okClient();
        when(user.getEmail()).thenReturn("asha@example.org");
        doThrow(new NsNotifyException("notification-service I/O error: ConnectException", new java.io.IOException("x")))
                .when(client).send(anyString());
        OtpEmailException e = assertThrows(OtpEmailException.class, () -> sender(client).send(realm, user, "123456"));
        assertThat(e.getMessage(), not(containsString("123456")));
        assertThat(e.getMessage(), not(containsString("asha@example.org")));
        assertThat(e.getMessage(), not(containsString("HTTP -1")));
    }
}
