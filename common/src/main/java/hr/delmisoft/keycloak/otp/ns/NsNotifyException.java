package hr.delmisoft.keycloak.otp.ns;

public class NsNotifyException extends Exception {
    private final int status;

    public NsNotifyException(String message, int status) {
        super(message);
        this.status = status;
    }

    public NsNotifyException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    /** HTTP status NS answered with; -1 for I/O and configuration errors. */
    public int status() {
        return status;
    }
}
