package hr.delmisoft.keycloak.otp.grant;

import hr.delmisoft.keycloak.otp.email.OtpEmailException;
import hr.delmisoft.keycloak.otp.sms.SmsException;
import org.junit.jupiter.api.Test;
import org.keycloak.email.EmailException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

class AbstractOtpGrantTypeTest {

    @Test
    void tokenParameterNames_matchKeycloakBuiltInGrants() {
        // Built-in grants return an empty set: every parameter gets Keycloak's default length limit.
        assertThat(new EmailOtpGrantType().getTokenParameterNames(), empty());
        assertThat(new SmsOtpGrantType().getTokenParameterNames(), empty());
    }

    @Test
    void sendFailureSummary_emailNamesTransportAndCauseClassOnly() {
        EmailException smtp = new EmailException("550 rejected: asha@example.org");
        String summary = AbstractOtpGrantType.sendFailureSummary(
                new OtpEmailException("smtp", "Failed to send OTP email over SMTP", smtp));
        assertThat(summary, equalTo("transport=smtp error=EmailException"));
    }

    @Test
    void sendFailureSummary_emailWithoutCauseNamesTheExceptionClass() {
        String summary = AbstractOtpGrantType.sendFailureSummary(
                new OtpEmailException("http", "User has no email address", null));
        assertThat(summary, equalTo("transport=http error=OtpEmailException"));
    }

    @Test
    void sendFailureSummary_smsNamesCauseClassOnly() {
        String summary = AbstractOtpGrantType.sendFailureSummary(
                new SmsException("send failed for +91******3210", new java.io.IOException("x")));
        assertThat(summary, equalTo("transport=sms error=IOException"));
    }

    @Test
    void sendFailureSummary_otherFailureNamesItsClass() {
        String summary = AbstractOtpGrantType.sendFailureSummary(
                new IllegalStateException("User has no phone number configured"));
        assertThat(summary, equalTo("transport=unknown error=IllegalStateException"));
    }
}
