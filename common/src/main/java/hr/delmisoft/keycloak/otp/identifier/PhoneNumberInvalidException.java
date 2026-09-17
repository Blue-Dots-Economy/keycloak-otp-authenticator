package hr.delmisoft.keycloak.otp.identifier;

import com.google.i18n.phonenumbers.NumberParseException;

/**
 * Raised when a phone number cannot be parsed or fails validation. Adapted from
 * cooperlyt/keycloak-phone-provider (Apache 2.0).
 */
public class PhoneNumberInvalidException extends Exception {

    public enum ErrorType {
        VALID_FAIL,
        NOT_SUPPORTED,
        PARSE_FAIL
    }

    private final ErrorType errorType;

    public PhoneNumberInvalidException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public PhoneNumberInvalidException(NumberParseException cause) {
        super("Phone number parse failed: " + cause.getMessage(), cause);
        this.errorType = ErrorType.PARSE_FAIL;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
