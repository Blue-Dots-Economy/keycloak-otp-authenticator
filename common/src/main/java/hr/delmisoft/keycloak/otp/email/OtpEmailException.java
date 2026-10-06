package hr.delmisoft.keycloak.otp.email;

public class OtpEmailException extends Exception {
    public OtpEmailException(String message) { super(message); }
    public OtpEmailException(String message, Throwable cause) { super(message, cause); }
}
