package hr.delmisoft.keycloak.otp.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OtpVerificationRecorderTest {

    private static final String PHONE_ATTR = "phoneNumber";
    private static final String VERIFIED_ATTR = "phoneNumberVerified";

    @Mock private UserModel user;

    // --- email ---

    @Test
    void markEmailVerified_setsFlag() {
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.isEmailVerified()).thenReturn(false);

        assertThat(OtpVerificationRecorder.markEmailVerified(user, "user@example.com"), equalTo(true));
        verify(user).setEmailVerified(true);
    }

    @Test
    void markEmailVerified_ignoresCaseOfTarget() {
        when(user.getEmail()).thenReturn("User@Example.com");
        when(user.isEmailVerified()).thenReturn(false);

        assertThat(OtpVerificationRecorder.markEmailVerified(user, "user@example.com"), equalTo(true));
        verify(user).setEmailVerified(true);
    }

    @Test
    void markEmailVerified_nullTarget_stillSetsFlag() {
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.isEmailVerified()).thenReturn(false);

        assertThat(OtpVerificationRecorder.markEmailVerified(user, null), equalTo(true));
        verify(user).setEmailVerified(true);
    }

    @Test
    void markEmailVerified_targetNoLongerAccountEmail_skips() {
        when(user.getEmail()).thenReturn("new@example.com");
        when(user.isEmailVerified()).thenReturn(false);

        assertThat(OtpVerificationRecorder.markEmailVerified(user, "old@example.com"), equalTo(false));
        verify(user, never()).setEmailVerified(true);
    }

    @Test
    void markEmailVerified_alreadyVerified_isNoWrite() {
        when(user.getEmail()).thenReturn("user@example.com");
        when(user.isEmailVerified()).thenReturn(true);

        assertThat(OtpVerificationRecorder.markEmailVerified(user, "user@example.com"), equalTo(false));
        verify(user, never()).setEmailVerified(true);
    }

    @Test
    void markEmailVerified_noEmailOnProfile_skips() {
        when(user.getEmail()).thenReturn(null);

        assertThat(OtpVerificationRecorder.markEmailVerified(user, "user@example.com"), equalTo(false));
        verify(user, never()).setEmailVerified(true);
    }

    @Test
    void markEmailVerified_nullUser_skips() {
        assertThat(OtpVerificationRecorder.markEmailVerified(null, "user@example.com"), equalTo(false));
    }

    // --- phone ---

    @Test
    void markPhoneVerified_setsAttribute() {
        when(user.getFirstAttribute(PHONE_ATTR)).thenReturn("+919876543210");
        when(user.getFirstAttribute(VERIFIED_ATTR)).thenReturn(null);

        assertThat(OtpVerificationRecorder.markPhoneVerified(user, PHONE_ATTR, VERIFIED_ATTR, "+919876543210"),
                equalTo(true));
        verify(user).setSingleAttribute(VERIFIED_ATTR, "true");
    }

    @Test
    void markPhoneVerified_nullTarget_stillSetsAttribute() {
        when(user.getFirstAttribute(PHONE_ATTR)).thenReturn("+919876543210");
        when(user.getFirstAttribute(VERIFIED_ATTR)).thenReturn(null);

        assertThat(OtpVerificationRecorder.markPhoneVerified(user, PHONE_ATTR, VERIFIED_ATTR, null), equalTo(true));
        verify(user).setSingleAttribute(VERIFIED_ATTR, "true");
    }

    @Test
    void markPhoneVerified_targetNoLongerProfileNumber_skips() {
        when(user.getFirstAttribute(PHONE_ATTR)).thenReturn("+919999999999");
        when(user.getFirstAttribute(VERIFIED_ATTR)).thenReturn(null);

        assertThat(OtpVerificationRecorder.markPhoneVerified(user, PHONE_ATTR, VERIFIED_ATTR, "+919876543210"),
                equalTo(false));
        verify(user, never()).setSingleAttribute(VERIFIED_ATTR, "true");
    }

    @Test
    void markPhoneVerified_alreadyVerified_isNoWrite() {
        when(user.getFirstAttribute(PHONE_ATTR)).thenReturn("+919876543210");
        when(user.getFirstAttribute(VERIFIED_ATTR)).thenReturn("true");

        assertThat(OtpVerificationRecorder.markPhoneVerified(user, PHONE_ATTR, VERIFIED_ATTR, "+919876543210"),
                equalTo(false));
        verify(user, never()).setSingleAttribute(VERIFIED_ATTR, "true");
    }

    @Test
    void markPhoneVerified_noNumberOnProfile_skips() {
        when(user.getFirstAttribute(PHONE_ATTR)).thenReturn(null);

        assertThat(OtpVerificationRecorder.markPhoneVerified(user, PHONE_ATTR, VERIFIED_ATTR, "+919876543210"),
                equalTo(false));
        verify(user, never()).setSingleAttribute(VERIFIED_ATTR, "true");
    }

    @Test
    void markPhoneVerified_nullUser_skips() {
        assertThat(OtpVerificationRecorder.markPhoneVerified(null, PHONE_ATTR, VERIFIED_ATTR, "+919876543210"),
                equalTo(false));
    }
}
