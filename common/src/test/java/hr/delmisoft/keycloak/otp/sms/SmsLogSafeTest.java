package hr.delmisoft.keycloak.otp.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@DisplayName("SmsLogSafe")
class SmsLogSafeTest {

    @Test
    @DisplayName("maskPhone keeps only the last four digits")
    void maskPhone_keepsOnlyTheLastFourDigits() {
        assertThat(SmsLogSafe.maskPhone("+91 99999-91234"), equalTo("****1234"));
        assertThat(SmsLogSafe.maskPhone("+14155550199"), equalTo("****0199"));
    }

    @Test
    @DisplayName("maskPhone reveals nothing when there is too little to mask")
    void maskPhone_masksShortAndAbsentNumbersEntirely() {
        assertThat(SmsLogSafe.maskPhone("123"), equalTo("****"));
        assertThat(SmsLogSafe.maskPhone("1234"), equalTo("****"));
        assertThat(SmsLogSafe.maskPhone(""), equalTo("****"));
        assertThat(SmsLogSafe.maskPhone(null), equalTo("****"));
    }

    @Test
    @DisplayName("boundedResponse passes a short body through untouched")
    void boundedResponse_leavesShortBodiesAlone() {
        assertThat(SmsLogSafe.boundedResponse("{\"type\":\"success\"}"),
                equalTo("{\"type\":\"success\"}"));
        assertThat(SmsLogSafe.boundedResponse(""), equalTo(""));
        assertThat(SmsLogSafe.boundedResponse(null), equalTo(""));
    }

    @Test
    @DisplayName("boundedResponse truncates a long body and says it did")
    void boundedResponse_truncatesAndMarksLongBodies() {
        String body = "x".repeat(500);
        String out = SmsLogSafe.boundedResponse(body);
        assertThat(out, startsWith("x".repeat(200)));
        assertThat(out.contains("truncated 500 chars"), equalTo(true));
    }
}
