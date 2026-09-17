package hr.delmisoft.keycloak.otp.identifier;

import hr.delmisoft.keycloak.otp.sms.SmsOtpConst;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.events.EventBuilder;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentifierFormAuthenticatorTest {

    private IdentifierFormAuthenticator authenticator;

    @Mock private AuthenticationFlowContext context;
    @Mock private KeycloakSession session;
    @Mock private KeycloakContext kcContext;
    @Mock private RealmModel realm;
    @Mock private UserProvider userProvider;
    @Mock private UserModel user;
    @Mock private AuthenticationSessionModel authSession;
    @Mock private LoginFormsProvider form;
    @Mock private HttpRequest httpRequest;
    @Mock private EventBuilder event;
    @Mock private Response challengeResponse;

    @BeforeEach
    void setUp() {
        authenticator = new IdentifierFormAuthenticator();
        when(context.getSession()).thenReturn(session);
        when(context.getRealm()).thenReturn(realm);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(context.form()).thenReturn(form);
        when(context.getHttpRequest()).thenReturn(httpRequest);
        when(context.getEvent()).thenReturn(event);
        when(event.user(any(UserModel.class))).thenReturn(event);
        when(event.detail(anyString(), anyString())).thenReturn(event);
        when(session.getContext()).thenReturn(kcContext);
        when(kcContext.getRealm()).thenReturn(realm);
        when(session.users()).thenReturn(userProvider);
        when(realm.getDefaultLocale()).thenReturn("en-IN");
        when(form.createForm(IdentifierFormConst.LOGIN_TEMPLATE)).thenReturn(challengeResponse);
        when(form.setError(anyString())).thenReturn(form);
        when(form.setAttribute(anyString(), any())).thenReturn(form);
    }

    private void postIdentifier(String value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (value != null) params.putSingle(IdentifierFormConst.PARAM_IDENTIFIER, value);
        when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    }

    @Test
    void authenticate_rendersForm() {
        authenticator.authenticate(context);
        verify(form).createForm(IdentifierFormConst.LOGIN_TEMPLATE);
        verify(context).challenge(challengeResponse);
    }

    @Test
    void action_blankIdentifier_failureChallenge() {
        postIdentifier("");
        authenticator.action(context);
        verify(form).setError(IdentifierFormConst.ERROR_IDENTIFIER_BLANK);
        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_USER), any(Response.class));
    }

    @Test
    void action_nullIdentifier_failureChallenge() {
        postIdentifier(null);
        authenticator.action(context);
        verify(form).setError(IdentifierFormConst.ERROR_IDENTIFIER_BLANK);
    }

    @Test
    void action_emailUserNotFound_failureChallenge() {
        postIdentifier("missing@example.com");
        when(userProvider.getUserByEmail(realm, "missing@example.com")).thenReturn(null);
        authenticator.action(context);
        verify(form).setError(IdentifierFormConst.ERROR_IDENTIFIER_USER_NOT_FOUND);
    }

    @Test
    void action_emailMatch_setsUserAndSucceeds() {
        postIdentifier("alice@example.com");
        when(userProvider.getUserByEmail(realm, "alice@example.com")).thenReturn(user);
        when(user.getEmail()).thenReturn("alice@example.com");
        when(user.getUsername()).thenReturn("alice");
        authenticator.action(context);
        verify(authSession).setAuthNote(IdentifierFormConst.AUTH_NOTE_IDENTIFIER, "alice@example.com");
        verify(authSession).setAuthNote(IdentifierFormConst.AUTH_NOTE_IDENTIFIER_TYPE, IdentifierUtil.IdentifierType.EMAIL.name());
        verify(context).setUser(user);
        verify(context).success();
    }

    @Test
    void action_phoneMatch_setsUserAndSucceeds() {
        postIdentifier("9876543210");
        when(userProvider.searchForUserByUserAttributeStream(eq(realm), eq(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE), anyString()))
                .thenReturn(java.util.stream.Stream.empty());
        when(userProvider.searchForUserByUserAttributeStream(eq(realm), eq(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE), eq("+919876543210")))
                .thenReturn(java.util.stream.Stream.of(user));
        when(user.getFirstAttribute(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE)).thenReturn("+919876543210");
        when(user.getUsername()).thenReturn("alice");
        authenticator.action(context);
        verify(authSession).setAuthNote(IdentifierFormConst.AUTH_NOTE_IDENTIFIER_TYPE, IdentifierUtil.IdentifierType.PHONE.name());
        verify(context).setUser(user);
        verify(context).success();
    }

    @Test
    void action_usernameMatch_setsUserAndSucceeds() {
        postIdentifier("alice");
        when(userProvider.getUserByUsername(realm, "alice")).thenReturn(user);
        when(user.getEmail()).thenReturn("alice@example.com");
        when(user.getUsername()).thenReturn("alice");
        authenticator.action(context);
        verify(authSession).setAuthNote(IdentifierFormConst.AUTH_NOTE_IDENTIFIER_TYPE, IdentifierUtil.IdentifierType.USERNAME.name());
        verify(context).setUser(user);
        verify(context).success();
    }

    @Test
    void action_emailUserHasNoEmail_rejected() {
        postIdentifier("alice@example.com");
        when(userProvider.getUserByEmail(realm, "alice@example.com")).thenReturn(user);
        when(user.getEmail()).thenReturn(null);
        when(user.getUsername()).thenReturn("alice");
        authenticator.action(context);
        verify(form).setError(IdentifierFormConst.ERROR_IDENTIFIER_NO_CHANNEL);
        verify(context, never()).success();
    }

    @Test
    void action_phoneUserHasNoPhoneAttr_rejected() {
        postIdentifier("9876543210");
        when(userProvider.searchForUserByUserAttributeStream(eq(realm), eq(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE), anyString()))
                .thenReturn(java.util.stream.Stream.empty());
        when(userProvider.searchForUserByUserAttributeStream(eq(realm), eq(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE), eq("+919876543210")))
                .thenReturn(java.util.stream.Stream.of(user));
        when(user.getFirstAttribute(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE)).thenReturn(null);
        when(user.getUsername()).thenReturn("alice");
        authenticator.action(context);
        verify(form).setError(IdentifierFormConst.ERROR_IDENTIFIER_NO_CHANNEL);
        verify(context, never()).success();
    }
}
