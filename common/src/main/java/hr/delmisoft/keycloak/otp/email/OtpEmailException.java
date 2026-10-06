package hr.delmisoft.keycloak.otp.email;

/**
 * An OTP email was not sent. The message names the transport and, for notification-service,
 * the HTTP status; it never carries the recipient address or the code. {@link #transport()}
 * is the provider id that failed ({@code smtp}, {@code http}), or {@code unknown}.
 */
public class OtpEmailException extends Exception {
    private static final String UNKNOWN = "unknown";

    private final String transport;

    public OtpEmailException(String message) { this(UNKNOWN, message, null); }
    public OtpEmailException(String message, Throwable cause) { this(UNKNOWN, message, cause); }

    public OtpEmailException(String transport, String message, Throwable cause) {
        super(message, cause);
        this.transport = transport == null || transport.isBlank() ? UNKNOWN : transport;
    }

    /** Provider id of the transport that failed, for logging. */
    public String transport() { return transport; }
}
