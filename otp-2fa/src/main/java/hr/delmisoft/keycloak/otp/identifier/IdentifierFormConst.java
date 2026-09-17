package hr.delmisoft.keycloak.otp.identifier;

/**
 * Constants for the identifier form authenticator (Phase 1 of OTP login flow).
 */
public final class IdentifierFormConst {

    private IdentifierFormConst() {}

    public static final String PROVIDER_ID = "otp-identifier-form";

    public static final String LOGIN_TEMPLATE = "login-otp-identifier.ftl";

    public static final String PARAM_IDENTIFIER = "identifier";

    public static final String AUTH_NOTE_IDENTIFIER = "otpIdentifier";
    public static final String AUTH_NOTE_IDENTIFIER_TYPE = "otpIdentifierType";

    // Error keys (resolved against theme messages)
    public static final String ERROR_IDENTIFIER_BLANK = "otpIdentifierBlank";
    public static final String ERROR_IDENTIFIER_USER_NOT_FOUND = "otpIdentifierUserNotFound";
    public static final String ERROR_IDENTIFIER_NO_CHANNEL = "otpIdentifierNoChannel";
}
