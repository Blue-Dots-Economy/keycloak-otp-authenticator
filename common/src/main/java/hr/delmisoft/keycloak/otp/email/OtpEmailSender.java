package hr.delmisoft.keycloak.otp.email;

import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.Provider;

/** Delivers a login OTP by email. Keycloak generates and verifies the code. */
public interface OtpEmailSender extends Provider {
    void send(RealmModel realm, UserModel user, String code) throws OtpEmailException;
}
