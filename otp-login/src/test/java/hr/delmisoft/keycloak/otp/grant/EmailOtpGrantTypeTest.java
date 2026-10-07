package hr.delmisoft.keycloak.otp.grant;

import hr.delmisoft.keycloak.otp.email.OtpEmailException;
import hr.delmisoft.keycloak.otp.email.OtpEmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailOtpGrantTypeTest {

    @Mock KeycloakSession session;
    @Mock RealmModel realm;
    @Mock UserModel user;
    @Mock OtpEmailSender emailSender;

    /** Sets the session and realm that Keycloak's grant-type base class normally fills from the request context. */
    private static final class Grant extends EmailOtpGrantType {
        Grant(KeycloakSession session, RealmModel realm) {
            this.session = session;
            this.realm = realm;
        }
    }

    @Test
    void sendOtp_delegatesToTheEmailSenderAndReturnsTheUserEmailAsTarget() throws Exception {
        when(session.getProvider(OtpEmailSender.class)).thenReturn(emailSender);
        when(user.getEmail()).thenReturn("user@example.com");

        String target = new Grant(session, realm).sendOtp(user, "123456");

        verify(emailSender).send(realm, user, "123456");
        assertThat(target, equalTo("user@example.com"));
    }

    @Test
    void sendOtp_propagatesSenderFailure() throws Exception {
        when(session.getProvider(OtpEmailSender.class)).thenReturn(emailSender);
        OtpEmailException failure = new OtpEmailException("x");
        doThrow(failure).when(emailSender).send(realm, user, "123456");

        OtpEmailException thrown = assertThrows(OtpEmailException.class,
                () -> new Grant(session, realm).sendOtp(user, "123456"));
        assertThat(thrown, sameInstance(failure));
    }
}
