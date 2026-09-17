package hr.delmisoft.keycloak.otp.sms;

/**
 * Redaction helpers for anything an SMS provider writes to the Keycloak log.
 *
 * <p>Auth logs ship to a central store and are retained, so a phone number
 * emitted here is published for as long as that retention lasts — a later fix
 * does not unpublish it. Every provider logs through these helpers so the rule
 * cannot drift between one provider and the next.
 */
public final class SmsLogSafe {

    /** Enough of the response to diagnose a failure, not enough to dump request context. */
    private static final int MAX_RESPONSE_CHARS = 200;

    private SmsLogSafe() {}

    /**
     * Logs are not a place for whole phone numbers. Keeps the last four digits,
     * which is enough to correlate a complaint with a send without recording
     * the subscriber. A number of four digits or fewer masks completely.
     */
    public static String maskPhone(String phoneNumber) {
        if (phoneNumber == null) {
            return "****";
        }
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        return digits.length() <= 4 ? "****" : "****" + digits.substring(digits.length() - 4);
    }

    /**
     * Provider responses echo request context back and are unbounded, so they
     * are only worth logging on a failure and only up to a limit. Truncation is
     * marked so a clipped body is never mistaken for the whole one.
     */
    public static String boundedResponse(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return body.length() <= MAX_RESPONSE_CHARS
                ? body
                : body.substring(0, MAX_RESPONSE_CHARS) + "…[truncated " + body.length() + " chars]";
    }
}
