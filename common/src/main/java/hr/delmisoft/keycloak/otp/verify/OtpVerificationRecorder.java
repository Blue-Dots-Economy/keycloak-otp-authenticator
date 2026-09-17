package hr.delmisoft.keycloak.otp.verify;

import org.jboss.logging.Logger;
import org.keycloak.models.UserModel;

/**
 * Records the outcome of a successful OTP challenge on the user profile, so that
 * "this person proved control of this address / number" outlives the login.
 *
 * <p>Email verification maps onto Keycloak's built-in {@code emailVerified} flag, which
 * feeds the {@code email_verified} claim in issued tokens. Phone verification has no
 * built-in flag, so it is stored as a user attribute (default {@code phoneNumberVerified});
 * surface it in tokens with a User Attribute protocol mapper.
 *
 * <p>Writes are skipped when the delivery target no longer matches the profile value
 * (changed mid-flow) and when the flag is already set, so a repeat login is not a write.
 */
public final class OtpVerificationRecorder {

    private static final Logger LOG = Logger.getLogger(OtpVerificationRecorder.class);

    public static final String VALUE_TRUE = "true";

    private OtpVerificationRecorder() {}

    /**
     * Flags the user's email as verified after a successful email OTP.
     *
     * @param verifiedEmail address the code was sent to; null skips the match check
     * @return true when the flag was flipped by this call
     */
    public static boolean markEmailVerified(UserModel user, String verifiedEmail) {
        if (user == null) {
            return false;
        }
        String current = user.getEmail();
        if (current == null || current.isBlank()) {
            return false;
        }
        if (verifiedEmail != null && !verifiedEmail.equalsIgnoreCase(current)) {
            LOG.warnf("Not marking email verified for user %s — OTP went to a different address than the account email now holds",
                    user.getId());
            return false;
        }
        if (user.isEmailVerified()) {
            return false;
        }
        user.setEmailVerified(true);
        LOG.infof("Marked email verified for user %s after successful OTP", user.getId());
        return true;
    }

    /**
     * Flags the user's phone number as verified after a successful SMS OTP.
     *
     * @param phoneAttribute    attribute holding the number (e.g. {@code phoneNumber})
     * @param verifiedAttribute attribute holding the verified flag (e.g. {@code phoneNumberVerified})
     * @param verifiedPhone     number the code was sent to; null skips the match check
     * @return true when the flag was flipped by this call
     */
    public static boolean markPhoneVerified(UserModel user, String phoneAttribute,
                                            String verifiedAttribute, String verifiedPhone) {
        if (user == null || phoneAttribute == null || verifiedAttribute == null) {
            return false;
        }
        String current = user.getFirstAttribute(phoneAttribute);
        if (current == null || current.isBlank()) {
            return false;
        }
        if (verifiedPhone != null && !verifiedPhone.equals(current)) {
            LOG.warnf("Not marking phone verified for user %s — OTP went to a different number than '%s' now holds",
                    user.getId(), phoneAttribute);
            return false;
        }
        if (VALUE_TRUE.equals(user.getFirstAttribute(verifiedAttribute))) {
            return false;
        }
        user.setSingleAttribute(verifiedAttribute, VALUE_TRUE);
        LOG.infof("Marked phone verified for user %s after successful OTP", user.getId());
        return true;
    }
}
