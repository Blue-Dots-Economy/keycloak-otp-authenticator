package hr.delmisoft.keycloak.otp.email;

import java.util.HashMap;
import java.util.Map;

import hr.delmisoft.keycloak.otp.EmailOtpConst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmtpOtpEmailSenderFactoryTest {
    @Mock KeycloakSession session;
    @Mock EmailTemplateProvider templates;
    @Mock RealmModel realm;
    @Mock UserModel user;

    @Test
    void idAndOrder() {
        SmtpOtpEmailSenderFactory f = new SmtpOtpEmailSenderFactory();
        assertThat(f.getId(), equalTo("smtp"));
        assertThat(f.order(), equalTo(100));
    }

    @Test
    void create_returnsSender() {
        assertThat(new SmtpOtpEmailSenderFactory().create(session), notNullValue());
    }

    @Test
    void sendsTheThemeTemplateWithTheCode() throws Exception {
        when(session.getProvider(EmailTemplateProvider.class)).thenReturn(templates);
        when(templates.setRealm(realm)).thenReturn(templates);
        when(templates.setUser(user)).thenReturn(templates);

        new SmtpOtpEmailSenderFactory().create(session).send(realm, user, "123456");

        verify(templates).setRealm(realm);
        verify(templates).setUser(user);
        verify(templates).send(EmailOtpConst.EMAIL_SUBJECT_KEY, EmailOtpConst.EMAIL_TEMPLATE,
                new HashMap<>(Map.of("code", "123456")));
    }

    @Test
    void wrapsEmailException() throws Exception {
        when(session.getProvider(EmailTemplateProvider.class)).thenReturn(templates);
        when(templates.setRealm(realm)).thenReturn(templates);
        when(templates.setUser(user)).thenReturn(templates);
        EmailException cause = new EmailException("smtp down");
        doThrow(cause).when(templates).send(anyString(), anyString(), anyMap());

        OtpEmailException e = assertThrows(OtpEmailException.class,
                () -> new SmtpOtpEmailSenderFactory().create(session).send(realm, user, "123456"));
        assertThat(e.getCause(), sameInstance(cause));
        assertThat(e.transport(), equalTo("smtp"));
        assertThat(e, instanceOf(Exception.class));
    }
}
