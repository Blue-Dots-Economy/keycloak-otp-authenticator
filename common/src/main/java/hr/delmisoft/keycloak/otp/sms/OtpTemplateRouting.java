package hr.delmisoft.keycloak.otp.sms;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class OtpTemplateRouting {

    static final OtpTemplateRouting NONE = new OtpTemplateRouting(Collections.emptyMap(), Collections.emptySet());

    private final Map<String, String> clientApps;
    private final Set<String> signinNeedsPhoneVerifiedApps;

    OtpTemplateRouting(Map<String, String> clientApps, Set<String> signinNeedsPhoneVerifiedApps) {
        this.clientApps = clientApps;
        this.signinNeedsPhoneVerifiedApps = signinNeedsPhoneVerifiedApps;
    }

    static OtpTemplateRouting fromSettings(String clientApps, String signinNeedsPhoneVerifiedApps) {
        return new OtpTemplateRouting(parseClientAppMap(clientApps), parseAppList(signinNeedsPhoneVerifiedApps));
    }

    String templateFor(SmsContext context) {
        if (context == null || context.otpFlow() == null || context.clientId() == null) {
            return null;
        }
        String app = clientApps.get(context.clientId());
        if (app == null) {
            return null;
        }
        String flow = context.otpFlow();
        if (!SmsContext.SIGNIN.equals(flow) && !SmsContext.SIGNUP.equals(flow)) {
            return null;
        }
        if (signinNeedsPhoneVerifiedApps.contains(app)) {
            flow = context.phoneVerified() ? SmsContext.SIGNIN : SmsContext.SIGNUP;
        }
        return app + "_" + flow + "_otp";
    }

    static Set<String> parseAppList(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw.split(",")) {
                if (!item.isBlank()) {
                    out.add(item.trim());
                }
            }
        }
        return out;
    }

    static Map<String, String> parseClientAppMap(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String client = pair.substring(0, eq).trim();
            String app = pair.substring(eq + 1).trim();
            if (!client.isEmpty() && app.matches("[a-z0-9_]+")) {
                out.put(client, app);
            }
        }
        return out;
    }
}
