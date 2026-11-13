package hr.delmisoft.keycloak.otp;

/**
 * Masks the address an OTP was sent to, so the code page can name it without showing it in full.
 */
public final class OtpTargetMask {

    private static final String MASK = "***";

    private OtpTargetMask() {
    }

    /**
     * Keeps the first two characters of the local part (one when it is that short) and the domain.
     *
     * @param email the address the code was sent to
     * @return the masked address, or {@code null} when {@code email} is not an address
     */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return null;
        }
        int keep = at > 2 ? 2 : 1;
        return email.substring(0, keep) + MASK + email.substring(at);
    }
}
