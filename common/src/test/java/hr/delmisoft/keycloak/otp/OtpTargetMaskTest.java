package hr.delmisoft.keycloak.otp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class OtpTargetMaskTest {

    @Test
    void keepsTwoCharactersAndTheDomain() {
        assertEquals("sr***@yopmail.com", OtpTargetMask.email("srivatsa@yopmail.com"));
    }

    @Test
    void keepsOneCharacterOfAShortLocalPart() {
        assertEquals("a***@x.org", OtpTargetMask.email("ab@x.org"));
        assertEquals("a***@x.org", OtpTargetMask.email("a@x.org"));
    }

    @Test
    void returnsNullForSomethingThatIsNotAnAddress() {
        assertNull(OtpTargetMask.email(null));
        assertNull(OtpTargetMask.email("no-at-sign"));
        assertNull(OtpTargetMask.email("@x.org"));
        assertNull(OtpTargetMask.email("user@"));
    }
}
