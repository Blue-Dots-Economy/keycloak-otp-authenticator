package hr.delmisoft.keycloak.otp.identifier;

import hr.delmisoft.keycloak.otp.sms.SmsOtpConst;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentifierUtilTest {

    @Mock private KeycloakSession session;
    @Mock private KeycloakContext context;
    @Mock private RealmModel realm;
    @Mock private UserProvider userProvider;
    @Mock private UserModel user;

    @BeforeEach
    void wireSession() {
        lenient().when(session.getContext()).thenReturn(context);
        lenient().when(context.getRealm()).thenReturn(realm);
        lenient().when(session.users()).thenReturn(userProvider);
        lenient().when(realm.getDefaultLocale()).thenReturn("en-IN");
    }

    @Test
    void detect_email() {
        assertThat(IdentifierUtil.detect("user@example.com"), is(IdentifierUtil.IdentifierType.EMAIL));
    }

    @Test
    void detect_phoneWithPlus() {
        assertThat(IdentifierUtil.detect("+919876543210"), is(IdentifierUtil.IdentifierType.PHONE));
    }

    @Test
    void detect_phonePlainDigits() {
        assertThat(IdentifierUtil.detect("9876543210"), is(IdentifierUtil.IdentifierType.PHONE));
    }

    @Test
    void detect_phoneWithSpacesAndDashes() {
        assertThat(IdentifierUtil.detect("+91 98765-43210"), is(IdentifierUtil.IdentifierType.PHONE));
    }

    @Test
    void detect_username() {
        assertThat(IdentifierUtil.detect("alice"), is(IdentifierUtil.IdentifierType.USERNAME));
    }

    @Test
    void detect_blank() {
        assertThat(IdentifierUtil.detect(""), is(IdentifierUtil.IdentifierType.USERNAME));
        assertThat(IdentifierUtil.detect(null), is(IdentifierUtil.IdentifierType.USERNAME));
        assertThat(IdentifierUtil.detect("   "), is(IdentifierUtil.IdentifierType.USERNAME));
    }

    @Test
    void findUser_email_delegatesToUserProvider() {
        when(userProvider.getUserByEmail(realm, "alice@example.com")).thenReturn(user);
        Optional<UserModel> result = IdentifierUtil.findUser(session, realm, "alice@example.com");
        assertThat(result.isPresent(), is(true));
        assertThat(result.get(), is(user));
    }

    @Test
    void findUser_username_delegatesToUserProvider() {
        when(userProvider.getUserByUsername(realm, "alice")).thenReturn(user);
        Optional<UserModel> result = IdentifierUtil.findUser(session, realm, "alice");
        assertThat(result.isPresent(), is(true));
        assertThat(result.get(), is(user));
    }

    @Test
    void findUser_phone_canonical() {
        lenient().when(userProvider.searchForUserByUserAttributeStream(eq(realm), eq(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE), anyString()))
                .thenReturn(Stream.empty());
        lenient().when(userProvider.searchForUserByUserAttributeStream(eq(realm), eq(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE), eq("+919876543210")))
                .thenReturn(Stream.of(user));
        Optional<UserModel> result = IdentifierUtil.findUser(session, realm, "9876543210");
        assertThat(result.isPresent(), is(true));
    }

    @Test
    void findUser_blankReturnsEmpty() {
        assertThat(IdentifierUtil.findUser(session, realm, "").isPresent(), is(false));
        assertThat(IdentifierUtil.findUser(session, realm, null).isPresent(), is(false));
    }

    @Test
    void canonicalize_validIndianMobile_returnsE164() throws Exception {
        String result = IdentifierUtil.canonicalize(session, "9876543210");
        assertThat(result, equalTo("+919876543210"));
    }

    @Test
    void canonicalize_alreadyE164_unchanged() throws Exception {
        String result = IdentifierUtil.canonicalize(session, "+919876543210");
        assertThat(result, equalTo("+919876543210"));
    }

    @Test
    void canonicalize_invalidNumber_throws() {
        assertThrows(PhoneNumberInvalidException.class,
                () -> IdentifierUtil.canonicalize(session, "12"));
    }

    @Test
    void canonicalize_blank_throws() {
        assertThrows(PhoneNumberInvalidException.class,
                () -> IdentifierUtil.canonicalize(session, ""));
        assertThrows(PhoneNumberInvalidException.class,
                () -> IdentifierUtil.canonicalize(session, null));
    }

    @Test
    void defaultRegion_fromLocale() {
        when(realm.getDefaultLocale()).thenReturn("en-US");
        assertThat(IdentifierUtil.defaultRegion(session), equalTo("US"));
    }

    @Test
    void defaultRegion_fromRealmAttribute() {
        when(realm.getAttribute(IdentifierUtil.REALM_ATTR_DEFAULT_REGION)).thenReturn("GB");
        assertThat(IdentifierUtil.defaultRegion(session), equalTo("GB"));
    }

    @Test
    void defaultRegion_fallback_whenNoLocaleNoAttr() {
        when(realm.getDefaultLocale()).thenReturn(null);
        when(realm.getAttribute(IdentifierUtil.REALM_ATTR_DEFAULT_REGION)).thenReturn(null);
        assertThat(IdentifierUtil.defaultRegion(session), equalTo("IN"));
    }
}
