package hr.delmisoft.keycloak.otp.sms;

import org.keycloak.provider.Provider;

public interface SmsProvider extends Provider {

    void send(String phoneNumber, String message) throws SmsException;

    default void send(String phoneNumber, String message, SmsContext context) throws SmsException {
        send(phoneNumber, message);
    }
}
