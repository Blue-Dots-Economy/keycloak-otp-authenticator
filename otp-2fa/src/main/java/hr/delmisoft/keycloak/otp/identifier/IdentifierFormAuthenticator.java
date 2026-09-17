package hr.delmisoft.keycloak.otp.identifier;

import hr.delmisoft.keycloak.otp.sms.SmsOtpConst;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.Optional;

/**
 * First-step authenticator for the OTP login flow. Accepts a single free-form
 * identifier (email, phone, or username), resolves it to a {@link UserModel} via
 * {@link hr.delmisoft.keycloak.otp.identifier.IdentifierUtil}, and stores the
 * detected type as an auth-session note so downstream authenticators (e.g.,
 * {@code OtpChannelChoiceAuthenticator}) can route to the correct channel
 * automatically without prompting the user again.
 *
 * <p>Replaces the standard Keycloak username form for OTP-only login flows.
 * Does NOT collect a password — OTP is the credential.
 */
public class IdentifierFormAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(IdentifierFormAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        context.challenge(context.form().createForm(IdentifierFormConst.LOGIN_TEMPLATE));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        String identifier = context.getHttpRequest().getDecodedFormParameters().getFirst(IdentifierFormConst.PARAM_IDENTIFIER);

        if (identifier == null || identifier.isBlank()) {
            context.getEvent().error(Errors.USERNAME_MISSING);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER,
                    context.form()
                            .setError(IdentifierFormConst.ERROR_IDENTIFIER_BLANK)
                            .createForm(IdentifierFormConst.LOGIN_TEMPLATE));
            return;
        }

        String trimmed = identifier.trim();
        IdentifierUtil.IdentifierType type = IdentifierUtil.detect(trimmed);
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();

        Optional<UserModel> userOpt = IdentifierUtil.findUser(session, realm, trimmed);

        if (userOpt.isEmpty()) {
            LOG.debugf("Identifier '%s' (type=%s) did not match any user", trimmed, type);
            context.getEvent().error(Errors.USER_NOT_FOUND);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER,
                    context.form()
                            .setAttribute(IdentifierFormConst.PARAM_IDENTIFIER, trimmed)
                            .setError(IdentifierFormConst.ERROR_IDENTIFIER_USER_NOT_FOUND)
                            .createForm(IdentifierFormConst.LOGIN_TEMPLATE));
            return;
        }

        UserModel user = userOpt.get();

        // Reject if user has no channel matching the identifier type — prevents
        // "send SMS to user with no phone" or "send email to user with no email".
        if (!hasChannelFor(user, type)) {
            LOG.warnf("User %s has no channel for identifier type %s", user.getUsername(), type);
            context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER,
                    context.form()
                            .setAttribute(IdentifierFormConst.PARAM_IDENTIFIER, trimmed)
                            .setError(IdentifierFormConst.ERROR_IDENTIFIER_NO_CHANNEL)
                            .createForm(IdentifierFormConst.LOGIN_TEMPLATE));
            return;
        }

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        authSession.setAuthNote(IdentifierFormConst.AUTH_NOTE_IDENTIFIER, trimmed);
        authSession.setAuthNote(IdentifierFormConst.AUTH_NOTE_IDENTIFIER_TYPE, type.name());

        context.getEvent().detail(Details.USERNAME, user.getUsername());
        context.setUser(user);
        context.success();
    }

    private static boolean hasChannelFor(UserModel user, IdentifierUtil.IdentifierType type) {
        switch (type) {
            case EMAIL:
                return user.getEmail() != null && !user.getEmail().isBlank();
            case PHONE:
                String phone = user.getFirstAttribute(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE);
                return phone != null && !phone.isBlank();
            case USERNAME:
            default:
                // Username login — accept either channel; downstream picker decides.
                return (user.getEmail() != null && !user.getEmail().isBlank())
                        || (user.getFirstAttribute(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE) != null
                            && !user.getFirstAttribute(SmsOtpConst.DEFAULT_PHONE_ATTRIBUTE).isBlank());
        }
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
