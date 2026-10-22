package hr.delmisoft.keycloak.otp.sms;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SmsContext(String otpFlow, String clientId, Map<String, String> variables, boolean phoneVerified) {

    public static final String SIGNIN = "signin";
    public static final String SIGNUP = "signup";

    public SmsContext {
        variables = variables == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
